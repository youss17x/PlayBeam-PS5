# PlayBeam for the PS5 home screen

A tiny native PS5 title (`PLBM00001`) that opens the PlayBeam Server page
(`http://127.0.0.1:9116`) in the PS5 web browser. ShadowMountPlus installs it from
`/data/homebrew/PLBM00001`; the Android app uploads it there from Settings → PlayBeam Server.

Build: `PS5_PAYLOAD_SDK=... ZLIB_SRC=... ./build.sh` → `out/playbeam-ps5-app.zip`.

`ps5-native-tool/` and `runtime/libc.prx` come from BlackBearReloaded's
ps5-native-app-boilerplate (GPL-3.0-or-later), see `ps5-native-tool/README.md`.
