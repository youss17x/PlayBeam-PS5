# PlayBeam Server (PS5 payload)

A small PS5 payload that receives files from the PlayBeam Android app.

- Listens on TCP port **9113**
- Several connections at once (one thread each) for fast uploads
- Resume support (continue a file from an offset)
- Extracts `.zip` files directly on the PS5 (ZIP64 supported, via [miniz](https://github.com/richgel999/miniz), MIT — see `miniz-LICENSE`)
- Lists, downloads, renames and deletes files (used by the app's PS5 file browser)
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
| `UNZIP <zip>⇥<dest>` | `PROG <done> <total>` lines, then `OK <files>` / `ERR` |
| `RM <path>` | `OK` / `ERR` |
| `RMTREE <path>` | `OK` / `ERR` (folder and its content) |
| `RENAME <from>⇥<to>` | `OK` / `ERR` |
| `LIST <dir>` | `D <size> <name>` / `F <size> <name>` lines, then `OK <count>` |
| `GET <path>` | `OK <size>` then the bytes / `ERR` |
| `QUIT` | closes the connection |
| `SHUTDOWN` | `OK`, then the server exits |
