#!/usr/bin/env bash
set -euo pipefail

icons_dir="$(cd "$(dirname "$0")" && pwd)"
source_svg="$icons_dir/../../../../design/App/docs/assets/skill-bill-app-icon.svg"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for size in 16 32 48 64 128 256 512 1024; do
  rsvg-convert -w "$size" -h "$size" "$source_svg" > "$tmp/$size.png"
done
cp "$tmp/512.png" "$icons_dir/../../../core/designsystem/src/commonMain/composeResources/drawable/skill_bill.png"
magick "$tmp/16.png" "$tmp/32.png" "$tmp/48.png" "$tmp/64.png" "$tmp/128.png" "$tmp/256.png" "$icons_dir/skill-bill.ico"
python3 -I - "$tmp" "$icons_dir/skill-bill.icns" <<'PY'
import pathlib
import struct
import sys

source = pathlib.Path(sys.argv[1])
entries = ((b"ic07", "128.png"), (b"ic08", "256.png"), (b"ic09", "512.png"), (b"ic10", "1024.png"))
payload = b"".join(kind + struct.pack(">I", 8 + len((source / name).read_bytes())) + (source / name).read_bytes() for kind, name in entries)
pathlib.Path(sys.argv[2]).write_bytes(b"icns" + struct.pack(">I", 8 + len(payload)) + payload)
PY
