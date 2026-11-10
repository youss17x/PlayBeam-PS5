# PlayBeam PS5

An Android app to send files and folders from your phone to your PS5 over Wi-Fi (FTP).

## Features (v0.8)

Five tabs: **Upload · Files · Payload · History · Settings**.

Languages: **English**, **Français**, **العربية** (right-to-left) — Settings → Language.
Themes: **System**, **Light**, **Dark** — Settings → Appearance.
Updates: the app checks GitHub Releases and offers to install new versions.

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

**PlayBeam Server** (PS5 payload, built into the app)
- Start it from the app with one tap (**Payload → Start on PS5**)
- Faster uploads than FTP; the app uses it automatically when it's running
- **Extracts ZIP files on the PS5** after uploading (and deletes the .zip)
- Powers the PS5 file browser and downloads too — no FTP server needed
- Source and protocol: [`payload/`](payload/)

**PS5**
- **Find my PS5**: scans the local network for the PS5 FTP server — no need to type the IP
- **Saved consoles**: keep several PS5s by name and switch with one tap
- **Files** (PS5 file manager): switch between PS5 and USB drives (with free space), copy, cut & paste, move to another drive, rename, delete, details; the `homebrew` folder is shown in green
- **Download to phone**: copy files or whole folders from the PS5 to your phone
- **Payload sender**: send `.elf` / `.bin` payloads to the PS5 loader (e.g. to start the FTP server),
  with a list of recent payloads for one-tap re-sending
- **PS5 info** in Settings: model, firmware, live CPU / SoC temperatures, uptime and free space on every drive
- Quick destination presets: `/data/homebrew`, `/data/etaHEN/games`, `/mnt/usb0`

## Requirements
- A jailbroken PS5 with an ELF loader (for PlayBeam Server) and/or an FTP server (e.g. etaHEN's)
- Phone and PS5 on the same local network
- Android 8.0 or newer

## Download
Get the latest APK from the [**Releases**](../../releases/latest) page. Once installed, PlayBeam tells you when a new version is out and updates itself.

## Building
- Test builds: **Actions** → **Build APK** → **Run workflow** (APK in the run's Artifacts).
- New version: **Actions** → **Release** → **Run workflow**, type the version (e.g. `1.0.0`) and what's new.
  It publishes a GitHub release with the APK and the PS5 payload; the app picks it up as an update.
- Every build is signed with the project key from the repository secrets
  `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD` and `SIGNING_KEY_ALIAS`.
To build locally, open the project in Android Studio and press **Run**.

## Code structure
| File | Purpose |
|---|---|
| `MainActivity.kt` | App state, bottom navigation (5 tabs), Files tab, receiving shared files |
| `UploadScreen.kt` | Upload tab + destination sheet |
| `SettingsScreen.kt` | Settings tab (PS5, transfers, downloads, about) |
| `ConsoleEditor.kt` | Choosing the PS5 (saved consoles, Find my PS5, IP) |
| `Updater.kt`, `UpdateDialog.kt` | Update check on GitHub Releases, download and install |
| `Ps5InfoCard.kt` | PS5 model, firmware, live sensors and storage |
| `Splash.kt` | Opening animation “PlayBeam — Has No Limits” |
| `Strings.kt` | All texts in English, French and Arabic |
| `Ui.kt` | Pearl theme (colors, type) and shared UI building blocks |
| `BrowserScreen.kt` | PS5 file browser (incl. download to phone) |
| `PayloadScreen.kt` | Payload sender |
| `HistoryScreen.kt` | Upload history |
| `UploadService.kt` | Background upload queue + notifications |
| `FtpUploader.kt` | Parallel FTP upload with resume and retries |
| `FtpSession.kt` | FTP commands used by the browser (list, create, rename, delete, download) |
| `PS5Discovery.kt` | Finds the PS5 on the local network |
| `ServerClient.kt` | PlayBeam Server client and uploader |
| `ServerSession.kt` | PS5 file browser over PlayBeam Server |
| `payload/` | PlayBeam Server PS5 payload (C, PS5 Payload SDK) |
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
- [x] PlayBeam Server payload: faster transfers and ZIP extraction on the PS5
