# PlayBeam

An Android app to send files and folders from your phone to your PS5 over Wi-Fi (FTP).

## Features (v0.3)
- Pick a whole folder (including all subfolders) or multiple files
- **Share → PlayBeam** from any app (gallery, file manager, browser downloads…)
- Background upload (foreground service) with a progress notification and a Stop button
- Keeps uploading when the screen is off (WifiLock + WakeLock)
- **Resume**: files already on the PS5 are skipped, unfinished files continue where they stopped,
  and dropped connections are retried automatically
- **Find my PS5**: scans the local network for the PS5 FTP server — no need to type the IP
- **PS5 file browser**: browse folders, create, rename and delete, and pick the upload destination
- Quick destination presets: `/data/homebrew`, `/data/etaHEN/games`, `/mnt/usb0`
- Remembers your PS5 IP, port and destination path

## Requirements
- A PS5 running an FTP server (e.g. etaHEN's FTP server or an FTP payload)
- Phone and PS5 on the same local network

## Download
Grab the latest APK from the **Actions** tab → latest **Build APK** run → **Artifacts** → `PlayBeam-debug`.

## Building
The APK is built with GitHub Actions: **Actions** tab → **Build APK** → **Run workflow**.
To build locally, open the project in Android Studio and press **Run**.

## Code structure
| File | Purpose |
|---|---|
| `MainActivity.kt` | Main screen + receiving shared files (Jetpack Compose) |
| `BrowserScreen.kt` | PS5 file browser screen |
| `FtpSession.kt` | FTP commands used by the browser (list, create, rename, delete) |
| `PS5Discovery.kt` | Finds the PS5 on the local network |
| `FileScanner.kt` | Lists the selected files and folders |
| `FtpUploader.kt` | Connects to the PS5 and uploads (with resume and retries) |
| `UploadService.kt` | Runs the upload in the background + notification |
| `UploadState.kt` | Shared upload progress |

## Roadmap
- [x] Auto-discover the PS5 on the local network
- [x] Resume interrupted uploads
- [x] PS5 file browser
- [x] Send files from other apps (Android share menu)
- [ ] Dedicated PS5 payload for faster transfers
