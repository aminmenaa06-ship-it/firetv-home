#!/bin/zsh
# Build the release APK and install it on the Fire TV.   Usage: ./deploy.sh <fire-tv-ip>
set -euo pipefail
cd "$(dirname "$0")"
DEV=${1:?usage: ./deploy.sh <fire-tv-ip>}
[[ $DEV == *:* ]] || DEV=$DEV:5555
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}

adb connect $DEV
echo "If the TV shows 'Allow USB debugging?', choose Always allow."
adb -s $DEV wait-for-device
./gradlew assembleRelease -q
adb -s $DEV install -r app/build/outputs/apk/release/app-release.apk
adb -s $DEV shell am start -n com.amin.tvhome/.MainActivity
