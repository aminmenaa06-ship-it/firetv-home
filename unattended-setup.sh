#!/bin/zsh
# Unattended install + debloat, for when the Mac can only reach the TV over a network that the
# Claude session can't stay connected on. Waits for the Fire TV to show up, installs and smoke-tests
# the launcher, disables bloat-packages.txt, checks Home, reboots, checks again, and rolls back to
# Amazon's Home if the new one doesn't take. Announces progress with `say`.
#
# Results land in run-logs/<timestamp>/ (log.txt, STATUS, screenshots, logcat).
# Usage: ./unattended-setup.sh [tv-ip] [--no-debloat]
set -u
cd "$(dirname "$0")"
IP=${1:-172.20.10.3}
DEBLOAT=1; [[ ${2:-} == --no-debloat ]] && DEBLOAT=0
PKG=com.amin.tvhome
APK=app/build/outputs/apk/release/app-release.apk
OUT=run-logs/$(date +%Y%m%d-%H%M%S); mkdir -p $OUT
LOG=$OUT/log.txt
BLOAT=(${(f)"$(grep -v '^#' bloat-packages.txt)"})
DEV="" LOGCAT_PID="" STOCK_HOME=""

log() { print -r -- "$(date +%H:%M:%S) $*" | tee -a $LOG; }
speak() { [[ -n ${QUIET:-} ]] || say "$1" 2>/dev/null & }
a() { adb -s $DEV "$@"; }
shot() { a exec-out screencap -p > $OUT/$1.png 2>/dev/null; }
keys() { for k in "$@"; do a shell input keyevent KEYCODE_$k; sleep 0.35; done; }
resumed() {
  a shell dumpsys activity activities | tr -d '\r' | grep -E "mResumedActivity|topResumedActivity" | head -1 \
    | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1
}
alive() { a shell pidof $PKG 2>/dev/null | grep -q '[0-9]' || a shell ps 2>/dev/null | grep -q $PKG; }
home_handlers() { a shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | tr -d '\r'; }
start_logcat() { adb -s $DEV logcat -v time '*:E' > $OUT/$1 2>&1 & LOGCAT_PID=$!; }

finish() {
  log "RESULT: $1"
  print -r -- "$1" > $OUT/STATUS
  [[ -n $LOGCAT_PID ]] && kill $LOGCAT_PID 2>/dev/null
  speak "$2"
  exit 0
}

rollback() {
  log "ROLLING BACK: $1"
  for p in $BLOAT; do
    a shell pm path $p | grep -q package: && log "  enable $p: $(a shell pm enable $p 2>&1 | tr -d '\r' | tail -1)"
  done
  [[ -n $STOCK_HOME ]] && log "  set-home $STOCK_HOME: $(a shell cmd package set-home-activity $STOCK_HOME 2>&1 | tr -d '\r')"
  a shell input keyevent KEYCODE_HOME; sleep 3; shot 99-after-rollback
  finish "ROLLED BACK: $1" "Fire TV setup was rolled back. Amazon's home screen is restored. You can switch back to campus wifi."
}

# The TV's hotspot address can change on rejoin, so scan the hotspot's /28 if the given IP is silent.
# Any interface counts: Wi-Fi to the hotspot, or the iPhone's USB cable.
my_ips() { ifconfig | awk '/inet /{print $2}' | grep -v '^127\.'; }
# iPhone hotspots hand out 172.20.10.x or, on some carriers, 192.0.0.x. Scan whichever we landed in.
hotspot_prefixes() { my_ips | grep -E '^(172\.20\.10|192\.0\.0)\.' | sed -E 's/\.[0-9]+$//' | sort -u; }
find_tv() {
  local mine=($(my_ips)) cands=($IP) pre
  for pre in $(hotspot_prefixes); do for i in {1..14}; do cands+=($pre.$i); done; done
  for c in $cands; do
    (( ${mine[(Ie)$c]} )) && continue
    nc -z -G 1 $c 5555 2>/dev/null && { DEV=$c:5555; return 0; }
  done
  return 1
}

