/* PlayBeam Server — PS5 payload that receives files from the PlayBeam Android app.

   Simple line protocol over TCP (port 9113). Each command is one line ending in '\n';
   paths are absolute and are always the rest of the line.

     HELLO                         -> OK PlayBeam-Server <version> [PIN]
                                      "PIN" means AUTH is needed before any other command
     AUTH <pin>                    -> OK | ERR wrong PIN   (5 wrong tries close the connection)
     SETPIN <pin>                  -> OK | ERR <reason>    (4-8 digits; empty removes the PIN)
     MKDIR <path>                  -> OK | ERR <reason>          (like mkdir -p)
     SIZE <path>                   -> OK <bytes>  (OK -1 if missing)
     PUT <offset> <length> <path>  -> READY, then the client sends <length> raw bytes,
                                      then OK | ERR <reason>
                                      offset 0 creates/truncates, offset > 0 continues a file
     CREATE <size> <path>          -> creates an empty file for a split upload:
                                      OK SPARSE <fs> (pieces may be written in parallel) | OK <fs>
     PUTR <offset> <length> <path> -> like PUT but never truncates (one piece of a split upload)
     UNZIP <zip>\t<dest dir>       -> PROG <done> <total> lines while extracting,
                                      then OK <files> | ERR <reason>
     RM <path>                     -> OK | ERR <reason>          (removes one file)
     RMTREE <path>                 -> OK | ERR <reason>          (removes a folder and its content)
     RENAME <from>\t<to>           -> OK | ERR <reason>
     LIST <dir>                    -> one line per entry "D <size> <name>" or "F <size> <name>",
                                      then OK <count> | ERR <reason>
     GET <path>                    -> OK <size>, then <size> raw bytes | ERR <reason>
     STAT <path>                   -> OK F <size> <mtime> | OK D <total size> <mtime> <files> <folders>
     DF <path>                     -> OK <total bytes> <free bytes>
     INFO                          -> key=value lines (model, fw, cpu_temp, soc_temp, cpu_mhz,
                                      uptime, version), then OK
     COPY <from>\t<to>             -> PROG <done> <total> lines, then OK | ERR   (file or folder)
     MOVE <from>\t<to>             -> like COPY; a plain rename when on the same drive
     QUIT                          -> closes this connection
     SHUTDOWN                      -> OK, then the server exits

   A web page on port 9116 shows a QR code for the app and live transfer progress.

   Every connection runs in its own thread, so the app can upload over several
   connections at once. ZIP extraction uses miniz (MIT licence, see miniz-LICENSE). */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#ifdef __FreeBSD__
#include <sys/mount.h>
#endif
#include <sys/sysctl.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include "miniz.h"
#include "activity.h"

#define SERVER_VERSION "1.6"
#ifndef PIN_DIR
#define PIN_DIR "/data/playbeam"
#endif
#define PIN_FILE PIN_DIR "/pin"
#define SERVER_PORT 9113
#define LINE_MAX_LEN 4096
#define IO_BUF_SIZE (4 * 1024 * 1024)
#define SOCK_BUF_SIZE (4 * 1024 * 1024)

/* ---------------------------------------------------------------- notifications */

typedef struct notify_request {
  char useless1[45];
  char message[3075];
} notify_request_t;

int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);

/* hardware info (libkernel) */
int sceKernelGetHwModelName(char *);
long sceKernelGetCpuFrequency(void);
int sceKernelGetCpuTemperature(int *);
int sceKernelGetSocSensorTemperature(int, int *);

static void notify(const char *fmt, ...) {
  notify_request_t req;
  va_list ap;

  bzero(&req, sizeof req);
  va_start(ap, fmt);
  vsnprintf(req.message, sizeof req.message, fmt, ap);
  va_end(ap);
  sceKernelSendNotificationRequest(0, &req, sizeof req, 0);
}

/* ---------------------------------------------------------------- connection i/o */

typedef struct conn {
  int fd;
  char rbuf[64 * 1024]; /* bytes already received but not consumed yet */
  size_t rlen;
  size_t rpos;
  char *io; /* IO_BUF_SIZE scratch buffer */
  int authed;
  int bad_pins;
} conn_t;

