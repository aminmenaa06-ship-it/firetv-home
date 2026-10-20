#!/bin/zsh
# Make this launcher the Home app and disable the packages in bloat-packages.txt.
# Undo with ./restore-stock.sh <ip>.          Usage: ./debloat.sh <fire-tv-ip>
set -uo pipefail
cd "$(dirname "$0")"
DEV=${1:?usage: ./debloat.sh <fire-tv-ip>}
[[ $DEV == *:* ]] || DEV=$DEV:5555
a() { adb -s $DEV "$@"; }

# Never remove the stock Home without a working replacement installed.
a shell pm path com.amin.tvhome | grep -q package: || { echo "com.amin.tvhome not installed; run ./deploy.sh first"; exit 1; }

for p in ${(f)"$(grep -v '^#' bloat-packages.txt)"}; do
  if a shell pm path "$p" | grep -q package:; then
    echo -n "$p: "; a shell pm disable-user --user 0 "$p"
  else
    echo "$p: not on this device, skipped"
  fi
done
a shell cmd package set-home-activity com.amin.tvhome/.MainActivity
