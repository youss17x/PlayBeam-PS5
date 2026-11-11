/* PlayBeam home screen tile installer — a one-shot PS5 payload.
   Adds a "PlayBeam" tile to the PS5 home screen that opens the PlayBeam Server page
   (http://127.0.0.1:9116) in the PS5 web browser. Kept apart from PlayBeam Server so a problem
   here can never stop the server.
   Based on the install_app sample of the PS5 Payload SDK by John Törnblom (GPLv3). */

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <ps5/kernel.h>

#define TITLE_ID "PLBM00001"
#define APP_DIR "/user/app/" TITLE_ID
#define AUTOLOAD_DIR "/data/ps5_autoloader"
#define AUTOLOAD_TXT AUTOLOAD_DIR "/autoload.txt"
#define AUTOLOAD_BAK AUTOLOAD_DIR "/autoload.txt.playbeam-backup"
#define SELF_ELF AUTOLOAD_DIR "/playbeam-tile.elf"

#define INCASSET(name, file)                                                   \
  __asm__(".section .rodata\n"                                                 \
          ".global " #name "\n"                                                \
          ".global " #name "_end\n"                                            \
          ".global " #name "_size\n"                                           \
          ".align 16\n" #name ":\n"                                            \
          ".incbin \"" file "\"\n" #name "_end:\n" #name "_size:\n"            \
          ".quad " #name "_end - " #name "\n"                                  \
          ".previous\n");                                                      \
  extern const uint8_t name[];                                                 \
  extern const size_t name##_size;

INCASSET(param_json, "assets/param.json");
INCASSET(icon0_png, "assets/icon0.png");

int sceAppInstUtilInitialize(void);
int sceAppInstUtilTerminate(void);
int sceAppInstUtilAppInstallAll(void *);

/* Same approach as Payload Manager: look the TitleDir installer up by its NID at run time, and
   fall back to "install all" when this firmware doesn't have it. */
static int install_title(const char *title_id) {
  int (*install_dir)(const char *, const char *, void *) = 0;
  uint32_t handle;
  if (!kernel_dynlib_handle(-1, "libSceAppInstUtil.sprx", &handle))
    install_dir = (void *)kernel_dynlib_resolve(-1, handle, "Wudg3Xe3heE");
  if (install_dir) return install_dir(title_id, "/user/app/", 0);
  return sceAppInstUtilAppInstallAll(0);
}

typedef struct notify_request {
  char useless1[45];
  char message[3075];
} notify_request_t;
int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);

static void notify(const char *msg) {
  printf("%s\n", msg);
  fflush(stdout);
  notify_request_t req;
  memset(&req, 0, sizeof req);
  strncpy(req.message, msg, sizeof req.message - 1);
  sceKernelSendNotificationRequest(0, &req, sizeof req, 0);
}

static int write_file(const char *path, const uint8_t *data, size_t size) {
  FILE *f = fopen(path, "wb");
  if (!f) return -1;
  int ok = fwrite(data, 1, size, f) == size;
  return (fclose(f) == 0 && ok) ? 0 : -1;
}

/* The app put this installer in the Y2JB autoloader for one boot (with a backup of
   autoload.txt): put autoload.txt back and remove the installer, whatever happened. */
static void undo_autoload(void) {
  struct stat st;
  if (stat(AUTOLOAD_BAK, &st) == 0) rename(AUTOLOAD_BAK, AUTOLOAD_TXT);
  unlink(SELF_ELF);
}

int main(void) {
  undo_autoload();
  char msg[128];
  printf("playbeam-tile: started\n");
  fflush(stdout);
  notify("PlayBeam: adding the home screen tile…");
  int err = sceAppInstUtilInitialize();
  if (err) {
    snprintf(msg, sizeof msg, "PlayBeam tile: installer not available (0x%08x)", err);
    notify(msg);
    return 1;
  }
  mkdir("/user/app", 0755);
  mkdir(APP_DIR, 0755);
  mkdir(APP_DIR "/sce_sys", 0755);
  if (write_file(APP_DIR "/sce_sys/param.json", param_json, param_json_size) ||
      write_file(APP_DIR "/sce_sys/icon0.png", icon0_png, icon0_png_size)) {
    snprintf(msg, sizeof msg, "PlayBeam tile: can't write files (%s)", strerror(errno));
    notify(msg);
    return 1;
  }
  err = install_title(TITLE_ID);
  sceAppInstUtilTerminate();
  if (err) {
    snprintf(msg, sizeof msg, "PlayBeam tile: install failed (0x%08x)", err);
    notify(msg);
    return 1;
  }
  notify("PlayBeam is on your home screen.\nIt opens the PlayBeam Server page.");
  return 0;
}
