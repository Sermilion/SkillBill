#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_dir="$(mktemp -d)"
trap 'rm -rf "$task_dir"' EXIT
sed -n '/^build_source_desktop_app() {/,/^}/p; /^install_selected_desktop_app() {/,/^}/p; /^stage_selected_desktop_app() {/,/^}/p; /^activate_staged_desktop_app() {/,/^}/p; /^desktop_app_target_path() {/,/^}/p; /^desktop_app_executable_path() {/,/^}/p; /^desktop_quoted_exec_path() {/,/^}/p; /^check_desktop_app_target_ownership() {/,/^}/p; /^check_desktop_app_launcher_ownership() {/,/^}/p; /^prepare_desktop_app_launcher() {/,/^}/p; /^discard_desktop_app_launcher_stage() {/,/^}/p; /^install_desktop_app_launcher() {/,/^}/p; /^linux_legacy_desktop_is_owned() {/,/^}/p; /^migrate_kde_desktop_pin() {/,/^}/p; /^cleanup_linux_legacy_desktop() {/,/^}/p; /^desktop_app_running_pids() {/,/^}/p; /^stop_desktop_app() {/,/^}/p; /^desktop_app_hosts_installer() {/,/^}/p; /^run_detached() {/,/^}/p; /^schedule_desktop_app_restart() {/,/^}/p' "$repo_root/install.sh" > "$task_dir/install-functions.sh"
sed -n '/^install_sona_launcher() {/,/^install_runtime_launchers() {/p' "$repo_root/install.sh" | sed '$d' >> "$task_dir/install-functions.sh"
sed -n '/^is_owned_windows_desktop_launcher() {/,/^}/p' "$repo_root/install.sh" >> "$task_dir/install-functions.sh"
source "$task_dir/install-functions.sh"

RUNTIME_KOTLIN_DIR="$task_dir/checkout/runtime-kotlin"
INSTALL_SOURCE="source"
DESKTOP_APP_INSTALL_RESULT=""
BUILT_DESKTOP_APP_SOURCE=""
mkdir -p "$RUNTIME_KOTLIN_DIR" "$task_dir/checkout/desktop" "$task_dir/bin"
RUNTIME_LAUNCHER_BIN_DIR="$task_dir/bin"
RUNTIME_CLI_BIN="$task_dir/runtime-cli"
RUNTIME_MCP_BIN="$task_dir/runtime-mcp"
RECOVERY_DESKTOP_APP_PATH=""
staged_runtime_build_identity_record() { printf fixture; }
desktop_app_build_identity_path() { printf fixture; }
build_identities_match() { return 0; }
report_desktop_recovery_path() { :; }
info() { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*" >&2; }
err() { printf '%s\n' "$*" >&2; }
ok() { printf '%s\n' "$*"; }
ensure_build_jvm() { return 0; }
migrate_kde_desktop_pin() { return 0; }
update-desktop-database() { return 0; }
kbuildsycoca6() { return 0; }
TEST_DESKTOP_OS=macos
TEST_DESKTOP_ROOT="$task_dir/install"
host_os() { printf '%s' "$TEST_DESKTOP_OS"; }
host_token() { err 'Source install queried the release host token.'; exit 1; }
resolve_desktop_app_release_asset() { err 'Source install queried release assets.'; exit 1; }
desktop_app_install_root() { printf '%s' "$TEST_DESKTOP_ROOT"; }
launch_desktop_app() { printf '%s\n' "$3" >> "$task_dir/launched"; }
init_prebuilt_work_dir() { mkdir -p "$task_dir/staging"; }
prebuilt_work_dir() { printf '%s' "$task_dir/staging"; }

cat > "$task_dir/checkout/desktop/gradlew" <<'BUILD'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 4 ]]
[[ "$1" == '--no-daemon' ]]
[[ "$2" == "-Dorg.gradle.daemon.registry.base=$PWD/.gradle/installer-daemon" ]]
[[ "$3" == '-q' && "$4" == 'createDistributable' ]]
if [[ -f fail-build ]]; then
  exit 23
fi
mkdir -p 'app/build/compose/binaries/main/app/Sona.app/Contents/MacOS'
printf '#!/usr/bin/env bash\nprintf "source desktop app\\n"\n' > 'app/build/compose/binaries/main/app/Sona.app/Contents/MacOS/Sona'
chmod +x 'app/build/compose/binaries/main/app/Sona.app/Contents/MacOS/Sona'
printf 'commit=1111111111111111111111111111111111111111\n' > 'app/build/compose/binaries/main/app/Sona.app/Contents/build-identity.properties'
BUILD
chmod +x "$task_dir/checkout/desktop/gradlew"

