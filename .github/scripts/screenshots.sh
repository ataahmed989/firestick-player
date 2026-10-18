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
  adb shell "am start -W -a android.intent.action.VIEW -d '$1' -t video/x-matroska -n $PKG/.PlayerActivity --es title '$2'"
}

adb install -r FirePlayer.apk || exit 1
adb_ready
adb shell wm size

# Home screen. This build is older than the latest release, so the update offer shows first.
adb shell am start -W -n $PKG/.HomeActivity
shot 01-update-offer 10
key KEYCODE_BACK
shot 02-home 2
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
shot 03-home-settings 2
key KEYCODE_BACK

# Player.
movie "http://10.0.2.2:8000/movie.mkv" "EN - 4K UHD - Dune: Part Two (2024)"
shot 04-playing 15
# Skipping with the controls hidden brings up the bar and the "+0:20" card.
burst 04b-skip 6 KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
sleep 4
key KEYCODE_DPAD_UP
shot 05-controls 1.5
key KEYCODE_DPAD_UP
shot 06-seek-bar-selected 0.6
# Moving along the selected bar (it waits 2 s before jumping) shows a picture of where it would land.
burst 06b-seek-back-preview 6 KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
burst 06c-seek-preview-moved 4 KEYCODE_DPAD_RIGHT
# Paused, the emulator has time to spare for the preview's picture.
key KEYCODE_MEDIA_PLAY_PAUSE
burst 06d-paused-preview 8 KEYCODE_DPAD_LEFT
adb logcat -d -v time | grep -E "ExoPlayerImpl|MediaCodec|CCodec|AndroidRuntime" | tail -300 > "$OUT/logcat-skip.txt"
key KEYCODE_MEDIA_PLAY_PAUSE
sleep 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
shot 07-more-row 1
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
shot 08-subtitle-style 1.5
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
shot 09-size-picker 1.5
key KEYCODE_BACK KEYCODE_BACK
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
shot 10-settings 1.5
key KEYCODE_BACK
key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_CENTER
shot 11-subtitles 1.5
key KEYCODE_BACK
key KEYCODE_MEDIA_PLAY_PAUSE
shot 12-paused 1.5

# Coming back to the same film asks where to start.
movie "http://10.0.2.2:8000/movie.mkv" "EN - 4K UHD - Dune: Part Two (2024)"
shot 13-resume 6

# A link that isn't on the server.
movie "http://10.0.2.2:8000/missing.mkv" "EN - The Batman (2022)"
shot 14-error 12

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