wait_connected() {
  local deadline=$((SECONDS + $1)) asked=0 st next_diag=0
  while (( SECONDS < deadline )); do
    # Breadcrumbs for when nobody is watching: is the Mac on the hotspot, does the TV answer at all,
    # and is its ADB port open? (No ping = different network; ping but no port = ADB debugging off.)
    if (( SECONDS >= next_diag )); then
      log "diag: mac=[$(ifconfig | awk '/^[a-z]/{i=$1} /inet /{print i $2}' | grep -v '^lo0' | paste -sd ' ' -)] ping $IP=$(ping -c1 -t2 $IP >/dev/null 2>&1 && echo yes || echo no) adb-port=$(nc -z -G 2 $IP 5555 2>/dev/null && echo open || echo closed) hotspot-devices=[$(arp -an | grep -E '\((172\.20\.10|192\.0\.0)\.' | grep -v incomplete | awk '{print $2}' | tr -d '()' | paste -sd ' ' -)]"
      next_diag=$((SECONDS + 20))
    fi
    if find_tv; then
      adb connect $DEV >/dev/null 2>&1
      st=$(adb -s $DEV get-state 2>&1)
      [[ $st == device ]] && return 0
      if [[ $st == *unauthorized* ]] && (( !asked )); then
        log "TV found at $DEV; waiting for the 'Allow USB debugging' prompt to be approved"
        speak "Approve the debugging prompt on your TV. Choose always allow."
        asked=1
      fi
    fi
    sleep 3
  done
  return 1
}

# ---------------------------------------------------------------- connect
[[ -f $APK ]] || finish "FAILED: $APK missing; build first" "Fire TV setup failed. The app isn't built."
log "Waiting up to 45 min for the Fire TV at $IP (Mac must be on the same hotspot)"
wait_connected 2700 || finish "FAILED: TV never answered on port 5555" "Fire TV setup could not find the TV."
log "Connected to $DEV"
speak "Connected to the Fire TV. Setting up, this takes about five minutes."
a shell input keyevent KEYCODE_WAKEUP
for p in ro.product.model ro.product.name ro.build.version.name ro.build.version.release ro.build.version.sdk; do
  log "$p=$(a shell getprop $p | tr -d '\r')"
done
log "$(a shell wm size | tr -d '\r') / $(a shell wm density | tr -d '\r')"
log "$(a shell cat /proc/meminfo | tr -d '\r' | head -2 | tr -s ' ' | paste -sd ';' -)"
a shell pm list packages -d | tr -d '\r' > $OUT/disabled-before.txt
home_handlers > $OUT/home-handlers-before.txt
for p in $BLOAT; do log "$p: $(a shell pm path $p | grep -q package: && echo present || echo 'not on this device')"; done
a logcat -c
start_logcat logcat-errors.txt

# ---------------------------------------------------------------- install + smoke test
log "Installing $APK"
a install -r $APK 2>&1 | tr -d '\r' | tee -a $LOG | grep -q Success || finish "FAILED: install" "Fire TV setup failed at install."
a shell am force-stop $PKG
log "Cold start: $(a shell am start -W -n $PKG/.MainActivity 2>&1 | tr -d '\r' | grep -E 'TotalTime|WaitTime' | paste -sd ' ' -)"
sleep 3; shot 01-launch
alive || finish "FAILED: launcher not running after launch (see logcat-errors.txt)" "Fire TV setup failed. The launcher crashed."