static int write_all(int fd, const void *data, size_t len) {
  const char *p = data;
  while (len > 0) {
    ssize_t n = write(fd, p, len);
    if (n < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    p += n;
    len -= (size_t)n;
  }
  return 0;
}

static int send_line(conn_t *c, const char *fmt, ...) {
  char line[LINE_MAX_LEN + 64];
  va_list ap;
  int n;

  va_start(ap, fmt);
  n = vsnprintf(line, sizeof line - 1, fmt, ap);
  va_end(ap);
  if (n < 0) return -1;
  if ((size_t)n > sizeof line - 2) n = (int)(sizeof line - 2);
  line[n++] = '\n';
  return write_all(c->fd, line, (size_t)n);
}

/* Reads up to len bytes, first from the line buffer, then from the socket. */
static ssize_t conn_read(conn_t *c, void *dst, size_t len) {
  if (c->rpos < c->rlen) {
    size_t avail = c->rlen - c->rpos;
    if (avail > len) avail = len;
    memcpy(dst, c->rbuf + c->rpos, avail);
    c->rpos += avail;
    return (ssize_t)avail;
  }
  for (;;) {
    ssize_t n = recv(c->fd, dst, len, 0);
    if (n < 0 && errno == EINTR) continue;
    return n;
  }
}

/* Reads one '\n'-terminated line (without the '\n' or a trailing '\r').
   Returns its length, or -1 when the connection is closed / the line is too long. */
static int read_line(conn_t *c, char *line, size_t cap) {
  size_t len = 0;
  for (;;) {
    if (c->rpos >= c->rlen) {
      ssize_t n;
      do {
        n = recv(c->fd, c->rbuf, sizeof c->rbuf, 0);
      } while (n < 0 && errno == EINTR);
      if (n <= 0) return -1;
      c->rlen = (size_t)n;
      c->rpos = 0;
    }
    char ch = c->rbuf[c->rpos++];
    if (ch == '\n') break;
    if (len + 1 >= cap) return -1;
    line[len++] = ch;
  }
  if (len > 0 && line[len - 1] == '\r') len--;
  line[len] = '\0';
  return (int)len;
}

/* ---------------------------------------------------------------- file helpers */

/* Creates every folder in path (like mkdir -p). If skip_last, the last component is a file. */
static int make_dirs(const char *path, int skip_last) {
  char tmp[LINE_MAX_LEN];
  size_t len = strlen(path);

  if (len == 0 || len >= sizeof tmp) return -1;
  memcpy(tmp, path, len + 1);
  if (skip_last) {
    char *slash = strrchr(tmp, '/');
    if (!slash || slash == tmp) return 0;
    *slash = '\0';
  }
  for (char *p = tmp + 1; *p; p++) {
    if (*p == '/') {
      *p = '\0';
      if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return -1;
      *p = '/';
    }
  }
  if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return -1;
  return 0;
}

static int valid_path(const char *p) {
  return p && p[0] == '/' && strlen(p) < LINE_MAX_LEN - 1;
}

/* ---------------------------------------------------------------- commands */

static void cmd_mkdir(conn_t *c, const char *path) {
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (make_dirs(path, 0) != 0) send_line(c, "ERR %s", strerror(errno));
  else send_line(c, "OK");
}

static void cmd_size(conn_t *c, const char *path) {
  struct stat st;
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) send_line(c, "OK -1");
  else send_line(c, "OK %lld", (long long)st.st_size);
}

static void cmd_rm(conn_t *c, const char *path) {
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (unlink(path) != 0) send_line(c, "ERR %s", strerror(errno));
  else send_line(c, "OK");
}

/* True when pieces of a file can be written at any offset cheaply (UFS, the PS5's internal
   drive). exFAT / FAT USB drives would fill the gaps with zeros, so uploads stay in order there. */
static int sparse_ok(const char *path, char *fs, size_t fslen) {
  snprintf(fs, fslen, "unknown");
#ifdef __FreeBSD__
  struct statfs st;
  if (statfs(path, &st) != 0) return 0;
  snprintf(fs, fslen, "%s", st.f_fstypename);
  return strstr(fs, "fat") == NULL && strstr(fs, "msdos") == NULL;
#else
  (void)path;
  snprintf(fs, fslen, "native");
  return 1;
#endif
}

static void cmd_create(conn_t *c, char *args) {
  char *end;
  long long size = strtoll(args, &end, 10);
  if (*end != ' ' || size < 0) { send_line(c, "ERR bad CREATE"); return; }
  const char *path = end + 1;
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (make_dirs(path, 1) != 0) { send_line(c, "ERR mkdir: %s", strerror(errno)); return; }
  int f = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0777);
  if (f < 0) { send_line(c, "ERR %s", strerror(errno)); return; }
  char fs[32];
  int sparse = sparse_ok(path, fs, sizeof fs);
  if (sparse && size > 0 && ftruncate(f, (off_t)size) != 0) sparse = 0;
  close(f);
  send_line(c, sparse ? "OK SPARSE %s" : "OK %s", fs);
}

