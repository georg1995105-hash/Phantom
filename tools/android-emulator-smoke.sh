#!/usr/bin/env bash
set -euo pipefail
on_failure() {
  local code=$?
  echo "Smoke command failed at line ${BASH_LINENO[0]} (exit ${code})" >&2
  adb logcat -d -b crash || true
  adb logcat -d -t 160 || true
  exit "$code"
}
trap on_failure ERR

PACKAGE='ru.smartvoice.keyboard.debug'
IME="$PACKAGE/helium314.keyboard.latin.LatinIME"
APK="$(find app/build/outputs/apk/debugNoMinify -maxdepth 1 -name '*.apk' -print -quit)"
test -f "$APK"
adb install -r "$APK"
adb shell pm path "$PACKAGE" | grep -q 'package:'
adb shell am start -W -n "$PACKAGE/helium314.keyboard.settings.SettingsActivity"
sleep 3
adb shell pidof "$PACKAGE" | grep -Eq '[0-9]+'
adb shell ime list -a -s | grep -F "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell settings get secure default_input_method | grep -F "$IME"
adb shell wm size 720x1280
adb shell wm density 320
adb shell am start -W -n "$PACKAGE/helium314.keyboard.smart.debug.InputTestActivity"
sleep 3
adb shell input tap 120 310
visible=false
for attempt in $(seq 1 20); do
  if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then visible=true; break; fi
  sleep 3
done
test "$visible" = true
adb shell am broadcast -a ru.smartvoice.DEBUG_UI -p "$PACKAGE" --es type 'Приве'
sleep 2
adb shell uiautomator dump /sdcard/smartvoice-smoke.xml
UI_XML="$(adb exec-out cat /sdcard/smartvoice-smoke.xml)"
[[ "$UI_XML" == *'text="Приве"'* ]]
if adb logcat -d -b crash | grep -E 'FATAL EXCEPTION|Process: ru.smartvoice.keyboard.debug'; then
  echo 'Android crash detected' >&2
  exit 1
fi
echo 'Verified: APK installed; settings launched; IME selected and visible in a real Android field; input dispatched; no crash in buffer.'