keys DPAD_RIGHT DPAD_RIGHT DPAD_DOWN; sleep 1; shot 02-grid-nav
keys DPAD_UP DPAD_UP DPAD_RIGHT DPAD_DOWN; sleep 1; shot 03-settings-tab
keys BACK; sleep 1.5; shot 04-back-home
a shell dumpsys gfxinfo $PKG reset >/dev/null
keys DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT DPAD_LEFT
a shell dumpsys gfxinfo $PKG | tr -d '\r' | grep -E 'Total frames|Janky frames|percentile' | grep -v gpu | while read -r l; do log "gfx: $l"; done
keys BACK
alive || finish "FAILED: launcher died during navigation (see logcat-errors.txt)" "Fire TV setup failed. The launcher crashed."
if grep -A4 "FATAL EXCEPTION" $OUT/logcat-errors.txt | grep -q $PKG; then
  finish "FAILED: crash during smoke test (see logcat-errors.txt)" "Fire TV setup failed. The launcher crashed."
fi
log "Smoke test passed"

(( DEBLOAT )) || finish "OK: installed and smoke-tested; debloat skipped" "Fire TV setup finished. You can switch back to campus wifi."

# ---------------------------------------------------------------- debloat
# Only take away Amazon's Home if Settings stays reachable from ours.
if ! a shell cmd package query-activities --brief -a android.settings.SETTINGS 2>&1 | grep -qE '^ *[a-z][A-Za-z0-9_.]*/' \
   && ! a shell pm path com.amazon.tv.settings.v2 | grep -q package:; then
  finish "STOPPED before debloat: no Settings app reachable from the launcher" "Fire TV setup stopped before changing the home screen. You can switch back to campus wifi."
fi
STOCK_HOME=$(home_handlers | grep -E '^ *com\.amazon\.tv\.launcher/' | head -1 | tr -d ' ')
log "Amazon Home component: ${STOCK_HOME:-unknown}"
for p in $BLOAT; do
  if a shell pm path $p | grep -q package:; then
    log "disable $p: $(a shell pm disable-user --user 0 $p 2>&1 | tr -d '\r' | tail -1)"
  fi
done
log "set-home: $(a shell cmd package set-home-activity $PKG/.MainActivity 2>&1 | tr -d '\r')"
home_handlers > $OUT/home-handlers-after.txt

a shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 3
a shell input keyevent KEYCODE_HOME; sleep 3
R=$(resumed); log "Home key -> $R"; shot 05-home-after-debloat
[[ $R == $PKG/* ]] || rollback "Home key opened '$R' instead of the new launcher"

# ---------------------------------------------------------------- reboot check
log "Rebooting to check the override survives a restart"
speak "Rebooting the TV to test. Don't touch it for a couple of minutes."
kill $LOGCAT_PID 2>/dev/null; LOGCAT_PID=""
a reboot
sleep 30
wait_connected 480 || finish "UNKNOWN: TV not back on the network 8 min after reboot; check the TV screen" "The TV hasn't come back after rebooting. Check the TV screen."
for i in {1..60}; do [[ $(a shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && break; sleep 3; done
sleep 20
start_logcat logcat-errors-after-reboot.txt
if a shell pm list packages -d | tr -d '\r' | grep -q 'com.amazon.tv.launcher$'; then
  log "Amazon launcher after boot: still disabled"
else
  log "Amazon launcher after boot: RE-ENABLED by Fire OS"
fi
R=$(resumed); log "In front after boot: $R"; shot 06-after-reboot
a shell input keyevent KEYCODE_HOME; sleep 3
R=$(resumed); log "Home key after reboot -> $R"; shot 07-home-after-reboot

if [[ $R == $PKG/* ]]; then
  finish "OK: new launcher is Home and survived a reboot" "Fire TV setup finished. The new home screen is working. You can switch back to campus wifi."
elif [[ $R == com.amazon.tv.launcher/* ]]; then
  finish "FINDING: Fire OS re-enabled its launcher at boot; Amazon's Home is back, nothing broken" "Fire TV setup finished, but Fire OS put its own home screen back. You can switch back to campus wifi."
else
  rollback "after reboot Home opened '$R'"
fi