/* Returns -1 if the connection broke (caller must close it). */
static int cmd_put(conn_t *c, char *args, int may_truncate) {
  char *end;
  long long offset = strtoll(args, &end, 10);
  if (*end != ' ') { send_line(c, "ERR bad PUT"); return 0; }
  long long length = strtoll(end + 1, &end, 10);
  if (*end != ' ' || offset < 0 || length < 0) { send_line(c, "ERR bad PUT"); return 0; }
  const char *path = end + 1;
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return 0; }

  if (make_dirs(path, 1) != 0) { send_line(c, "ERR mkdir: %s", strerror(errno)); return 0; }
  int flags = O_WRONLY | O_CREAT | (offset == 0 && may_truncate ? O_TRUNC : 0);
  int f = open(path, flags, 0777);
  if (f < 0) { send_line(c, "ERR %s", strerror(errno)); return 0; }
  if (offset > 0 && lseek(f, (off_t)offset, SEEK_SET) < 0) {
    send_line(c, "ERR seek: %s", strerror(errno));
    close(f);
    return 0;
  }
  if (send_line(c, "READY") != 0) { close(f); return -1; }

  int slot = act_begin('u', path, length);
  int write_err = 0;
  long long left = length;
  while (left > 0) {
    size_t want = left > IO_BUF_SIZE ? IO_BUF_SIZE : (size_t)left;
    ssize_t n = conn_read(c, c->io, want);
    if (n <= 0) { close(f); act_end(slot); return -1; } /* client went away mid-file */
    if (!write_err && write_all(f, c->io, (size_t)n) != 0) write_err = errno ? errno : EIO;
    left -= n;
    act_add(slot, n);
  }
  act_end(slot);
  if (close(f) != 0 && !write_err) write_err = errno ? errno : EIO;

  if (write_err) send_line(c, "ERR %s", strerror(write_err));
  else {
    send_line(c, "OK");
    /* a whole file (pieces of a split upload are counted when renamed into place) */
    if (may_truncate) act_file_done();
  }
  return 0;
}

static void join_path(char *out, size_t cap, const char *dir, const char *name) {
  size_t len = strlen(dir);
  if (len > 0 && dir[len - 1] == '/') snprintf(out, cap, "%s%s", dir, name);
  else snprintf(out, cap, "%s/%s", dir, name);
}

static void cmd_list(conn_t *c, const char *path) {
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  DIR *d = opendir(path);
  if (!d) { send_line(c, "ERR %s", strerror(errno)); return; }

  char full[LINE_MAX_LEN];
  unsigned count = 0;
  struct dirent *e;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    if (strchr(e->d_name, '\n')) continue;
    join_path(full, sizeof full, path, e->d_name);
    struct stat st;
    int is_dir = e->d_type == DT_DIR;
    long long size = 0;
    if (stat(full, &st) == 0) {
      is_dir = S_ISDIR(st.st_mode);
      size = is_dir ? 0 : (long long)st.st_size;
    }
    if (send_line(c, "%c %lld %s", is_dir ? 'D' : 'F', size, e->d_name) != 0) break;
    count++;
  }
  closedir(d);
  send_line(c, "OK %u", count);
}

/* Returns -1 if the connection broke (caller must close it). */
static int cmd_get(conn_t *c, const char *path) {
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return 0; }
  int f = open(path, O_RDONLY);
  if (f < 0) { send_line(c, "ERR %s", strerror(errno)); return 0; }
  struct stat st;
  if (fstat(f, &st) != 0 || !S_ISREG(st.st_mode)) {
    send_line(c, "ERR not a file");
    close(f);
    return 0;
  }
  if (send_line(c, "OK %lld", (long long)st.st_size) != 0) { close(f); return -1; }
  int slot = act_begin('d', path, (long long)st.st_size);
  long long left = st.st_size;
  while (left > 0) {
    size_t want = left > IO_BUF_SIZE ? IO_BUF_SIZE : (size_t)left;
    ssize_t n = read(f, c->io, want);
    if (n <= 0) { close(f); act_end(slot); return -1; } /* can't report mid-stream: drop the connection */
    if (write_all(c->fd, c->io, (size_t)n) != 0) { close(f); act_end(slot); return -1; }
    left -= n;
    act_add(slot, n);
  }
  act_end(slot);
  close(f);
  return 0;
}

static int remove_tree(const char *path) {
  struct stat st;
  if (lstat(path, &st) != 0) return -1;
  if (!S_ISDIR(st.st_mode)) return unlink(path);

  DIR *d = opendir(path);
  if (!d) return -1;
  char full[LINE_MAX_LEN];
  struct dirent *e;
  int rc = 0;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    join_path(full, sizeof full, path, e->d_name);
    if (remove_tree(full) != 0) { rc = -1; break; }
  }
  closedir(d);
  if (rc != 0) return rc;
  return rmdir(path);
}

static void cmd_rmtree(conn_t *c, const char *path) {
  if (!valid_path(path) || strcmp(path, "/") == 0) { send_line(c, "ERR bad path"); return; }
  if (remove_tree(path) != 0) send_line(c, "ERR %s", strerror(errno));
  else send_line(c, "OK");
}

