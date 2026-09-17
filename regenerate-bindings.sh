#!/bin/sh
# Regenerate the wgpu-native Java bindings under java/wgpu/ with
# jextract from the C headers of the pinned wgpu-native release
# (v22.1.0.5): webgpu.h plus the wgpu-native extensions in wgpu.h.
# The generated tree is committed to git; run this only when the
# header revision changes, and diff the result before committing.
#
#   WGPU_NATIVE_DIR     a wgpu-native checkout whose ffi/ directory
#                       matches the pinned release. Without the
#                       variable, a sibling "wn22" directory is tried.
#
# jextract is not part of any JDK distribution: it ships as an
# early-access build from jdk.java.net/jextract, which this script
# downloads (build 25-jextract+2-4). A jextract already on PATH wins.
# Generating is pure Java, so any platform may regenerate the tree.
set -e
cd "$(dirname "$0")"

# ── find the wgpu-native headers
if [ -z "${WGPU_NATIVE_DIR:-}" ]; then
  for candidate in ../wn22 wn22; do
    if [ -f "$candidate/ffi/wgpu.h" ]; then
      WGPU_NATIVE_DIR=$candidate
      break
    fi
  done
fi
if [ -z "${WGPU_NATIVE_DIR:-}" ] || [ ! -f "$WGPU_NATIVE_DIR/ffi/wgpu.h" ]; then
  echo "no wgpu-native checkout with ffi/wgpu.h found;" >&2
  echo "set WGPU_NATIVE_DIR to one matching release v22.1.0.5" >&2
  exit 1
fi
echo "headers: $WGPU_NATIVE_DIR/ffi"

# ── get a jextract binary
JEXTRACT=$(command -v jextract || true)
if [ -z "$JEXTRACT" ]; then
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  artifact="openjdk-25-jextract+2-4_linux-x64_bin.tar.gz" ;;
    Linux/aarch64) artifact="openjdk-25-jextract+2-4_linux-aarch64_bin.tar.gz" ;;
    Darwin/arm64)  artifact="openjdk-25-jextract+2-4_macos-aarch64_bin.tar.gz" ;;
    Darwin/x86_64) artifact="openjdk-25-jextract+2-4_macos-x64_bin.tar.gz" ;;
    *)
      echo "no jextract on PATH and no EA build for $(uname -s)/$(uname -m);" >&2
      echo "put a jextract binary on PATH (jdk.java.net/jextract)" >&2
      exit 1 ;;
  esac
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  echo "downloading jextract: $artifact"
  curl -fL --retry 3 -o "$work/jextract.tar.gz" \
    "https://download.java.net/java/early_access/jextract/25/2/$artifact"
  tar xzf "$work/jextract.tar.gz" -C "$work"
  JEXTRACT_DIR=$(find "$work" -type d -name "jextract-*" | head -1)
  JEXTRACT="$JEXTRACT_DIR/bin/jextract"
fi
echo "jextract: $JEXTRACT"

# ── generate
rm -rf java/wgpu
"$JEXTRACT" \
  --include-dir "$WGPU_NATIVE_DIR/ffi" \
  --include-dir "$WGPU_NATIVE_DIR/ffi/webgpu-headers" \
  -l wgpu_native \
  -t wgpu \
  --symbols-class-name Wgpu \
  --output java \
  "$WGPU_NATIVE_DIR/ffi/wgpu.h"

# ── keep the one hand-maintained line in step (see WgpuSymbols.java)
sed -i.bak 's|static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.libraryLookup(System.mapLibraryName("wgpu_native"), LIBRARY_ARENA)|static final SymbolLookup SYMBOL_LOOKUP = triangle.WgpuSymbols.lookup()|' \
  java/wgpu/wgpu_h.java
rm java/wgpu/wgpu_h.java.bak

echo "regenerated java/wgpu ($(ls java/wgpu | wc -l | tr -d ' ') files)"
