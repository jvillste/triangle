#!/bin/sh
# Compile everything, run the struct layout checks, then the headless
# WebGPU smoke test (instance, adapter, device, shader module, render
# pipeline) and finally the window test. Needs no display or GPU: on
# headless Linux the window test opens its own Xvfb screen and draws
# through llvmpipe software rendering.
set -e
cd "$(dirname "$0")"

# Pin the toolchain to JDK 25 (the FFM boundary needs Java 22+, so
# older JDKs are not even tried). On Linux that is the apt
# openjdk-25-jdk package - the FULL build; the "-headless" variant
# has no X11 AWT toolkit, so java.awt.Robot could not screenshot
# the window test. On macOS java_home is asked to find JDK 25.
# An exported JAVA_HOME wins.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/lib/jvm/java-25-openjdk-arm64/bin/javac ]; then
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
  elif [ "$(uname)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 25 || true)
  fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "== lein javac + lein test (struct layout checks) =="
lein javac
lein test

echo
echo "== headless smoke test (full FFM chain against libwgpu_native) =="
lein run -- --smoke

echo
echo "== window test (real window; under Xvfb when headless) =="
if [ -n "$DISPLAY" ]; then
  lein run -- --window-test
elif [ "$(uname)" = Darwin ]; then
  echo "macOS: ./run.sh opens the window (the screenshot test needs X11)"
elif command -v Xvfb >/dev/null 2>&1; then
  Xvfb -ac :99 -screen 0 800x600x24 >/dev/null 2>&1 &
  XVPID=$!
  sleep 2
  STATUS=0
  DISPLAY=:99 lein run -- --window-test || STATUS=$?
  kill $XVPID 2>/dev/null || true
  if [ $STATUS -ne 0 ]; then
    exit $STATUS
  fi
else
  echo "no DISPLAY and no Xvfb binary: skipping the window test"
fi

echo
echo "All checks passed."
