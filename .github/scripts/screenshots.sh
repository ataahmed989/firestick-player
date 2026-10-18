#!/usr/bin/env bash
# Drives FirePlayer with D-pad key presses on the emulator and saves a screenshot of each screen in shots/.
set -x
PKG=app.fireplayer
OUT=shots
mkdir -p "$OUT"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
movie() {
  # Back closes whatever is open and leaves the player, which saves the position for the resume question.
  key KEYCODE_BACK KEYCODE_BACK
  sleep 2
  adb shell am force-stop $PKG
  adb shell "am start -W -a android.intent.action.VIEW -d '$1' -t video/x-matroska -n $PKG/.PlayerActivity --es title '$2'"
}

adb install -r FirePlayer.apk || exit 1
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
# Skipping with the controls hidden: the bar comes up with a preview of where the jump lands.
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.3; adb shell input keyevent KEYCODE_DPAD_RIGHT
shot 04b-skip-preview 0.6
sleep 3
key KEYCODE_DPAD_UP
shot 05-controls 1.5
key KEYCODE_DPAD_UP
shot 06-seek-bar-selected 0.6
adb shell input keyevent KEYCODE_DPAD_LEFT; sleep 0.3; adb shell input keyevent KEYCODE_DPAD_LEFT; sleep 0.3; adb shell input keyevent KEYCODE_DPAD_LEFT
shot 06b-seek-back-preview 0.8
sleep 3
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

adb logcat -d -t 400 | grep -iE "fireplayer|exoplayer|AndroidRuntime" | tail -120 > "$OUT/logcat.txt"
ls -la "$OUT"