install_selected_desktop_app
app="$task_dir/install/Sona.app/Contents/MacOS/Sona"
[[ "$("$app")" == 'source desktop app' ]]
[[ "$(readlink "$task_dir/bin/sona-app")" == "$app" ]]
[[ "$DESKTOP_APP_INSTALL_RESULT" == "installed at $task_dir/install/Sona.app" ]]
if [[ -e "$task_dir/launched" ]]; then
  err 'Installer launched a desktop app that was not running.'
  exit 1
fi
mkdir -p "$task_dir/install/Skill Bill.app" "$task_dir/install/SkillBill.app"
printf 'legacy package sentinel\n' > "$task_dir/install/Skill Bill.app/sentinel"
printf 'historical package sentinel\n' > "$task_dir/install/SkillBill.app/sentinel"

stage_selected_desktop_app
printf 'commit=2222222222222222222222222222222222222222\n' > "$BUILT_DESKTOP_APP_SOURCE/Contents/build-identity.properties"
printf '#!/usr/bin/env bash\nprintf "replaced desktop build\\n"\n' > "$BUILT_DESKTOP_APP_SOURCE/Contents/MacOS/Sona"
activate_staged_desktop_app
[[ "$(cat "$task_dir/install/Sona.app/Contents/build-identity.properties")" == 'commit=1111111111111111111111111111111111111111' ]]
[[ "$("$app")" == 'source desktop app' ]]
[[ "$(cat "$task_dir/install/Skill Bill.app/sentinel")" == 'legacy package sentinel' ]]
[[ "$(cat "$task_dir/install/SkillBill.app/sentinel")" == 'historical package sentinel' ]]

(exec -a "$app" sleep 60) &
running_app=$!
unrelated_app_dir="$task_dir/other/Sona.app/Contents/MacOS"
mkdir -p "$unrelated_app_dir"
(exec -a "$unrelated_app_dir/Sona" sleep 60) &
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

(exec -a "$task_dir/bin/sona-app" sleep 60) &
running_app=$!
sleep 0.5
install_selected_desktop_app
if kill -0 "$running_app" 2>/dev/null; then
  err 'Installer left the desktop app launched through its symlink running.'
  exit 1
fi
kill -0 "$unrelated_app"
[[ "$(wc -l < "$task_dir/launched")" -eq 2 ]]

# The installer runs inside the desktop app's terminal: the app is its parent. Stopping
# the app there would end the installer, so the restart waits until the installer exits.
declare -f > "$task_dir/hosted-functions.sh"
declare -p task_dir RUNTIME_KOTLIN_DIR INSTALL_SOURCE DESKTOP_APP_INSTALL_RESULT BUILT_DESKTOP_APP_SOURCE RUNTIME_LAUNCHER_BIN_DIR RUNTIME_CLI_BIN RUNTIME_MCP_BIN RECOVERY_DESKTOP_APP_PATH TEST_DESKTOP_OS TEST_DESKTOP_ROOT > "$task_dir/hosted-vars.sh"
cat > "$task_dir/hosted-install.sh" <<'HOSTED'
set -euo pipefail
source "$1/hosted-vars.sh"
source "$1/hosted-functions.sh"
export task_dir
install_selected_desktop_app
kill -0 "$PPID" && touch "$task_dir/app-alive-after-install"
HOSTED
(exec -a "$app" bash -c 'bash "$1/hosted-install.sh" "$1"; exec sleep 60' hosted "$task_dir") &
hosting_app=$!
trap 'kill "$running_app" "$unrelated_app" "$hosting_app" 2>/dev/null || true; rm -rf "$task_dir"' EXIT
for _ in $(seq 1 100); do
  if ! kill -0 "$hosting_app" 2>/dev/null && [[ "$(wc -l < "$task_dir/launched")" -eq 3 ]]; then
    break
  fi
  sleep 0.1
done
if [[ ! -e "$task_dir/app-alive-after-install" ]]; then
  err 'Installer stopped the desktop app that hosts it before the install finished.'
  exit 1
fi
if kill -0 "$hosting_app" 2>/dev/null; then
  err 'Installer left the desktop app that hosts it running after it exited.'
  exit 1
fi
[[ "$(wc -l < "$task_dir/launched")" -eq 3 ]]
[[ "$(tail -n 1 "$task_dir/launched")" == "$app" ]]
kill -0 "$unrelated_app"

