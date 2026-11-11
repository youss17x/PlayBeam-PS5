# PlayBeam Server (PS5 payload)

A small PS5 payload that receives files from the PlayBeam Android app.

- Listens on TCP port **9113**
- Several connections at once (one thread each) for fast uploads
- Resume support (continue a file from an offset)
- Extracts `.zip` files directly on the PS5 (ZIP64 supported, via [miniz](https://github.com/richgel999/miniz), MIT — see `miniz-LICENSE`)
- Lists, downloads, copies, moves, renames and deletes files, shows sizes and free space (used by the app's PS5 file browser)
- Reports the PS5 model, firmware, temperatures, CPU frequency and uptime
- Shows PS5 notifications when it starts and when a ZIP is extracted

## Start it
- From the app: **Payload → PlayBeam Server → Start on PS5** (the ELF is built into the app), or
- Send `playbeam-server.elf` to your ELF loader (usually port 9021).

Running it twice is safe: the second copy sees the port in use and exits.

## Build
```sh
export PS5_PAYLOAD_SDK=/opt/ps5-payload-sdk   # https://github.com/ps5-payload-dev/sdk
make
```

## Protocol
One command per line (`\n`), paths are absolute and take the rest of the line:

| Command | Reply |
|---|---|
| `HELLO` | `OK PlayBeam-Server <version>` |
| `MKDIR <path>` | `OK` / `ERR <reason>` |
| `SIZE <path>` | `OK <bytes>` (`OK -1` if missing) |
| `PUT <offset> <length> <path>` | `READY`, then send the bytes, then `OK` / `ERR` |
| `AUTH <pin>` | `OK` / `ERR wrong PIN` — needed first when `HELLO` ends with `PIN` (5 wrong tries close the connection) |
| `SETPIN <pin>` | Sets a 4–8 digit PIN, or removes it when empty (saved in `/data/playbeam/pin`) |
| `CREATE <size> <path>` | Empty file for a split upload: `OK SPARSE <fs>` (pieces may be sent in parallel) or `OK <fs>` (USB exFAT/FAT) |
| `PUTR <offset> <length> <path>` | Like `PUT` but never truncates (one piece of a split upload) |
| `UNZIP <zip>⇥<dest>` | `PROG <done> <total>` lines, then `OK <files>` / `ERR` |
| `RM <path>` | `OK` / `ERR` |
| `RMTREE <path>` | `OK` / `ERR` (folder and its content) |
| `RENAME <from>⇥<to>` | `OK` / `ERR` |
| `LIST <dir>` | `D <size> <name>` / `F <size> <name>` lines, then `OK <count>` |
| `GET <path>` | `OK <size>` then the bytes / `ERR` |
| `STAT <path>` | `OK F <size> <mtime>` / `OK D <total> <mtime> <files> <folders>` |
| `DF <path>` | `OK <total bytes> <free bytes>` |
| `INFO` | `key=value` lines (model, fw, uptime, cpu_temp, soc_temp, cpu_mhz, version), then `OK` |
| `COPY <from>⇥<to>` | `PROG` lines, then `OK` / `ERR` (file or folder, on the PS5 itself) |
| `MOVE <from>⇥<to>` | rename, or copy + delete across drives; `PROG` lines, then `OK` / `ERR` |
| `QUIT` | closes the connection |
| `SHUTDOWN` | `OK`, then the server exits |

## Web page (port 9116)

PlayBeam Server also serves a small web page: open `http://127.0.0.1:9116` in a browser on the PS5
(or `http://<PS5 IP>:9116` from any device). It shows a QR code that opens the PlayBeam app with this
PS5 selected (`playbeam://connect?host=<ip>`), and live progress of the files being sent.
`GET /status` returns the same data as JSON.

`playbeam-tile.elf` (in `tile/`) is a separate one-shot payload that adds a **PlayBeam** tile to the
PS5 home screen (title ID `PLBM00001`, in `/user/app/PLBM00001`) that opens this page in the PS5
browser. The app sends it from Settings → PlayBeam Server.

The QR code is made with [QR Code generator](https://www.nayuki.io/page/qr-code-generator-library)
by Project Nayuki (MIT License, see the header of `qrcodegen.c`).
