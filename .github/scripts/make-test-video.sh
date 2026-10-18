#!/usr/bin/env bash
# A two-minute 720p MKV with English audio and English subtitles, for the screenshot run.
set -e
mkdir -p media
cat > media/subs.srt <<'SRT'
1
00:00:01,000 --> 00:01:59,000
This is how subtitles look in FirePlayer.
They stay low and easy to read.
SRT
video="gradients=s=1280x720:r=24000/1001:speed=0.01:nb_colors=3:c0=0x0b1a33:c1=0x2f6fd6:c2=0x4a2a6a"
ffmpeg -loglevel error -y -f lavfi -i "$video" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i media/subs.srt \
  -t 120 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 \
  -c:a aac -ac 2 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng media/movie.mkv \
|| ffmpeg -loglevel error -y -f lavfi -i "testsrc2=s=1280x720:r=24000/1001" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i media/subs.srt \
  -t 120 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 \
  -c:a aac -ac 2 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng media/movie.mkv
ls -la media
