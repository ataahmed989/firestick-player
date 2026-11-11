#!/usr/bin/env bash
# Drives FirePlayer with D-pad key presses on the emulator and saves a screenshot of each screen in shots/.
set -x
PKG=app.fireplayer
OUT=shots
mkdir -p "$OUT"

DEV=/data/local/tmp/shots
adb_ready() { adb shell "rm -rf $DEV; mkdir -p $DEV"; }
# Screenshots are saved raw on the device (quick) and turned into PNGs at the end, so taking one doesn't hold up
# the key presses that follow: the controls hide by themselves after 5 seconds.
shot() { sleep "${2:-2}"; adb shell screencap "$DEV/$1.raw"; }
# Presses keys one after another and, while they're being sent, takes screenshots on the device itself a moment
# apart, so a screen that only shows briefly (the skip preview) is caught however slow the emulator's input is.
burst() {
  local name=$1 frames=$2; shift 2
  adb shell "(for k in $*; do input keyevent \$k; done) & sleep 0.6; for i in \$(seq $frames); do screencap $DEV/$name-\$i.raw; sleep 0.2; done; wait"
}
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
movie() {
  # Back closes whatever is open and leaves the player, which saves the position for the resume question.
  key KEYCODE_BACK KEYCODE_BACK
  sleep 2
  adb shell am force-stop $PKG
  adb shell "am start -W -a android.intent.action.VIEW -d '$1' -t video/x-matroska -n $PKG/.PlayerActivity --es title '$2' $3"
}

adb install -r FirePlayer.apk || exit 1
adb_ready
adb shell wm size

# TEMP: screen dimmer shots only.
adb shell am start -W -n $PKG/.HomeActivity
sleep 8
key KEYCODE_BACK

# Episodes sheet first, with the dimmer off: glass pods dim with the sheet.
movie "http://10.0.2.2:8000/series/demo/demo/2003.mkv" "EN - Breaking Bad (2008) - S02E03 - Bit by a Dead Bee"
shot e16-episode-playing 7
sleep 10
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
shot e18-episodes 4
key KEYCODE_DPAD_UP
shot e18c-episodes-seasons 1.5
key KEYCODE_BACK
sleep 2

# The dimmer window.
key KEYCODE_DPAD_UP
shot d00-controls-before 1
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot d01-more-row 0.5
key KEYCODE_DPAD_CENTER
shot d02-dimmer-list 1.5
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
shot d03-custom-1 1.5
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot d04-display-up-auto 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_UP KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
shot d05-auto-off-glass-down 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
shot d06-save-custom 1.5
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
sleep 1.5
adb shell input text Night
shot d07-name 1.5
key KEYCODE_ENTER
shot d08-saved-night 2
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot d09-night-changed-unsaved 1.5
key KEYCODE_BACK
shot d10-controls-dimmed 1.5
key KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
shot d11-episodes-dimmed 4

# Raw screenshots: a 16-byte header (width, height, format, colour space), then RGBA pixels.
adb pull $DEV /tmp/raw
for f in /tmp/raw/*.raw /tmp/raw/shots/*.raw; do
  [ -f "$f" ] || continue
  read -r w h < <(od -An -tu4 -N8 "$f")
  tail -c +17 "$f" | ffmpeg -loglevel error -y -f rawvideo -pix_fmt rgba -s "${w}x${h}" -i - -frames:v 1 "$OUT/$(basename "${f%.raw}").png"
done
pid=$(adb shell pidof $PKG | tr -d '\r')
adb logcat -d -v time ${pid:+--pid=$pid} | tail -400 > "$OUT/logcat.txt"
adb logcat -d -v time -b crash | tail -100 >> "$OUT/logcat.txt"
ls -la "$OUT"
