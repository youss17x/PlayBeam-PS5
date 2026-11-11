#!/usr/bin/env bash
# Builds the PlayBeam app for the PS5 home screen: a native title (PLBM00001) that opens the
# PlayBeam Server page in the PS5 web browser. ShadowMountPlus installs it from /data/homebrew.
#
# Needs: PS5_PAYLOAD_SDK (built SDK), clang-18 / clang++-18 / ld.lld-18, and ZLIB_SRC (zlib source).
# Output: out/PLBM00001/ and out/playbeam-ps5-app.zip
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${PS5_PAYLOAD_SDK:?set PS5_PAYLOAD_SDK}"
zlib="${ZLIB_SRC:?set ZLIB_SRC to a zlib source folder}"
cc="${CC18:-clang-18}"; cxx="${CXX18:-clang++-18}"; lld="${LLD18:-ld.lld-18}"
native="$here/ps5-native-tool/tooling/native"
out="$here/out"; work="$out/work"
rm -rf "$out"; mkdir -p "$work/host" "$work/obj"

# host tool: converts and signs the native ELF
for n in adler32 crc32 deflate inflate infback inffast inftrees trees zutil compress uncompr; do
  "$cc" -O2 -I"$zlib" -c "$zlib/$n.c" -o "$work/host/$n.o"
done
for n in native_app_builder self_container elf_object sce_module_writer; do
  "$cxx" -std=c++20 -O2 -I"$zlib" -c "$native/$n.cpp" -o "$work/host/$n.o"
done
"$cxx" -o "$work/ps5-native-tool" "$work"/host/*.o

# the app (C headers only: the payload SDK has no libc++ headers)
sed 's/#include <cstddef>/#include <stddef.h>/; s/#include <cstdint>/#include <stdint.h>/; s/std::uint8_t/uint8_t/g; s/std::uint64_t/uint64_t/g' \
  "$native/app_crt.cpp" > "$work/app_crt.cpp"
flags=(--no-default-config -target x86_64-sie-ps5 -std=c++17 -O2 -fno-stack-protector -fno-plt -femulated-tls
       -fvisibility-nodllstorageclass=default -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti
       -isysroot "$sdk" -nostdinc++ -isystem "$sdk/target/include")
"$cc" "${flags[@]}" -c "$work/app_crt.cpp" -o "$work/obj/app_crt.o"
"$cc" "${flags[@]}" -c "$here/src/main.cpp" -o "$work/obj/main.o"
"$lld" -m elf_x86_64 -pie -T "$native/ps5-pie.ld" --eh-frame-hdr -z max-page-size=0x4000 --hash-style=gnu \
  --gc-sections --version-script "$native/app-symbols.map" -e _start -o "$work/pie.elf" "$work"/obj/*.o \
  -L"$sdk/target/lib" --as-needed "$sdk"/target/lib/*.so
"$work/ps5-native-tool" link --in "$work/pie.elf" --out "$work/eboot.elf" --stub-dir "$sdk/target/lib" \
  --module-sdk 0x02000009 --companion-sdk 0x08050001 --file-name eboot.elf

title="$out/PLBM00001"
mkdir -p "$title/sce_sys" "$title/sce_module"
"$work/ps5-native-tool" self --sign --in "$work/eboot.elf" --out "$title/eboot.bin" --magic 0x1D3D154F
cp "$here/sce_sys/param.json" "$here/sce_sys/icon0.png" "$title/sce_sys/"
cp "$here/runtime/libc.prx" "$title/sce_module/libc.prx"
(cd "$out" && rm -f playbeam-ps5-app.zip && zip -qr playbeam-ps5-app.zip PLBM00001)
echo "Built $out/playbeam-ps5-app.zip"
