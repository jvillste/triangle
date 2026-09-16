#!/bin/sh
# Opens the window and draws. Needs a display: X11 or Wayland on
# Linux, a normal user session on macOS.
set -e
cd "$(dirname "$0")"

# Same JDK 25 selection as test.sh (an exported JAVA_HOME wins).
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/lib/jvm/java-25-openjdk-arm64/bin/javac ]; then
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
  elif [ "$(uname)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 25 || true)
  fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

exec lein run "$@"
