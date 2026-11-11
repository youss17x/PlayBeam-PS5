/* PlayBeam Server web page (port 9116): a QR code that connects the PlayBeam app, and live
   transfer progress. Open http://127.0.0.1:9116 in a browser on the PS5, or
   http://<PS5 IP>:9116 from any device on the network. */

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "activity.h"
#include "qrcodegen.h"

/* The PS5's address on the local network (not 127.0.0.1), or "" if unknown. */
static void lan_ip(char *out, size_t cap) {
  /* the address a route to the internet would use (no packet is sent) */
  out[0] = 0;
  int s = socket(AF_INET, SOCK_DGRAM, 0);
  if (s < 0) return;
  struct sockaddr_in dst = {0};
  dst.sin_family = AF_INET;
  dst.sin_port = htons(53);
  inet_pton(AF_INET, "8.8.8.8", &dst.sin_addr);
  if (connect(s, (struct sockaddr *)&dst, sizeof dst) == 0) {
    struct sockaddr_in me;
    socklen_t len = sizeof me;
    if (getsockname(s, (struct sockaddr *)&me, &len) == 0 && me.sin_addr.s_addr != 0)
      inet_ntop(AF_INET, &me.sin_addr, out, (socklen_t)cap);
  }
  close(s);
}

static int write_all_fd(int fd, const char *p, size_t n) {
  while (n > 0) {
    ssize_t w = send(fd, p, n, 0);
    if (w <= 0) { if (w < 0 && errno == EINTR) continue; return -1; }
    p += w;
    n -= (size_t)w;
  }
  return 0;
}

static void respond(int fd, const char *status, const char *type, const char *body, size_t len) {
  char head[256];
  int n = snprintf(head, sizeof head,
                   "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %zu\r\n"
                   "Cache-Control: no-store\r\nConnection: close\r\n\r\n",
                   status, type, len);
  if (write_all_fd(fd, head, (size_t)n) == 0 && len) write_all_fd(fd, body, len);
}

/* An SVG QR code for playbeam://connect?host=<ip> (opens the app with this PS5 selected). */
static size_t qr_svg(char *out, size_t cap, const char *ip) {
  char text[96];
  snprintf(text, sizeof text, "playbeam://connect?host=%s", ip);
  uint8_t qr[qrcodegen_BUFFER_LEN_FOR_VERSION(6)], tmp[qrcodegen_BUFFER_LEN_FOR_VERSION(6)];
  if (!qrcodegen_encodeText(text, tmp, qr, qrcodegen_Ecc_MEDIUM, 1, 6, qrcodegen_Mask_AUTO, true))
    return 0;
  int size = qrcodegen_getSize(qr), border = 3, dim = size + 2 * border;
  size_t n = (size_t)snprintf(out, cap,
      "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" shape-rendering=\"crispEdges\">"
      "<rect width=\"100%%\" height=\"100%%\" fill=\"#fff\"/><path fill=\"#0b1e3f\" d=\"", dim, dim);
  for (int y = 0; y < size && n < cap; y++)
    for (int x = 0; x < size && n < cap; x++)
      if (qrcodegen_getModule(qr, x, y))
        n += (size_t)snprintf(out + n, cap - n, "M%d,%dh1v1h-1z", x + border, y + border);
  if (n < cap) n += (size_t)snprintf(out + n, cap - n, "\"/></svg>");
  return n < cap ? n : 0;
}

