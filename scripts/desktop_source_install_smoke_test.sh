#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_dir="$(mktemp -d)"
trap 'rm -rf "$task_dir"' EXIT
sed -n '/^build_source_desktop_app() {/,/^}/p; /^install_selected_desktop_app() {/,/^}/p; /^desktop_app_target_path() {/,/^}/p; /^desktop_app_executable_path() {/,/^}/p; /^desktop_app_legacy_executable_path() {/,/^}/p; /^desktop_app_running_pids() {/,/^}/p; /^stop_desktop_app() {/,/^}/p' "$repo_root/install.sh" > "$task_dir/install-functions.sh"
source "$task_dir/install-functions.sh"

RUNTIME_KOTLIN_DIR="$task_dir/checkout/runtime-kotlin"
INSTALL_SOURCE="source"
DESKTOP_APP_INSTALL_RESULT=""
BUILT_DESKTOP_APP_SOURCE=""
mkdir -p "$RUNTIME_KOTLIN_DIR" "$task_dir/checkout/desktop-tray" "$task_dir/bin"
RUNTIME_LAUNCHER_BIN_DIR="$task_dir/bin"
info() { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*" >&2; }
err() { printf '%s\n' "$*" >&2; }
ok() { printf '%s\n' "$*"; }
ensure_build_jvm() { return 0; }
host_os() { printf 'macos'; }
host_token() { err 'Source install queried the release host token.'; exit 1; }
resolve_desktop_app_release_asset() { err 'Source install queried release assets.'; exit 1; }
desktop_app_install_root() { printf '%s' "$task_dir/install"; }
desktop_app_legacy_target_path() { printf '%s/SkillBill.app' "$2"; }
install_desktop_app_launcher() { ln -sf "$3" "$task_dir/bin/skillbill-desktop"; }
launch_desktop_app() { printf '%s\n' "$3" >> "$task_dir/launched"; }

cat > "$task_dir/checkout/desktop-tray/gradlew" <<'BUILD'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == '--no-daemon -q createDistributable' ]]
if [[ -f fail-build ]]; then
  exit 23
fi
mkdir -p 'build/compose/binaries/main/app/Skill Bill.app/Contents/MacOS'
printf '#!/usr/bin/env bash\nprintf "source desktop app\\n"\n' > 'build/compose/binaries/main/app/Skill Bill.app/Contents/MacOS/Skill Bill'
chmod +x 'build/compose/binaries/main/app/Skill Bill.app/Contents/MacOS/Skill Bill'
BUILD
chmod +x "$task_dir/checkout/desktop-tray/gradlew"

install_selected_desktop_app
app="$task_dir/install/Skill Bill.app/Contents/MacOS/Skill Bill"
[[ "$("$app")" == 'source desktop app' ]]
[[ "$(readlink "$task_dir/bin/skillbill-desktop")" == "$app" ]]
[[ "$DESKTOP_APP_INSTALL_RESULT" == "installed at $task_dir/install/Skill Bill.app" ]]
if [[ -e "$task_dir/launched" ]]; then
  err 'Installer launched a desktop app that was not running.'
  exit 1
fi

(exec -a "$app" sleep 60) &
running_app=$!
unrelated_app_dir="$task_dir/other/Skill Bill.app/Contents/MacOS"
mkdir -p "$unrelated_app_dir"
(exec -a "$unrelated_app_dir/Skill Bill" sleep 60) &
unrelated_app=$!
trap 'kill "$running_app" "$unrelated_app" 2>/dev/null || true; rm -rf "$task_dir"' EXIT
sleep 0.5
install_selected_desktop_app
if kill -0 "$running_app" 2>/dev/null; then
  err 'Installer left the previous desktop app running.'
  exit 1
fi
kill -0 "$unrelated_app"
[[ "$(cat "$task_dir/launched")" == "$app" ]]

(exec -a "$task_dir/bin/skillbill-desktop" sleep 60) &
running_app=$!
sleep 0.5
install_selected_desktop_app
if kill -0 "$running_app" 2>/dev/null; then
  err 'Installer left the desktop app launched through its symlink running.'
  exit 1
fi
kill -0 "$unrelated_app"
[[ "$(wc -l < "$task_dir/launched")" -eq 2 ]]

touch "$task_dir/checkout/desktop-tray/fail-build"
if install_selected_desktop_app; then
  err 'Installer accepted a failed desktop source build.'
  exit 1
fi
[[ "$DESKTOP_APP_INSTALL_RESULT" == 'build failed' ]]
[[ "$("$app")" == 'source desktop app' ]]
[[ "$(readlink "$task_dir/bin/skillbill-desktop")" == "$app" ]]

INSTALL_SOURCE="prebuilt"
host_token() { printf 'macos-arm64'; }
resolve_desktop_app_release_asset() { RESOLVED_DESKTOP_APP_ASSET=""; }
if install_selected_desktop_app; then
  err 'Installer accepted a release without its required desktop archive.'
  exit 1
fi
[[ "$DESKTOP_APP_INSTALL_RESULT" == 'not available for macos-arm64' ]]

printf 'Required desktop install, direct and symlink launch restart, and failure preservation passed.\n'