for platform in macos linux windows; do
  platform_root="$task_dir/ownership-$platform"
  RUNTIME_LAUNCHER_BIN_DIR="$task_dir/ownership-$platform-bin"
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  platform_app="$(desktop_app_target_path "$platform" "$platform_root")"
  platform_executable="$(desktop_app_executable_path "$platform" "$platform_app")"
  mkdir -p "$(dirname "$platform_executable")"
  printf 'unrelated app sentinel\n' > "$platform_executable"
  [[ "$platform" == windows ]] || chmod +x "$platform_executable"
  printf '#!/usr/bin/env bash\n# skill-bill managed launcher\n' > "$RUNTIME_LAUNCHER_BIN_DIR/sona"
  if check_desktop_app_target_ownership "$platform" "$platform_app" "$platform_executable"; then
    err "Managed CLI launcher established $platform desktop ownership."
    exit 1
  fi
  if [[ "$platform" == windows ]]; then
    if command -v cygpath >/dev/null 2>&1; then platform_windows_executable="$(cygpath -w "$platform_executable")"; else platform_windows_executable="${platform_executable//\//\\}"; fi
    printf 'REM Managed by Sona install.sh\n"%s" %%*\n' "$platform_windows_executable" > "$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"
  else
    rm "$RUNTIME_LAUNCHER_BIN_DIR/sona"
    ln -s "$platform_executable" "$RUNTIME_LAUNCHER_BIN_DIR/sona"
  fi
  check_desktop_app_target_ownership "$platform" "$platform_app" "$platform_executable"
  [[ "$(cat "$platform_executable")" == 'unrelated app sentinel' ]]
done

TEST_DESKTOP_OS=linux
TEST_DESKTOP_ROOT="$task_dir/unowned-linux-root"
RUNTIME_LAUNCHER_BIN_DIR="$task_dir/unowned-linux-bin"
XDG_DATA_HOME="$task_dir/unowned-linux-xdg"
export XDG_DATA_HOME
unowned_app="$TEST_DESKTOP_ROOT/Sona"
unowned_stage="$task_dir/unowned-linux-stage/Sona"
mkdir -p "$unowned_app/bin" "$unowned_app/lib" "$unowned_stage/bin" "$unowned_stage/lib" "$RUNTIME_LAUNCHER_BIN_DIR"
printf 'keep unrelated app\n' > "$unowned_app/bin/Sona"
printf 'new app\n' > "$unowned_stage/bin/Sona"
printf 'icon\n' > "$unowned_stage/lib/Sona.png"
chmod +x "$unowned_app/bin/Sona" "$unowned_stage/bin/Sona"
printf '#!/usr/bin/env bash\n# skill-bill managed launcher\n' > "$RUNTIME_LAUNCHER_BIN_DIR/sona"
STAGED_DESKTOP_APP_SOURCE="$unowned_stage"
if activate_staged_desktop_app; then
  err 'Installer replaced an unrelated Linux app directory with only a managed CLI launcher.'
  exit 1
fi
[[ "$(cat "$unowned_app/bin/Sona")" == 'keep unrelated app' ]]
[[ ! -e "$RUNTIME_LAUNCHER_BIN_DIR/sona-app" ]]
[[ ! -e "$XDG_DATA_HOME/applications/sona.desktop" ]]
outside_app="$task_dir/outside-app"
mkdir -p "$outside_app/bin" "$outside_app/lib"
printf 'outside sentinel\n' > "$outside_app/bin/Sona"
rm -rf "$unowned_app"
ln -s "$outside_app" "$unowned_app"
if activate_staged_desktop_app; then
  err 'Installer accepted a symlinked desktop app target.'
  exit 1
fi
[[ -L "$unowned_app" && "$(readlink "$unowned_app")" == "$outside_app" ]]
[[ "$(cat "$outside_app/bin/Sona")" == 'outside sentinel' ]]

host_os() { printf 'linux'; }
RUNTIME_LAUNCHER_BIN_DIR="$task_dir/linux-bin"
XDG_DATA_HOME="$task_dir/xdg"
export XDG_DATA_HOME
linux_app="$task_dir/install/Sona"
linux_stage="$task_dir/linux-stage/Sona"
mkdir -p "$linux_app/bin" "$linux_app/lib" "$linux_stage/bin" "$linux_stage/lib" "$XDG_DATA_HOME/applications" "$RUNTIME_LAUNCHER_BIN_DIR"
printf 'previous app\n' > "$linux_app/bin/Sona"
printf 'new app\n' > "$linux_stage/bin/Sona"
printf 'icon\n' > "$linux_stage/lib/Sona.png"
chmod +x "$linux_app/bin/Sona" "$linux_stage/bin/Sona"
ln -s "$linux_app/bin/Sona" "$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
printf '[Desktop Entry]\nName=Sona\nExec="%s"\nIcon=%s\n' "$RUNTIME_LAUNCHER_BIN_DIR/sona-app" "$linux_app/lib/Sona.png" > "$XDG_DATA_HOME/applications/sona.desktop"
STAGED_DESKTOP_APP_SOURCE="$linux_stage"
mv() {
  if [[ "$1" == "$XDG_DATA_HOME/applications/sona.desktop.stage."* && "$2" == "$XDG_DATA_HOME/applications/sona.desktop" ]]; then
    return 1
  fi
  command mv "$@"
}
if activate_staged_desktop_app; then
  err 'Installer accepted a failed Linux menu registration write.'
  exit 1