static void cmd_rename(conn_t *c, char *args) {
  char *tab = strchr(args, '\t');
  if (!tab) { send_line(c, "ERR usage: RENAME <from><TAB><to>"); return; }
  *tab = '\0';
  if (!valid_path(args) || !valid_path(tab + 1)) { send_line(c, "ERR bad path"); return; }
  if (rename(args, tab + 1) != 0) send_line(c, "ERR %s", strerror(errno));
  else {
    send_line(c, "OK");
    size_t len = strlen(args);
    if (len > 7 && strcmp(args + len - 7, ".pbpart") == 0) act_file_done(); /* split upload finished */
  }
}

/* ---------------------------------------------------------------- copy / move / stat */

typedef struct tree_info {
  unsigned long long bytes;
  unsigned long files;
  unsigned long dirs;
} tree_info_t;

static void tree_measure(const char *path, tree_info_t *t) {
  struct stat st;
  if (lstat(path, &st) != 0) return;
  if (!S_ISDIR(st.st_mode)) {
    t->bytes += (unsigned long long)st.st_size;
    t->files++;
    return;
  }
  t->dirs++;
  DIR *d = opendir(path);
  if (!d) return;
  char full[LINE_MAX_LEN];
  struct dirent *e;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    join_path(full, sizeof full, path, e->d_name);
    tree_measure(full, t);
  }
  closedir(d);
}

static void cmd_stat(conn_t *c, const char *path) {
  struct stat st;
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (stat(path, &st) != 0) { send_line(c, "ERR %s", strerror(errno)); return; }
  if (!S_ISDIR(st.st_mode)) {
    send_line(c, "OK F %lld %lld", (long long)st.st_size, (long long)st.st_mtime);
    return;
  }
  tree_info_t t = {0};
  tree_measure(path, &t);
  send_line(c, "OK D %llu %lld %lu %lu", t.bytes, (long long)st.st_mtime, t.files,
            t.dirs > 0 ? t.dirs - 1 : 0);
}

static void cmd_df(conn_t *c, const char *path) {
  struct statvfs v;
  if (!valid_path(path)) { send_line(c, "ERR bad path"); return; }
  if (statvfs(path, &v) != 0) { send_line(c, "ERR %s", strerror(errno)); return; }
  unsigned long long unit = v.f_frsize ? v.f_frsize : v.f_bsize;
  send_line(c, "OK %llu %llu", (unsigned long long)v.f_blocks * unit, (unsigned long long)v.f_bavail * unit);
}

static void cmd_info(conn_t *c) {
  char buf[512];
  int temp = 0;

  buf[0] = '\0';
  if (sceKernelGetHwModelName(buf) == 0 && buf[0]) send_line(c, "model=%s", buf);

  size_t len = sizeof buf - 1;
  if (sysctlbyname("kern.version", buf, &len, NULL, 0) == 0) {
    buf[len < sizeof buf ? len : sizeof buf - 1] = '\0';
    for (char *p = buf; *p; p++) if (*p == '\n' || *p == '\t') *p = ' ';
    send_line(c, "fw=%s", buf);
  }

  struct timeval boot;
  len = sizeof boot;
  if (sysctlbyname("kern.boottime", &boot, &len, NULL, 0) == 0 && boot.tv_sec > 0) {
    send_line(c, "uptime=%lld", (long long)(time(NULL) - boot.tv_sec));
  }

  if (sceKernelGetCpuTemperature(&temp) == 0) send_line(c, "cpu_temp=%d", temp);
  if (sceKernelGetSocSensorTemperature(0, &temp) == 0) send_line(c, "soc_temp=%d", temp);
  long hz = sceKernelGetCpuFrequency();
  if (hz > 0) send_line(c, "cpu_mhz=%ld", hz / (1000 * 1000));
  send_line(c, "version=" SERVER_VERSION);
  send_line(c, "OK");
}

typedef struct copy_ctx {
  conn_t *c;
  unsigned long long done;
  unsigned long long total;
  time_t last_report;
} copy_ctx_t;

static void copy_report(copy_ctx_t *k, int force) {
  time_t now = time(NULL);
  if (force || now != k->last_report) {
    k->last_report = now;
    send_line(k->c, "PROG %llu %llu", k->done, k->total);
  }
}

static int copy_file(copy_ctx_t *k, const char *src, const char *dst, mode_t mode) {
  int in = open(src, O_RDONLY);
  if (in < 0) return -1;
  int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, mode ? mode : 0777);
  if (out < 0) { int e = errno; close(in); errno = e; return -1; }
  int rc = 0;
  for (;;) {
    ssize_t n = read(in, k->c->io, IO_BUF_SIZE);
    if (n == 0) break;
    if (n < 0) { if (errno == EINTR) continue; rc = -1; break; }
    if (write_all(out, k->c->io, (size_t)n) != 0) { rc = -1; break; }
    k->done += (unsigned long long)n;
    copy_report(k, 0);
  }
  int e = errno;
  close(in);
  if (close(out) != 0 && rc == 0) { rc = -1; e = errno; }
  errno = e;
  return rc;
}

