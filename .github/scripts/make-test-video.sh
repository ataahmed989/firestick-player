#!/usr/bin/env bash
# A five-minute 720p MKV with English audio and English subtitles, for the screenshot run,
# and movie-pgs.mkv: the same film with picture (PGS) subtitles instead.
set -e
mkdir -p media
# A two-line subtitle every few seconds, like a real film.
for i in $(seq 0 74); do
  t=$((i * 4))
  printf '%d\n00:%02d:%02d,500 --> 00:%02d:%02d,300\nThis is how subtitles look in FirePlayer.\nThey stay low and easy to read.\n\n' \
    $((i + 1)) $((t / 60)) $((t % 60)) $(((t + 4) / 60)) $(((t + 4) % 60))
done > media/subs.srt
video="gradients=s=1280x720:r=24000/1001:speed=0.01:nb_colors=3:c0=0x0b1a33:c1=0x2f6fd6:c2=0x4a2a6a"
# The time is printed on the picture, so the skip preview visibly shows a different moment.
clock="drawtext=text='%{pts\\:hms}':fontsize=110:fontcolor=white@0.8:x=(w-tw)/2:y=(h-th)/2-40"
ffmpeg -loglevel error -y -f lavfi -i "$video,$clock" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i media/subs.srt \
  -t 300 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 \
  -c:a aac -ac 2 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng media/movie.mkv \
|| ffmpeg -loglevel error -y -f lavfi -i "$video" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i media/subs.srt \
  -t 300 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 \
  -c:a aac -ac 2 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng media/movie.mkv \
|| ffmpeg -loglevel error -y -f lavfi -i "testsrc2=s=1280x720:r=24000/1001" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i media/subs.srt \
  -t 300 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 \
  -c:a aac -ac 2 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng media/movie.mkv

# The same film with one picture-based (Blu-ray PGS) subtitle track instead, at the same times as the SRT.
# Not having it only costs the PGS screenshots, so a failure here doesn't stop the run.
python3 -c 'import PIL' 2>/dev/null \
  || python3 -m pip install --quiet pillow \
  || python3 -m pip install --quiet --user --break-system-packages pillow \
  || sudo apt-get install -y -qq python3-pil \
  || true
# -copyts: otherwise ffmpeg moves the subtitles to start at 0, half a second early.
python3 "$(dirname "$0")/make-pgs.py" media/pgs.sup \
  && ffmpeg -loglevel error -y -copyts -i media/movie.mkv -i media/pgs.sup -map 0:v -map 0:a -map 1:s -c copy \
    -metadata:s:s:0 language=eng -disposition:s:0 default media/movie-pgs.mkv \
  || echo "Couldn't make media/movie-pgs.mkv; the PGS screenshots will show an error instead."
ls -la media
