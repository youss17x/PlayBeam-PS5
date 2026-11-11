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

int sceAppInstUtilInitialize(void);
int sceAppInstUtilTerminate(void);
int sceAppInstUtilAppInstallAll(void *);
int sceAppInstUtilAppInstallTitleDir(const char *title_id, const char *dir, void *);
int sceSystemServiceLaunchWebBrowser(const char *uri);
int sceLncUtilGetAppIdOfRunningBigApp(void);

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

  if (sceAppInstUtilInitialize() != 0) return -1;
  int rc = -1;
  mkdir("/user/app", 0755);
  mkdir(APP_DIR, 0755);
  mkdir(APP_DIR "/sce_sys", 0755);
  if (write_file(param, pb_param_json, pb_param_json_size) == 0 &&
      write_file(icon, pb_icon0_png, pb_icon0_png_size) == 0) {
    rc = sceAppInstUtilAppInstallTitleDir(TITLE_ID, "/user/app/", 0);
    if (rc != 0) rc = sceAppInstUtilAppInstallAll(0);
  }
  sceAppInstUtilTerminate();
  if (rc == 0) notify("PlayBeam added to the home screen");
  return rc;
}

/* Opens the page in the PS5 browser, unless a game is running (don't pull the player out). */
void home_app_open_page(void) {
  if ((uint32_t)sceLncUtilGetAppIdOfRunningBigApp() != UINT32_MAX) return;
  sceSystemServiceLaunchWebBrowser(PAGE_URL);
}

#else /* building on a PC for testing */
int home_app_install(void) { return 0; }
void home_app_open_page(void) {}
#endif