static int copy_tree(copy_ctx_t *k, const char *src, const char *dst) {
  struct stat st;
  if (lstat(src, &st) != 0) return -1;
  if (!S_ISDIR(st.st_mode)) return copy_file(k, src, dst, st.st_mode & 0777);

  if (mkdir(dst, 0777) != 0 && errno != EEXIST) return -1;
  DIR *d = opendir(src);
  if (!d) return -1;
  char s2[LINE_MAX_LEN], d2[LINE_MAX_LEN];
  struct dirent *e;
  int rc = 0;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    join_path(s2, sizeof s2, src, e->d_name);
    join_path(d2, sizeof d2, dst, e->d_name);
    if (copy_tree(k, s2, d2) != 0) { rc = -1; break; }
  }
  int err = errno;
  closedir(d);
  errno = err;
  return rc;
}

/* Splits "<from>\t<to>" and checks both; the destination must not exist yet
   and a folder can't go inside itself. Returns 0 if fine (reply already sent otherwise). */
static int two_paths(conn_t *c, char *args, char **from, char **to) {
  char *tab = strchr(args, '\t');
  if (!tab) { send_line(c, "ERR usage: <from><TAB><to>"); return -1; }
  *tab = '\0';
  *from = args;
  *to = tab + 1;
  if (!valid_path(*from) || !valid_path(*to)) { send_line(c, "ERR bad path"); return -1; }
  struct stat st;
  if (lstat(*from, &st) != 0) { send_line(c, "ERR %s", strerror(errno)); return -1; }
  if (lstat(*to, &st) == 0) { send_line(c, "ERR already exists"); return -1; }
  size_t fl = strlen(*from);
  if (strncmp(*to, *from, fl) == 0 && ((*to)[fl] == '/' || (*to)[fl] == '\0')) {
    send_line(c, "ERR can't put a folder inside itself");
    return -1;
  }
  if (make_dirs(*to, 1) != 0) { send_line(c, "ERR mkdir: %s", strerror(errno)); return -1; }
  return 0;
}

static void cmd_copy(conn_t *c, char *args, int move) {
  char *from, *to;
  if (two_paths(c, args, &from, &to) != 0) return;

  if (move && rename(from, to) == 0) { send_line(c, "OK"); return; }
  if (move && errno != EXDEV) { send_line(c, "ERR %s", strerror(errno)); return; }

  /* copy (or move across drives: copy, then remove the original) */
  tree_info_t t = {0};
  tree_measure(from, &t);
  copy_ctx_t k = { .c = c, .total = t.bytes };
  copy_report(&k, 1);
  if (copy_tree(&k, from, to) != 0) {
    int e = errno;
    remove_tree(to); /* don't leave a half copy behind */
    send_line(c, "ERR %s", strerror(e));
    return;
  }
  copy_report(&k, 1);
  if (move && remove_tree(from) != 0) {
    send_line(c, "ERR copied, but couldn't remove the original: %s", strerror(errno));
    return;
  }
  send_line(c, "OK");
}

typedef struct unzip_ctx {
  conn_t *c;
  int fd;
  uint64_t done;
  uint64_t total;
  time_t last_report;
  int failed;
} unzip_ctx_t;

static size_t unzip_write(void *opaque, mz_uint64 ofs, const void *buf, size_t n) {
  unzip_ctx_t *u = opaque;
  (void)ofs; /* data arrives in order */
  if (write_all(u->fd, buf, n) != 0) { u->failed = 1; return 0; }
  u->done += n;
  time_t now = time(NULL);
  if (now != u->last_report) {
    u->last_report = now;
    send_line(u->c, "PROG %llu %llu", (unsigned long long)u->done, (unsigned long long)u->total);
  }
  return n;
}

/* An entry name is safe when it is relative and never goes up with "..". */
static int safe_entry(const char *name) {
  if (name[0] == '/' || name[0] == '\0') return 0;
  const char *p = name;
  while (*p) {
    if (p[0] == '.' && p[1] == '.' && (p[2] == '/' || p[2] == '\\' || p[2] == '\0')) return 0;
    const char *slash = strpbrk(p, "/\\");
    if (!slash) break;
    p = slash + 1;
  }
  return 1;
}

