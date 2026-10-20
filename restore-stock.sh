#!/bin/zsh
# Re-enable everything debloat.sh disabled and hand Home back to Amazon's launcher.
#                                            Usage: ./restore-stock.sh <fire-tv-ip>
set -uo pipefail
cd "$(dirname "$0")"
DEV=${1:?usage: ./restore-stock.sh <fire-tv-ip>}
[[ $DEV == *:* ]] || DEV=$DEV:5555
a() { adb -s $DEV "$@"; }

adb connect $DEV >/dev/null
for p in ${(f)"$(grep -v '^#' bloat-packages.txt)"}; do
  a shell pm path "$p" | grep -q package: || continue
  echo -n "$p: "; a shell pm enable "$p" 2>&1 | tail -1
done
STOCK=$(a shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tr -d '\r' | grep -E '^ *com\.amazon\.tv\.launcher/' | head -1 | tr -d ' ')
if [[ -n $STOCK ]]; then
  a shell cmd package set-home-activity "$STOCK"
else
  echo "Couldn't find Amazon's Home activity; disabling this launcher so Home falls back to stock."
  a shell pm disable-user --user 0 com.amin.tvhome
fi
