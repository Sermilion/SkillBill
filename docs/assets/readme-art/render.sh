#!/usr/bin/env bash
# Renders the README images from index.html at 2x with headless Firefox and ImageMagick.
# usage: docs/assets/readme-art/render.sh docs/assets/readme-art/index.html docs/assets
set -euo pipefail
src=$(realpath "$1"); mkdir -p "$2"; out=$(realpath "$2")
declare -A size=([hero]=1280x720 [goal]=1440x900 [work]=1440x900 [details]=1440x900)
declare -A name=([hero]=sona-readme-hero [goal]=desktop-goal-run [work]=desktop-work-list [details]=desktop-goal-activity)
declare -A radius=([hero]=32 [goal]=24 [work]=24 [details]=24)
for v in hero goal work details; do
  w=${size[$v]%x*}; h=${size[$v]#*x}; W=$((w*2)); H=$((h*2))
  prof=$(mktemp -d)
  timeout 120 firefox --headless --no-remote --profile "$prof" --window-size=$W,$H --screenshot "$out/raw-$v.png" "file://$src?view=$v&scale=2" >/dev/null 2>&1
  rm -rf "$prof"
  r=${radius[$v]}
  magick "$out/raw-$v.png" \( -size ${W}x${H} xc:none -fill white -draw "roundrectangle 0,0,$((W-1)),$((H-1)),$r,$r" \) -alpha off -compose CopyOpacity -composite -define png:compression-level=9 "$out/${name[$v]}.png"
  rm "$out/raw-$v.png"
  echo "$out/${name[$v]}.png $(magick identify -format '%wx%h' "$out/${name[$v]}.png")"
done