static void cmd_unzip(conn_t *c, char *args) {
  char *tab = strchr(args, '\t');
  if (!tab) { send_line(c, "ERR usage: UNZIP <zip><TAB><dest>"); return; }
  *tab = '\0';
  const char *zpath = args;
  const char *dest = tab + 1;
  if (!valid_path(zpath) || !valid_path(dest)) { send_line(c, "ERR bad path"); return; }

  mz_zip_archive zip;
  memset(&zip, 0, sizeof zip);
  if (!mz_zip_reader_init_file_v2(&zip, zpath, MZ_ZIP_FLAG_DO_NOT_SORT_CENTRAL_DIRECTORY, 0, 0)) {
    send_line(c, "ERR can't open zip: %s", mz_zip_get_error_string(mz_zip_get_last_error(&zip)));
    return;
  }

  mz_uint count = mz_zip_reader_get_num_files(&zip);
  unzip_ctx_t u = { .c = c, .fd = -1 };
  for (mz_uint i = 0; i < count; i++) {
    mz_zip_archive_file_stat st;
    if (mz_zip_reader_file_stat(&zip, i, &st) && !st.m_is_directory) u.total += st.m_uncomp_size;
  }
  if (make_dirs(dest, 0) != 0) {
    send_line(c, "ERR mkdir: %s", strerror(errno));
    mz_zip_reader_end(&zip);
    return;
  }
  send_line(c, "PROG 0 %llu", (unsigned long long)u.total);

  char out[LINE_MAX_LEN];
  mz_uint files = 0;
  for (mz_uint i = 0; i < count; i++) {
    mz_zip_archive_file_stat st;
    if (!mz_zip_reader_file_stat(&zip, i, &st)) continue;
    if (!safe_entry(st.m_filename)) continue; /* skip "../" tricks */
    if (snprintf(out, sizeof out, "%s/%s", dest, st.m_filename) >= (int)sizeof out) continue;
    for (char *p = out; *p; p++) if (*p == '\\') *p = '/';

    if (st.m_is_directory) {
      make_dirs(out, 0);
      continue;
    }
    if (make_dirs(out, 1) != 0) {
      send_line(c, "ERR mkdir %s: %s", st.m_filename, strerror(errno));
      mz_zip_reader_end(&zip);
      return;
    }
    u.fd = open(out, O_WRONLY | O_CREAT | O_TRUNC, 0777);
    if (u.fd < 0) {
      send_line(c, "ERR %s: %s", st.m_filename, strerror(errno));
      mz_zip_reader_end(&zip);
      return;
    }
    mz_bool ok = mz_zip_reader_extract_to_callback(&zip, i, unzip_write, &u, 0);
    close(u.fd);
    u.fd = -1;
    if (!ok || u.failed) {
      send_line(c, "ERR %s: %s", st.m_filename,
                u.failed ? "write failed" : mz_zip_get_error_string(mz_zip_get_last_error(&zip)));
      mz_zip_reader_end(&zip);
      return;
    }
    files++;
  }
  mz_zip_reader_end(&zip);
  send_line(c, "PROG %llu %llu", (unsigned long long)u.total, (unsigned long long)u.total);
  send_line(c, "OK %u", files);
  notify("PlayBeam: extracted %u files", files);
}

/* ---------------------------------------------------------------- server */

/* ---------------------------------------------------------------- PIN */

/* Reads the PIN saved on the PS5 into out; returns 0 if there is none. */
static int load_pin(char *out, size_t len) {
  out[0] = 0;
  int f = open(PIN_FILE, O_RDONLY);
  if (f < 0) return 0;
  ssize_t n = read(f, out, len - 1);
  close(f);
  if (n <= 0) { out[0] = 0; return 0; }
  out[n] = 0;
  out[strcspn(out, "\r\n ")] = 0;
  return out[0] != 0;
}

static int valid_pin(const char *p) {
  size_t n = strlen(p);
  if (n < 4 || n > 8) return 0;
  for (size_t i = 0; i < n; i++) if (p[i] < '0' || p[i] > '9') return 0;
  return 1;
}

/* Compares without stopping at the first difference. */
static int pin_equal(const char *a, const char *b) {
  size_t la = strlen(a), lb = strlen(b);
  unsigned diff = (unsigned)(la ^ lb);
  for (size_t i = 0; i < la && i < lb; i++) diff |= (unsigned char)(a[i] ^ b[i]);
  return diff == 0;
}

static pthread_mutex_t g_auth_lock = PTHREAD_MUTEX_INITIALIZER;

/* Returns -1 when the connection must be closed (too many wrong PINs). */
static int cmd_auth(conn_t *c, const char *pin) {
  char saved[16];
  pthread_mutex_lock(&g_auth_lock);
  int good = !load_pin(saved, sizeof saved) || pin_equal(pin, saved);
  /* one wrong guess per second for the whole server, even over many connections */
  if (!good) sleep(1);
  pthread_mutex_unlock(&g_auth_lock);
  if (good) {
    c->authed = 1;
    c->bad_pins = 0;
    send_line(c, "OK");
    return 0;
  }
  if (++c->bad_pins >= 5) {
    send_line(c, "ERR wrong PIN");
    notify("PlayBeam: blocked a connection after 5 wrong PINs");
    return -1;
  }
  send_line(c, "ERR wrong PIN");
  return 0;
}

