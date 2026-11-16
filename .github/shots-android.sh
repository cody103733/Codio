#!/usr/bin/env bash
# Opens the phone and TV apps on the emulator and saves a picture of each main screen into out/.
set -u
mkdir -p out
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "out/$1.png"; }
size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${size%x*}; H=${size#*x}
echo "screen $W x $H"

adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r apk/Codio-Phone-9.9.9.apk
adb shell pm grant com.codio.phone android.permission.POST_NOTIFICATIONS || true
adb shell am start -n com.codio.phone/com.codio.tv.MainActivity
shot android-phone-home 75
adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*3/10)) 400
shot android-phone-home-2 5
TABY=$((H * 94 / 100))
i=0
for name in search library settings; do
  i=$((i+1))
  adb shell input tap $((W/8 + i*W/4)) $TABY
  shot android-phone-$name 6
done
adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*2/10)) 400
shot android-phone-settings-2 4
adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*2/10)) 400
shot android-phone-settings-3 4
adb shell am force-stop com.codio.phone

adb install -r apk/Codio-AndroidTV-9.9.9.apk
adb shell am start -n com.codio.tv/.MainActivity
shot android-tv-home 75
key() { adb shell input keyevent "$1"; sleep 1; }
key DPAD_DOWN; shot android-tv-home-rows 4
key BACK; key DPAD_UP
key DPAD_RIGHT; key DPAD_CENTER; shot android-tv-movies 12
key DPAD_UP; key DPAD_UP; key DPAD_UP
key DPAD_RIGHT; key DPAD_RIGHT; key DPAD_CENTER; shot android-tv-search 5
key DPAD_UP; key DPAD_UP; key DPAD_UP; key DPAD_UP; key DPAD_UP
key DPAD_RIGHT; key DPAD_CENTER; shot android-tv-library 5
key DPAD_UP; key DPAD_UP
key DPAD_RIGHT; key DPAD_RIGHT; key DPAD_CENTER; shot android-tv-settings 5
key DPAD_DOWN; key DPAD_DOWN; shot android-tv-settings-2 3
exit 0
