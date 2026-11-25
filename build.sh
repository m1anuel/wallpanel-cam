#!/bin/bash
set -euo pipefail
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/manuel/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$(dirname "$0")"
if [ -x /Users/manuel/Android/gradle-8.9/bin/gradle ]; then
  /Users/manuel/Android/gradle-8.9/bin/gradle assembleDebug
else
  ./gradlew assembleDebug
fi
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
