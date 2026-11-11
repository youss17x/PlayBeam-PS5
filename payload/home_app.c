/* PlayBeam on the PS5 home screen: installs a "PlayBeam" tile that opens the PlayBeam Server
   page in the PS5 web browser, and opens that page when the server starts.
   The tile install follows the app installer in ftpsrv by John Törnblom (GPLv3) and
   Payload Manager by itsPLK. */

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "activity.h"

#define TITLE_ID "PLBM00001"
#define APP_DIR "/user/app/" TITLE_ID
#define PAGE_URL "http://127.0.0.1:9116/"

#ifdef __PROSPERO__
#include <ps5/kernel.h>

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

INCASSET(pb_param_json, "assets/param.json");
INCASSET(pb_icon0_png, "assets/icon0.png");

/* System libraries are loaded at run time: if one can't be loaded, the server still runs
   (only the home tile / browser are skipped). */
int sceKernelLoadStartModule(const char *path, size_t argc, const void *argv, uint32_t flags, void *opt, int *res);
int sceKernelDlsym(int handle, const char *sym, void **addr);

static void *sym(const char *lib, const char *name) {
  char path[128];
  snprintf(path, sizeof path, "/system/common/lib/%s", lib);
  int h = sceKernelLoadStartModule(path, 0, NULL, 0, NULL, NULL);
  if (h < 0) return NULL;
  void *addr = NULL;
  if (sceKernelDlsym(h, name, &addr) != 0) return NULL;
  return addr;
}

void notify(const char *fmt, ...);

static int same_file(const char *path, const uint8_t *data, size_t size) {
  struct stat st;
  if (stat(path, &st) != 0 || (size_t)st.st_size != size) return 0;
  FILE *f = fopen(path, "rb");
  if (!f) return 0;
  uint8_t *buf = malloc(size);
  int same = buf && fread(buf, 1, size, f) == size && memcmp(buf, data, size) == 0;
  free(buf);
  fclose(f);
  return same;
}

static int write_file(const char *path, const uint8_t *data, size_t size) {
  FILE *f = fopen(path, "wb");
  if (!f) return -1;
  int ok = fwrite(data, 1, size, f) == size;
  return (fclose(f) == 0 && ok) ? 0 : -1;
}

/* Installs or updates the home screen tile. Returns 0 when it is in place. */
int home_app_install(void) {
  const char *param = APP_DIR "/sce_sys/param.json";
  const char *icon = APP_DIR "/sce_sys/icon0.png";
  if (same_file(param, pb_param_json, pb_param_json_size) && same_file(icon, pb_icon0_png, pb_icon0_png_size))
    return 0;

  int (*init)(void) = sym("libSceAppInstUtil.sprx", "sceAppInstUtilInitialize");
  int (*term)(void) = sym("libSceAppInstUtil.sprx", "sceAppInstUtilTerminate");
  int (*install_dir)(const char *, const char *, void *) =
      sym("libSceAppInstUtil.sprx", "sceAppInstUtilAppInstallTitleDir");
  int (*install_all)(void *) = sym("libSceAppInstUtil.sprx", "sceAppInstUtilAppInstallAll");
  if (!init || (!install_dir && !install_all)) { notify("PlayBeam: home tile not available on this PS5"); return -1; }
  int err = init();
  if (err != 0) { notify("PlayBeam: home tile not added (init 0x%08x)", err); return -1; }
  int rc = -1;
  mkdir("/user/app", 0755);
  mkdir(APP_DIR, 0755);
  mkdir(APP_DIR "/sce_sys", 0755);
  if (write_file(param, pb_param_json, pb_param_json_size) == 0 &&
      write_file(icon, pb_icon0_png, pb_icon0_png_size) == 0) {
    rc = install_dir ? install_dir(TITLE_ID, "/user/app/", 0) : -1;
    if (rc != 0 && install_all) rc = install_all(0);
  }
  if (term) term();
  if (rc == 0) notify("PlayBeam added to the home screen");
  else notify("PlayBeam: home tile not added (0x%08x)", rc);
  return rc;
}

/* Opens the page in the PS5 browser, unless a game is running (don't pull the player out). */
void home_app_open_page(void) {
  int (*big_app)(void) = sym("libSceSystemService.sprx", "sceLncUtilGetAppIdOfRunningBigApp");
  int (*user_init)(void *) = sym("libSceUserService.sprx", "sceUserServiceInitialize");
  int (*browser)(const char *, void *) = sym("libSceSystemService.sprx", "sceSystemServiceLaunchWebBrowser");
  if (!browser) return;
  if (big_app && (uint32_t)big_app() != UINT32_MAX) return; /* a game is running */
  if (user_init) user_init(0); /* needed before the browser can be launched */
  int err = browser(PAGE_URL, 0);
  if (err != 0) notify("PlayBeam: couldn't open the browser (0x%08x)", err);
}

#include <pthread.h>

static void *home_thread(void *arg) {
  (void)arg;
  sleep(1); /* the server is answering the app first */
  home_app_install();
  home_app_open_page();
  return NULL;
}

/* Runs in the background so the server keeps working even if this is slow or fails. */
void home_app_start(void) {
  pthread_t th;
  if (pthread_create(&th, NULL, home_thread, NULL) == 0) pthread_detach(th);
}

#else /* building on a PC for testing */
void home_app_start(void) {}
int home_app_install(void) { return 0; }
void home_app_open_page(void) {}
#endif
