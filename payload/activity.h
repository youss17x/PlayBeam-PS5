/* Live transfer activity, shown on the PlayBeam Server web page (web.c). */
#pragma once
#include <stddef.h>

/* kind: 'u' upload to the PS5, 'd' download to the phone. Returns a slot or -1. */
int act_begin(char kind, const char *path, long long total);
void act_add(int slot, long long bytes);
void act_end(int slot);
/* A whole file arrived (PUT finished, or a split upload was renamed into place). */
void act_file_done(void);

/* JSON for GET /status. */
size_t act_status_json(char *out, size_t cap);
int pin_is_set(void);

/* Starts the web page server thread on WEB_PORT. */
#define WEB_PORT 9116
void web_start(void);

/* home_app.c: the PlayBeam tile on the PS5 home screen */
int home_app_install(void);
void home_app_open_page(void);
void home_app_start(void);
