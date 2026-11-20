#!/usr/bin/env bash
# Test media for the Live TV fixture (mock-xtream.py): one short looping-looking MPEG-TS stream per channel
# category, with the category and a running clock drawn on the picture, and a PNG logo per fixture channel.
# All names are fictional fixtures. Usage: make-live-media.sh [media-dir]
set -e
dir="${1:-media}"
mkdir -p "$dir/live" "$dir/logos"

# category id, name, two gradient colours
cats=(
  "1|News|0x0b1a33|0x2f6fd6"
  "2|Sports|0x0d2b12|0x2fa84f"
  "3|Nature|0x0a2a2a|0x2a9d8f"
  "4|Movies|0x2a0b1a|0xb5446e"
  "5|Kids|0x2a220b|0xe9a23b"
  "6|Music|0x1f0b2a|0x8d5bd6"
)
for c in "${cats[@]}"; do
  IFS='|' read -r id name c0 c1 <<<"$c"
  out="$dir/live/cat-$id.ts"
  [ -s "$out" ] && continue
  ffmpeg -loglevel error -y \
    -f lavfi -i "gradients=s=640x360:r=25:speed=0.02:nb_colors=2:c0=$c0:c1=$c1,drawtext=text='$name':fontsize=54:fontcolor=white@0.9:x=(w-tw)/2:y=(h-th)/2-30,drawtext=text='%{pts\\:hms}':fontsize=34:fontcolor=white@0.75:x=(w-tw)/2:y=(h-th)/2+40" \
    -f lavfi -i "sine=frequency=$((180 + id * 40)):sample_rate=48000" \
    -t 240 -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p -g 50 -b:v 350k \
    -c:a aac -b:a 64k -ac 2 -f mpegts "$out"
done

# channel number, initials, colour (logos)
logos=(
  "1|NN|0x1d4ed8" "2|GR|0x0f766e" "3|M24|0xb91c1c" "4|AS|0x15803d" "5|GL|0x166534" "6|CS|0x9a3412"
  "7|WE|0x047857" "8|OD|0x0369a1" "9|C1|0x7e22ce" "10|CR|0x6b21a8" "11|KC|0xca8a04" "12|TT|0xea580c"
  "13|PM|0xbe185d" "14|RH|0x9d174d" "15|NW|0x1e40af" "16|TR|0x3f6212" "17|LD|0x155e75" "18|MM|0x4c1d95"
  "101|FT|0x334155" "102|BL|0x0e7490" "103|HT|0x9f1239" "104|SV|0x4d7c0f" "105|DK|0x1e3a8a" "106|QZ|0x7c2d12"
)
for l in "${logos[@]}"; do
  IFS='|' read -r n text colour <<<"$l"
  out="$dir/logos/$n.png"
  [ -s "$out" ] && continue
  ffmpeg -loglevel error -y -f lavfi -i "color=c=$colour:s=160x96" \
    -vf "drawtext=text='$text':fontsize=46:fontcolor=white:x=(w-tw)/2:y=(h-th)/2" -frames:v 1 "$out"
done
echo "Live fixture media in $dir"
