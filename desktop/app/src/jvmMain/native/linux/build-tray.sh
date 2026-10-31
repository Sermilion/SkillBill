#!/usr/bin/env bash
set -euo pipefail

output_dir="$1"
jdk_dir="$2"
source_dir="$output_dir/source"
archive="$output_dir/tray-source.tar.gz"
revision=acdde87059c7e82338cce65535ffe6334f2c91b9
checksum=6495867943bc168411073262106b94b920714f1657d933b290b4a04eaf27ab88
mkdir -p "$source_dir"
if [ ! -f "$archive" ]; then
  curl --fail --location --silent --show-error \
    "https://codeload.github.com/NucleusFramework/ComposeNativeTray/tar.gz/$revision" \
    --output "$archive"
fi
echo "$checksum  $archive" | sha256sum --check --status
tar -xzf "$archive" --strip-components=1 -C "$source_dir"
python3 - "$source_dir/src/native/linux/sni.c" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1])
text = source.read_text()
before = 'if (strcmp(property, "ItemIsMenu") == 0)\n        return sd_bus_message_append(reply, "b", 1);'
if text.count(before) != 1:
    raise SystemExit("ComposeNativeTray ItemIsMenu patch no longer matches")
text = text.replace(before, before.replace('"b", 1', '"b", 0'))
menu = 'return sd_bus_message_append(reply, "o", tray->current_menu_path);'
if text.count(menu) != 1:
    raise SystemExit("ComposeNativeTray Menu patch no longer matches")
text = text.replace(menu, 'return sd_bus_message_append(reply, "o", "/");')
right_click = 'enqueue_callback(tray, (pending_cb){.type = PCB_RCLICK, .x = x, .y = y});'
if text.count(right_click) != 1:
    raise SystemExit("ComposeNativeTray context activation patch no longer matches")
text = text.replace(right_click, right_click.replace('PCB_RCLICK', 'PCB_CLICK'))
tooltip = 'tray->tooltip_text ? tray->tooltip_text : "",\n                              ""); /* description */'
if text.count(tooltip) != 1:
    raise SystemExit("ComposeNativeTray tooltip description patch no longer matches")
text = text.replace(tooltip, '"Skill Bill",\n                              tray->tooltip_text ? tray->tooltip_text : "");')
title = 'return sd_bus_message_append(reply, "s", tray->title ? tray->title : "");'
if text.count(title) != 1:
    raise SystemExit("ComposeNativeTray title patch no longer matches")
text = text.replace(title, 'return sd_bus_message_append(reply, "s", "Skill Bill");')
source.write_text(text)
PY
case "$(uname -m)" in
  x86_64) arch=x64 ;;
  aarch64) arch=aarch64 ;;
  *) echo "Unsupported Linux tray architecture" >&2; exit 1 ;;
esac
native_dir="$source_dir/src/native/linux"
resource_dir="$output_dir/resources/composetray/native/linux-$arch"
mkdir -p "$resource_dir"
gcc -shared -fPIC -O2 -pthread \
  -I"$jdk_dir/include" -I"$jdk_dir/include/linux" \
  -I"$native_dir" -I"$source_dir/src/native/shared" \
  "$native_dir/sni.c" "$native_dir/jni_bridge.c" \
  $(pkg-config --cflags --libs libsystemd) -lm -ldl \
  -o "$resource_dir/libLinuxTray.so"
