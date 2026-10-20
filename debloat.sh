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

# Fire OS 8 protects its launcher ("Cannot disable a protected package"), so Home still resolves to
# Amazon. Fall back to the launcher's accessibility service, which hops from Amazon's home to ours.
HOME_TARGET=$(a shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
if [[ $HOME_TARGET != com.amin.tvhome/* ]]; then
  SVC=com.amin.tvhome/com.amin.tvhome.HomeRedirectService
  CUR=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
  if [[ $CUR != *$SVC* ]]; then
    if [[ -z $CUR || $CUR == null ]]; then NEW=$SVC; else NEW="$CUR:$SVC"; fi
    a shell settings put secure enabled_accessibility_services "$NEW"
  fi
  a shell settings put secure accessibility_enabled 1
  echo "Amazon's launcher is protected on this Fire OS; the Home-button redirect is on instead."
fi
