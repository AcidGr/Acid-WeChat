#!/bin/bash
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
MODE="${1:-release}"
if [ "$MODE" = "debug" ]; then
  /opt/gradle/gradle-8.10.2/bin/gradle assembleDebug --no-daemon --console=plain
  APK="app/build/outputs/apk/debug/app-debug.apk"
else
  /opt/gradle/gradle-8.10.2/bin/gradle assembleRelease --no-daemon --console=plain
  APK="app/build/outputs/apk/release/app-release.apk"
fi
cp "$APK" /storage/emulated/0/Download/Acid-WeChat.apk
ls -lh "$APK" /storage/emulated/0/Download/Acid-WeChat.apk