static void cmd_setpin(conn_t *c, const char *pin) {
  if (*pin == 0) {
    if (unlink(PIN_FILE) != 0 && errno != ENOENT) { send_line(c, "ERR %s", strerror(errno)); return; }
    send_line(c, "OK");
    notify("PlayBeam Server: PIN removed");
    return;
  }
  if (!valid_pin(pin)) { send_line(c, "ERR the PIN must be 4 to 8 digits"); return; }
  mkdir(PIN_DIR, 0700);
  int f = open(PIN_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0600);
  if (f < 0) { send_line(c, "ERR %s", strerror(errno)); return; }
  size_t n = strlen(pin);
  int ok = write(f, pin, n) == (ssize_t)n;
  close(f);
  if (!ok) { send_line(c, "ERR can't save the PIN"); return; }
  send_line(c, "OK");
  notify("PlayBeam Server: PIN set");
}

/* ---------------------------------------------------------------- activity (web page) */

#define MAX_ACT 32
typedef struct act {
  int used;
  char kind;
  char name[160];
  long long done, total;
} act_t;

static act_t g_act[MAX_ACT];
static pthread_mutex_t g_act_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned long long g_bytes_in, g_bytes_out;
static unsigned long g_files_in;
static time_t g_started;

int act_begin(char kind, const char *path, long long total) {
  const char *base = strrchr(path, '/');
  base = base ? base + 1 : path;
  char name[160];
  snprintf(name, sizeof name, "%s", base);
  /* a split upload writes "<name>.pbpart": show the real name */
  size_t len = strlen(name);
  if (len > 7 && strcmp(name + len - 7, ".pbpart") == 0) name[len - 7] = 0;
  pthread_mutex_lock(&g_act_lock);
  int slot = -1;
  for (int i = 0; i < MAX_ACT; i++)
    if (!g_act[i].used) {
      g_act[i].used = 1;
      g_act[i].kind = kind;
      snprintf(g_act[i].name, sizeof g_act[i].name, "%s", name);
      g_act[i].done = 0;
      g_act[i].total = total;
      slot = i;
      break;
    }
  pthread_mutex_unlock(&g_act_lock);
  return slot;
}

void act_add(int slot, long long bytes) {
  pthread_mutex_lock(&g_act_lock);
  if (slot >= 0) g_act[slot].done += bytes;
  if (slot >= 0 && g_act[slot].kind == 'd') g_bytes_out += (unsigned long long)bytes;
  else g_bytes_in += (unsigned long long)bytes;
  pthread_mutex_unlock(&g_act_lock);
}

void act_end(int slot) {
  if (slot < 0) return;
  pthread_mutex_lock(&g_act_lock);
  g_act[slot].used = 0;
  pthread_mutex_unlock(&g_act_lock);
}

void act_file_done(void) {
  pthread_mutex_lock(&g_act_lock);
  g_files_in++;
  pthread_mutex_unlock(&g_act_lock);
}

int pin_is_set(void) {
  char saved[16];
  return load_pin(saved, sizeof saved);
}

void web_lan_ip(char *out, size_t cap);

size_t act_status_json(char *out, size_t cap) {
  char ip[64];
  web_lan_ip(ip, sizeof ip);
  int pin = pin_is_set();
  pthread_mutex_lock(&g_act_lock);
  size_t n = (size_t)snprintf(out, cap,
      "{\"version\":\"%s\",\"ip\":\"%s\",\"pin\":%s,\"uptime\":%ld,\"files_in\":%lu,"
      "\"bytes_in\":%llu,\"bytes_out\":%llu,\"active\":[",
      SERVER_VERSION, ip, pin ? "true" : "false", (long)(time(NULL) - g_started), g_files_in,
      g_bytes_in, g_bytes_out);
  int first = 1;
  for (int i = 0; i < MAX_ACT && n < cap - 400; i++) {
    if (!g_act[i].used) continue;
    char esc[400];
    size_t e = 0;
    for (const char *p = g_act[i].name; *p && e < sizeof esc - 8; p++) {
      unsigned char ch = (unsigned char)*p;
      if (ch == '"' || ch == '\\') { esc[e++] = '\\'; esc[e++] = (char)ch; }
      else if (ch < 0x20) e += (size_t)snprintf(esc + e, sizeof esc - e, "\\u%04x", ch);
      else esc[e++] = (char)ch;
    }
    esc[e] = 0;
    n += (size_t)snprintf(out + n, cap - n, "%s{\"k\":\"%c\",\"name\":\"%s\",\"done\":%lld,\"total\":%lld}",
                          first ? "" : ",", g_act[i].kind, esc, g_act[i].done, g_act[i].total);
    first = 0;
  }
  pthread_mutex_unlock(&g_act_lock);
  if (n < cap - 3) n += (size_t)snprintf(out + n, cap - n, "]}");
  return n < cap ? n : cap - 1;
}

static volatile int g_running = 1;
static int g_listen_fd = -1;

