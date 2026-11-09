# PlayBeam

An Android app to send files and folders from your phone to your PS5 over Wi-Fi (FTP).

## Features (v0.1)
- Pick a whole folder (including all subfolders) or multiple files
- Background upload (foreground service) with a progress notification and a Stop button
- Keeps uploading when the screen is off (WifiLock + WakeLock)
- Quick destination presets: `/data/homebrew`, `/data/etaHEN/games`, `/mnt/usb0`
- Remembers your PS5 IP, port and destination path

## Requirements
- A PS5 running an FTP server (e.g. etaHEN's FTP server or an FTP payload)
- Phone and PS5 on the same local network

## Download
Grab the latest APK from the **Actions** tab → latest **Build APK** run → **Artifacts** → `PlayBeam-debug`.

## Building
Every push to `main` builds the APK automatically with GitHub Actions.
To build locally, open the project in Android Studio and press **Run**.

## Code structure
| File | Purpose |
|---|---|
| `MainActivity.kt` | User interface (Jetpack Compose) |
| `FileScanner.kt` | Lists the selected files and folders |
| `FtpUploader.kt` | Connects to the PS5 and uploads |
| `UploadService.kt` | Runs the upload in the background + notification |
| `UploadState.kt` | Shared upload progress |

## Roadmap
- [ ] Auto-discover the PS5 on the local network
- [ ] Resume interrupted uploads (FTP REST)
- [ ] PS5 file browser
- [ ] Send files from other apps (Android share menu)
- [ ] Dedicated PS5 payload for faster transfers
