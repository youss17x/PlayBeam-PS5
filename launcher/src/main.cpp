// PlayBeam launcher: a native PS5 title that opens the PlayBeam Server page in the
// PS5 web browser. It stays idle in the background afterwards: a title that quits on its
// own right away is reported by the PS5 as "Something went wrong with this game or app".
extern "C" {
int sceUserServiceInitialize(void *);
int sceSystemServiceLaunchWebBrowser(const char *uri, void *param);
int sceSystemServiceHideSplashScreen(void);
int sceKernelUsleep(unsigned int microseconds);
}

int main() {
  int prio = 256;
  sceUserServiceInitialize(&prio);
  sceSystemServiceHideSplashScreen();
  sceSystemServiceLaunchWebBrowser("http://127.0.0.1:9116/", nullptr);
  for (;;) sceKernelUsleep(60u * 1000u * 1000u);
}
