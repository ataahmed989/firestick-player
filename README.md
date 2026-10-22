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

## English titles in TiviMate

FirePlayer can give TiviMate its films and shows under their English names from TMDB, as **The Matrix (1999) [4K]**,
and TiviMate's search then finds them by those names.

1. FirePlayer → Settings → Online subtitles → add your **TMDB API key** (or read access token).
2. In TiviMate: Settings → Playlists → a playlist → **Xtream Codes parameters** → **Server address**. Put
   `http://127.0.0.1:8000/` in front of what's there, so `http://proxpanel.fans:80` becomes
   `http://127.0.0.1:8000/proxpanel.fans:80`, then **Apply changes**. A new playlist is added the same way.
3. Update the playlist. FirePlayer looks the titles up in the background (a few thousand a minute, never while a
   film plays); the next update shows them in English.

How it works: FirePlayer runs a small server on the TV, from when it starts, that TiviMate reaches its provider
through. Only the names in the lists of films and shows change; videos, live channels and catch-up go straight to
the provider, and categories, artwork, episodes, favourites and watch history stay as they are. A title is renamed
only when TMDB confirms it (by the TMDB id the provider gives, or by its name and year), so the provider's own name
stays when TMDB isn't sure. The quality tag comes from the provider's name or category ("4K Movies"); videos are
never opened to find it. Settings → TiviMate titles shows how far along each provider is, and turns it off.

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
