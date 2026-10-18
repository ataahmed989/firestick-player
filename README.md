# FirePlayer

A fast, minimal video player for Fire TV and Android TV, built for opening VOD from TiviMate.

- Media3 / ExoPlayer with hardware decoding, plus FFmpeg for audio formats the Fire TV can't decode (DTS, TrueHD and others)
- Embedded subtitles (SRT, ASS/SSA with full styling through libass, PGS and DVB picture subtitles), plus subtitle files and OpenSubtitles search
- Netflix-style subtitles by default, pinned to the bottom of the screen on any aspect ratio, with saveable presets
- Subtitle and audio delay, display size, speed, sleep timer, frame-rate matching and surround passthrough
- D-pad left/right jumps 10 s and rewind/fast-forward jumps 1:00; repeated presses add up into one jump

## Install on a Fire TV

1. On the Fire TV, install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Settings → My Fire TV → Developer options → Install unknown apps → turn on **Downloader**.
3. Open Downloader and enter this address:

   ```
   https://github.com/ataahmed989/firestick-player/releases/latest/download/FirePlayer.apk
   ```

4. Install. Later builds install over the top and keep your settings.

## Use it from TiviMate

TiviMate → Settings → Playback → External player → **FirePlayer**. Then pick a movie or episode and choose to play it in the external player (or set it as the default player for VOD). FirePlayer reports where you stopped, so TiviMate's resume points keep working.

## Remote

| Key | Action |
| --- | --- |
| Left / right | Jump 10 s (hold to go faster) |
| Rewind / fast-forward | Jump 1:00 |
| OK, up or down | Show the controls |
| Down (controls showing) | More options |
| Back | Hide controls, close a window, or exit |

Jump sizes, default languages and the rest live in the Settings window.

## Building

GitHub Actions builds `FirePlayer.apk` on every push to `main` and publishes it as the latest release. To build locally: `./gradlew assembleRelease` (JDK 17, Android SDK 36).

The release signing key is committed on purpose so every build installs over the last one. It's only meant for personal sideloading.
