#!/bin/sh
# Download the libwgpu-native binaries from the pinned official release
# (wgpu-native v22.1.0.5) into resources/native/<platform>/, so git
# carries no binaries. A file that already matches the expected sha256
# is left alone, so this is safe to run on every build.
#
#   ./fetch-native.sh              this machine's platform
#   ./fetch-native.sh --all        every platform (what CI wants)
#   ./fetch-native.sh <name>       one named platform: linux-aarch64,
#                                  linux-x86_64, macos-aarch64,
#                                  macos-x86_64, windows-x86_64
#
# Needs curl and unzip; both ship with macOS, on Linux they are one
# apt away. Run it on the machine that will USE the binaries: on macOS
# behind a Docker shared folder, a file the container wrote can be
# served with stale pages and get the process killed at dlopen.
set -e
cd "$(dirname "$0")"

TAG=v22.1.0.5
BASE="https://github.com/gfx-rs/wgpu-native/releases/download/$TAG"

hash_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

fetch() {
  dir=$1; asset=$2; member=$3; name=$4; sha=$5
  out="resources/native/$dir/$name"
  if [ -f "$out" ] && [ "$(hash_of "$out")" = "$sha" ]; then
    echo "ok       $out"
    return
  fi
  work=$(mktemp -d)
  echo "download $asset -> $out"
  curl -fL --retry 3 -o "$work/dl.zip" "$BASE/$asset"
  unzip -p "$work/dl.zip" "$member" > "$out"
  got=$(hash_of "$out")
  if [ "$got" != "$sha" ]; then
    echo "sha256 mismatch for $out:" >&2
    echo "  got  $got" >&2
    echo "  want $sha" >&2
    rm -f "$out"
    exit 1
  fi
  rm -rf "$work"
  echo "verified $out"
}

# dir, release asset, member inside the zip, local file name, sha256
fetch_one() {
  case "$1" in
    linux-aarch64)
      fetch linux-aarch64 wgpu-linux-aarch64-release.zip \
            lib/libwgpu_native.so libwgpu_native.so \
            973f8ac62f03681b6cb43234cecaaf85209af87a2e7c74f8fa01f25789c03f8b ;;
    linux-x86_64)
      fetch linux-x86_64 wgpu-linux-x86_64-release.zip \
            lib/libwgpu_native.so libwgpu_native.so \
            f58492b43c7da7e8223600ca6e85b38fdfbe157eec3d009f332b030d6cca4f92 ;;
    macos-aarch64)
      fetch macos-aarch64 wgpu-macos-aarch64-release.zip \
            lib/libwgpu_native.dylib libwgpu_native.dylib \
            cada65a147e8c96f30947cfeab8861961878ec1ff2b26ad83348775f215a64c5 ;;
    macos-x86_64)
      fetch macos-x86_64 wgpu-macos-x86_64-release.zip \
            lib/libwgpu_native.dylib libwgpu_native.dylib \
            b01a7b51cd360181f5184030150a33392d49682d1aa4c66ba23e106054b3efb9 ;;
    windows-x86_64)
      fetch windows-x86_64 wgpu-windows-x86_64-msvc-release.zip \
            lib/wgpu_native.dll wgpu_native.dll \
            5366ae5dce94de7037f82cb5b321ebcbdfb76516b0881764090bcc6b6fbea555 ;;
    *)
      echo "unknown platform: $1" >&2
      exit 1 ;;
  esac
}

platform() {
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)   echo linux-x86_64 ;;
    Linux/aarch64)  echo linux-aarch64 ;;
    Darwin/arm64)   echo macos-aarch64 ;;
    Darwin/x86_64)  echo macos-x86_64 ;;
    *)
      echo "unsupported platform: $(uname -s)/$(uname -m)" >&2
      exit 1 ;;
  esac
}

case "${1:-}" in
  --all)
    fetch_one linux-aarch64
    fetch_one linux-x86_64
    fetch_one macos-aarch64
    fetch_one macos-x86_64
    fetch_one windows-x86_64 ;;
  "")
    fetch_one "$(platform)" ;;
  *)
    fetch_one "$1" ;;
esac