static const char PAGE[] =
"<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
"<title>PlayBeam Server</title><style>"
"*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
"background:#0b1220;color:#e8ecf4;min-height:100vh;display:flex;align-items:center;justify-content:center}"
".wrap{display:flex;gap:56px;align-items:center;padding:40px;flex-wrap:wrap;justify-content:center;max-width:1300px;width:100%}"
".qr{background:#fff;border-radius:28px;padding:18px;width:340px;height:340px}.qr img{width:100%;height:100%}"
".side{flex:1;min-width:360px}h1{font-size:44px;margin:0 0 6px;letter-spacing:-.02em}"
".tag{color:#4fc3f7;font-weight:700;letter-spacing:.3em;font-size:14px}"
".ip{font-size:30px;font-weight:700;margin:22px 0 4px}.muted{color:#8a93a6;font-size:18px}"
".card{background:#141d31;border-radius:22px;padding:24px 28px;margin-top:26px}"
".file{font-size:22px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
".bar{height:14px;background:#24304a;border-radius:7px;overflow:hidden;margin:14px 0 10px}"
".bar i{display:block;height:100%;background:linear-gradient(90deg,#2f5bea,#4fc3f7);width:0;transition:width .8s}"
".row{display:flex;justify-content:space-between;color:#aab3c5;font-size:18px}"
".stats{display:flex;gap:34px;margin-top:22px}.stats b{display:block;font-size:26px;color:#fff}"
".pin{display:inline-block;margin-left:10px;font-size:14px;background:#1e3a2a;color:#7ee2a8;padding:4px 10px;border-radius:20px;vertical-align:middle}"
"</style></head><body><div class=\"wrap\"><div class=\"qr\"><img src=\"/qr.svg\" alt=\"QR\"></div><div class=\"side\">"
"<div class=\"tag\">PLAYBEAM SERVER</div><h1>Ready <span id=\"pin\"></span></h1>"
"<div class=\"muted\">Scan with your phone's camera to connect the PlayBeam app.</div>"
"<div class=\"ip\" id=\"ip\"></div><div class=\"muted\" id=\"ver\"></div>"
"<div class=\"card\" id=\"now\"><div class=\"muted\">Waiting for files from your phone…</div></div>"
"<div class=\"stats\"><div><span class=\"muted\">Received</span><b id=\"files\">0 files</b></div>"
"<div><span class=\"muted\">Data</span><b id=\"bytes\">0 B</b></div>"
"<div><span class=\"muted\">Speed</span><b id=\"speed\">–</b></div></div></div></div>"
"<script>"
"function fmt(b){var u=['B','KB','MB','GB','TB'],i=0;while(b>=1024&&i<4){b/=1024;i++}return(i?b.toFixed(1):b)+' '+u[i]}"
"function esc(s){return s.replace(/[&<>\"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}"
"var last=null,lastT=0,speed=0;"
"function tick(){var x=new XMLHttpRequest();x.open('GET','/status?'+Date.now());x.onload=function(){try{var s=JSON.parse(x.responseText)}catch(e){return}"
"document.getElementById('ip').textContent=s.ip?s.ip+' : 9113':'';"
"document.getElementById('ver').textContent='Version '+s.version;"
"document.getElementById('pin').innerHTML=s.pin?'<span class=\"pin\">PIN on</span>':'';"
"document.getElementById('files').textContent=s.files_in+(s.files_in==1?' file':' files');"
"document.getElementById('bytes').textContent=fmt(s.bytes_in);"
"var now=Date.now();if(last!==null){var dt=(now-lastT)/1000;if(dt>0)speed=speed*0.5+0.5*Math.max(0,(s.bytes_in+s.bytes_out-last)/dt)}last=s.bytes_in+s.bytes_out;lastT=now;"
"var g={},order=[];s.active.forEach(function(a){var k=a.k+a.name;if(!g[k]){g[k]={k:a.k,name:a.name,done:0,total:0};order.push(k)}g[k].done+=a.done;g[k].total+=a.total});"
"document.getElementById('speed').textContent=order.length?fmt(speed)+'/s':'–';"
"var h='';order.forEach(function(k){var a=g[k],p=a.total?Math.min(100,a.done*100/a.total):0;"
"h+='<div class=\"file\">'+(a.k=='d'?'⬇ ':'⬆ ')+esc(a.name)+'</div><div class=\"bar\"><i style=\"width:'+p.toFixed(1)+'%\"></i></div>'+"
"'<div class=\"row\"><span>'+fmt(a.done)+' of '+fmt(a.total)+'</span><span>'+Math.floor(p)+'%</span></div>'});"
"document.getElementById('now').innerHTML=h||'<div class=\"muted\">Waiting for files from your phone…</div>'};x.send()}"
"tick();setInterval(tick,1000);"
"</script></body></html>";

static void handle(int fd) {
  char req[2048];
  size_t got = 0;
  while (got < sizeof req - 1) {
    ssize_t n = recv(fd, req + got, sizeof req - 1 - got, 0);
    if (n <= 0) break;
    got += (size_t)n;
    req[got] = 0;
    if (strstr(req, "\r\n\r\n")) break;
  }
  req[got] = 0;
  char method[8] = "", path[256] = "";
  if (sscanf(req, "%7s %255s", method, path) != 2) return;
  char *q = strchr(path, '?');
  if (q) *q = 0;
  if (strcmp(method, "GET") != 0) {
    respond(fd, "405 Method Not Allowed", "text/plain", "GET only", 8);
  } else if (strcmp(path, "/") == 0 || strcmp(path, "/index.html") == 0) {
    respond(fd, "200 OK", "text/html; charset=utf-8", PAGE, sizeof PAGE - 1);
  } else if (strcmp(path, "/status") == 0) {
    char *buf = malloc(16384);
    if (!buf) return;
    size_t n = act_status_json(buf, 16384);
    respond(fd, "200 OK", "application/json", buf, n);
    free(buf);
  } else if (strcmp(path, "/qr.svg") == 0) {
    char ip[64];
    lan_ip(ip, sizeof ip);
    char *buf = malloc(65536);
    if (!buf) return;
    size_t n = ip[0] ? qr_svg(buf, 65536, ip) : 0;
    if (n) respond(fd, "200 OK", "image/svg+xml", buf, n);
    else respond(fd, "503 Service Unavailable", "text/plain", "no network", 10);
    free(buf);
  } else {
    respond(fd, "404 Not Found", "text/plain", "not found", 9);
  }
}

static void *web_thread(void *arg) {
  (void)arg;
  int ls = socket(AF_INET, SOCK_STREAM, 0);
  if (ls < 0) return NULL;
  int one = 1;
  setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
  struct sockaddr_in a = {0};
  a.sin_family = AF_INET;
  a.sin_port = htons(WEB_PORT);
  a.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(ls, (struct sockaddr *)&a, sizeof a) != 0 || listen(ls, 8) != 0) { close(ls); return NULL; }
  for (;;) {
    int fd = accept(ls, NULL, NULL);
    if (fd < 0) { if (errno == EINTR) continue; break; }
    struct timeval tv = {3, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    handle(fd);
    close(fd);
  }
  close(ls);
  return NULL;
}

/* lan_ip for the PS5 notification */
void web_lan_ip(char *out, size_t cap) { lan_ip(out, cap); }

void web_start(void) {
  pthread_t th;
  if (pthread_create(&th, NULL, web_thread, NULL) == 0) pthread_detach(th);
}
