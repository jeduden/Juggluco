#!/usr/bin/env bash
# Build the main Juggluco variant (mobile + Libre3 + Sibionics + Dexcom + Google)
# from the command line, outside Android Studio's flatpak sandbox.
#
# Usage:
#   ./build-main.sh                 # default: assembleMobileLibre3SiDexGoogleDebug
#   ./build-main.sh release         # assembleMobileLibre3SiDexGoogleRelease
#   ./build-main.sh <gradle-args>   # any explicit gradle task(s)/flags
set -euo pipefail
cd "$(dirname "$0")"

# JDK 21 bundled with the flatpak Android Studio (works fine outside the sandbox).
export JAVA_HOME=/home/jeduden/.local/share/flatpak/app/com.google.AndroidStudio/x86_64/stable/active/files/extra/jbr

# Host-built rtlpp code-gen tool, required at CMake configure time when cross-compiling.
export PATH="$(pwd)/build/hosttools:$PATH"

case "${1:-debug}" in
  debug)   TASK=":Common:assembleMobileLibre3SiDexGoogleDebug" ;;
  release) TASK=":Common:assembleMobileLibre3SiDexGoogleRelease" ;;
  *)       TASK="$*" ;;
esac

echo ">> ./gradlew $TASK"
exec ./gradlew $TASK
