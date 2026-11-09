# PlayBeam

An Android app to send files and folders from your phone to your PS5 over Wi-Fi (FTP).

## Features (v0.5)

Clean, bright **Pearl** design: settings-style cards, one-tap console and destination sheets.

**Upload**
- Pick a whole folder (including all subfolders) or multiple files
- **Share → PlayBeam** from any app (gallery, file manager, browser downloads…)
- **Parallel uploads**: 1–4 connections at once — much faster for folders with many small files
- **Upload queue**: add more uploads while one is running; remove or stop them any time
- **Resume**: files already on the PS5 are skipped, unfinished files continue where they stopped,
  and dropped connections are retried automatically
- Runs in the background with a progress notification, and keeps going when the screen is off
- Sound / vibration notification when an upload finishes
- **History** of past uploads (size, files, duration, errors)

**PS5**
- **Find my PS5**: scans the local network for the PS5 FTP server — no need to type the IP
- **Saved consoles**: keep several PS5s by name and switch with one tap
- **PS5 file browser**: browse, create folders, rename, delete, pick the upload destination
- **Download to phone**: copy files or whole folders from the PS5 to your phone
- **Payload sender**: send `.elf` / `.bin` payloads to the PS5 loader (e.g. to start the FTP server),
  with a list of recent payloads for one-tap re-sending
- Quick destination presets: `/data/homebrew`, `/data/etaHEN/games`, `/mnt/usb0`

## Requirements
- A jailbroken PS5 running an FTP server (e.g. etaHEN's FTP server or an FTP payload)
- Phone and PS5 on the same local network
- Android 8.0 or newer

## Download
Grab the latest APK from the **Actions** tab → latest **Build APK** run → **Artifacts** → `PlayBeam-debug`.

## Building
The APK is built with GitHub Actions: **Actions** tab → **Build APK** → **Run workflow**.
To build locally, open the project in Android Studio and press **Run**.

## Code structure
| File | Purpose |
|---|---|
| `MainActivity.kt` | App navigation, Upload screen, console and destination sheets, receiving shared files |
| `Ui.kt` | Pearl theme (colors, type) and shared UI building blocks |
| `BrowserScreen.kt` | PS5 file browser (incl. download to phone) |
| `PayloadScreen.kt` | Payload sender |
| `HistoryScreen.kt` | Upload history |
| `UploadService.kt` | Background upload queue + notifications |
| `FtpUploader.kt` | Parallel FTP upload with resume and retries |
| `FtpSession.kt` | FTP commands used by the browser (list, create, rename, delete, download) |
| `PS5Discovery.kt` | Finds the PS5 on the local network |
| `FileScanner.kt` | Lists the selected files and folders |
| `Store.kt` | Saved consoles, history and recent payloads |
| `UploadState.kt` | Shared upload progress and queue |

## Roadmap
- [x] Auto-discover the PS5 on the local network
- [x] Resume interrupted uploads
- [x] PS5 file browser + download to phone
- [x] Send files from other apps (Android share menu)
- [x] Parallel uploads, upload queue, history
- [x] Payload sender
- [ ] Dedicated PS5 payload: faster transfers and extracting zip / rar directly on the PS5