fi
unset -f mv
[[ "$(cat "$linux_app/bin/Sona")" == 'previous app' ]]
[[ "$(readlink "$RUNTIME_LAUNCHER_BIN_DIR/sona-app")" == "$linux_app/bin/Sona" ]]
grep -Fxq "Icon=$linux_app/lib/Sona.png" "$XDG_DATA_HOME/applications/sona.desktop"
[[ ! -e "$linux_app/lib/Sona.png" ]]
rm "$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
printf 'unrelated launcher\n' > "$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
printf 'unrelated menu entry\n' > "$XDG_DATA_HOME/applications/sona.desktop"
if activate_staged_desktop_app; then
  err 'Installer replaced unrelated Sona registration files.'
  exit 1
fi
[[ "$(cat "$linux_app/bin/Sona")" == 'previous app' ]]
[[ "$(cat "$RUNTIME_LAUNCHER_BIN_DIR/sona-app")" == 'unrelated launcher' ]]
[[ "$(cat "$XDG_DATA_HOME/applications/sona.desktop")" == 'unrelated menu entry' ]]
rm "$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
ln -s "$linux_app/bin/Sona" "$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
if activate_staged_desktop_app; then
  err 'Installer replaced an unrelated Linux menu entry.'
  exit 1
fi
[[ "$(cat "$linux_app/bin/Sona")" == 'previous app' ]]
[[ "$(readlink "$RUNTIME_LAUNCHER_BIN_DIR/sona-app")" == "$linux_app/bin/Sona" ]]
[[ "$(cat "$XDG_DATA_HOME/applications/sona.desktop")" == 'unrelated menu entry' ]]

host_os() { printf 'macos'; }
RUNTIME_LAUNCHER_BIN_DIR="$task_dir/bin"
RUNTIME_CLI_BIN="$task_dir/runtime-cli"
RUNTIME_MCP_BIN="$task_dir/runtime-mcp"
RECOVERY_DESKTOP_APP_PATH=""
staged_runtime_build_identity_record() { printf fixture; }
desktop_app_build_identity_path() { printf fixture; }
build_identities_match() { return 0; }
report_desktop_recovery_path() { :; }
touch "$task_dir/checkout/desktop/fail-build"
if install_selected_desktop_app; then
  err 'Installer accepted a failed desktop source build.'
  exit 1
fi
[[ "$DESKTOP_APP_INSTALL_RESULT" == 'build failed' ]]
[[ "$("$app")" == 'source desktop app' ]]
[[ "$(readlink "$task_dir/bin/sona-app")" == "$app" ]]

INSTALL_SOURCE="prebuilt"
host_token() { printf 'macos-arm64'; }
resolve_desktop_app_release_asset() { RESOLVED_DESKTOP_APP_ASSET=""; }
if install_selected_desktop_app; then
  err 'Installer accepted a release without its required desktop archive.'
  exit 1
fi
[[ "$DESKTOP_APP_INSTALL_RESULT" == 'not available for macos-arm64' ]]

sed -n '/^launch_desktop_app() {/,/^}/p' "$repo_root/install.sh" > "$task_dir/launch-function.sh"
source "$task_dir/launch-function.sh"
mkdir -p "$task_dir/service-bin"
cat > "$task_dir/service-bin/systemctl" <<'SERVICE'
#!/usr/bin/env bash
[[ "$*" == '--user show-environment' ]]
SERVICE
cat > "$task_dir/service-bin/systemd-run" <<'SERVICE'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$DESKTOP_LAUNCH_ARGS"
exit "${DESKTOP_LAUNCH_STATUS:-0}"
SERVICE
chmod +x "$task_dir/service-bin/systemctl" "$task_dir/service-bin/systemd-run"
export DESKTOP_LAUNCH_ARGS="$task_dir/service-launch"
PATH="$task_dir/service-bin:$PATH" launch_desktop_app linux "$(dirname "$app")" "$app"
grep -Fx -- '--service-type=exec' "$DESKTOP_LAUNCH_ARGS" >/dev/null
[[ "$(tail -n 1 "$DESKTOP_LAUNCH_ARGS")" == "$app" ]]
if DESKTOP_LAUNCH_STATUS=23 PATH="$task_dir/service-bin:$PATH" launch_desktop_app linux "$(dirname "$app")" "$app"; then
  err 'Desktop service launch failure was reported as success.'
  exit 1
fi

printf 'Required desktop install, direct, symlink and hosting-app launch restart, and failure preservation passed.\n'