static void *client_thread(void *arg) {
  conn_t *c = arg;
  char line[LINE_MAX_LEN];

  while (g_running) {
    if (read_line(c, line, sizeof line) < 0) break;

    char saved[16];
    int locked = !c->authed && load_pin(saved, sizeof saved);
    if (!locked) c->authed = 1;

    if (strcmp(line, "HELLO") == 0) {
      send_line(c, locked ? "OK PlayBeam-Server " SERVER_VERSION " PIN" : "OK PlayBeam-Server " SERVER_VERSION);
    } else if (strncmp(line, "AUTH ", 5) == 0 || strcmp(line, "AUTH") == 0) {
      if (cmd_auth(c, line[4] ? line + 5 : "") != 0) break;
    } else if (strcmp(line, "QUIT") == 0) {
      break;
    } else if (locked) {
      send_line(c, "ERR PIN required");
    } else if (strncmp(line, "SETPIN ", 7) == 0 || strcmp(line, "SETPIN") == 0) {
      cmd_setpin(c, line[6] ? line + 7 : "");
    } else if (strncmp(line, "PUT ", 4) == 0) {
      if (cmd_put(c, line + 4, 1) != 0) break;
    } else if (strncmp(line, "PUTR ", 5) == 0) {
      if (cmd_put(c, line + 5, 0) != 0) break;
    } else if (strncmp(line, "CREATE ", 7) == 0) {
      cmd_create(c, line + 7);
    } else if (strncmp(line, "MKDIR ", 6) == 0) {
      cmd_mkdir(c, line + 6);
    } else if (strncmp(line, "SIZE ", 5) == 0) {
      cmd_size(c, line + 5);
    } else if (strncmp(line, "UNZIP ", 6) == 0) {
      cmd_unzip(c, line + 6);
    } else if (strncmp(line, "RM ", 3) == 0) {
      cmd_rm(c, line + 3);
    } else if (strncmp(line, "RMTREE ", 7) == 0) {
      cmd_rmtree(c, line + 7);
    } else if (strncmp(line, "RENAME ", 7) == 0) {
      cmd_rename(c, line + 7);
    } else if (strncmp(line, "LIST ", 5) == 0) {
      cmd_list(c, line + 5);
    } else if (strncmp(line, "GET ", 4) == 0) {
      if (cmd_get(c, line + 4) != 0) break;
    } else if (strncmp(line, "STAT ", 5) == 0) {
      cmd_stat(c, line + 5);
    } else if (strcmp(line, "INFO") == 0) {
      cmd_info(c);
    } else if (strncmp(line, "DF ", 3) == 0) {
      cmd_df(c, line + 3);
    } else if (strncmp(line, "COPY ", 5) == 0) {
      cmd_copy(c, line + 5, 0);
    } else if (strncmp(line, "MOVE ", 5) == 0) {
      cmd_copy(c, line + 5, 1);
    } else if (strcmp(line, "SHUTDOWN") == 0) {
      send_line(c, "OK");
      g_running = 0;
      shutdown(g_listen_fd, SHUT_RDWR);
      close(g_listen_fd);
      break;
    } else {
      send_line(c, "ERR unknown command");
    }
  }
  close(c->fd);
  free(c->io);
  free(c);
  return NULL;
}

int main(void) {
  struct sockaddr_in addr;
  int one = 1;

  g_listen_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (g_listen_fd < 0) {
    notify("PlayBeam Server: socket failed (%s)", strerror(errno));
    return 1;
  }
  setsockopt(g_listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

  memset(&addr, 0, sizeof addr);
  addr.sin_family = AF_INET;
  addr.sin_port = htons(SERVER_PORT);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(g_listen_fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
    notify("PlayBeam Server is already running (port %d)", SERVER_PORT);
    close(g_listen_fd);
    return 0;
  }
  if (listen(g_listen_fd, 16) != 0) {
    notify("PlayBeam Server: listen failed (%s)", strerror(errno));
    close(g_listen_fd);
    return 1;
  }

  g_started = time(NULL);
  web_start();
  char ip[64];
  web_lan_ip(ip, sizeof ip);
  if (ip[0]) notify("PlayBeam Server " SERVER_VERSION " ready\nPage: http://%s:%d", ip, WEB_PORT);
  else notify("PlayBeam Server " SERVER_VERSION " ready on port %d", SERVER_PORT);

  while (g_running) {
    int fd = accept(g_listen_fd, NULL, NULL);
    if (fd < 0) {
      if (errno == EINTR) continue;
      break;
    }
    int size = SOCK_BUF_SIZE;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof size);
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof size);
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);

    conn_t *c = calloc(1, sizeof *c);
    if (c) c->io = malloc(IO_BUF_SIZE);
    if (!c || !c->io) {
      if (c) free(c);
      close(fd);
      continue;
    }
    c->fd = fd;

    pthread_t th;
    if (pthread_create(&th, NULL, client_thread, c) != 0) {
      close(fd);
      free(c->io);
      free(c);
      continue;
    }
    pthread_detach(th);
  }

  notify("PlayBeam Server stopped");
  return 0;
}
