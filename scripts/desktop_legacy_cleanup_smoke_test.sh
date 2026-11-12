#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_dir="$(mktemp -d)"
trap 'rm -rf "$task_dir"' EXIT
for function_name in desktop_app_target_path desktop_app_executable_path desktop_quoted_exec_path check_desktop_app_launcher_ownership prepare_desktop_app_launcher discard_desktop_app_launcher_stage install_desktop_app_launcher linux_legacy_desktop_is_owned migrate_kde_desktop_pin cleanup_linux_legacy_desktop activate_staged_desktop_app; do
  sed -n "/^$function_name() {/,/^}/p" "$repo_root/install.sh" >> "$task_dir/functions.sh"
done
sed -n '/^install_skill_bill_launcher() {/,/^install_runtime_launchers() {/p' "$repo_root/install.sh" | sed '$d' >> "$task_dir/functions.sh"
sed -n '/^is_owned_windows_desktop_launcher() {/,/^}/p' "$repo_root/install.sh" >> "$task_dir/functions.sh"
source "$task_dir/functions.sh"

RUNTIME_LAUNCHER_BIN_DIR="$task_dir/bin"
RUNTIME_CLI_BIN="$task_dir/runtime-cli"
RUNTIME_MCP_BIN="$task_dir/runtime-mcp"
RECOVERY_DESKTOP_APP_PATH=""
staged_runtime_build_identity_record() { printf fixture; }
desktop_app_build_identity_path() { printf fixture; }
build_identities_match() { return 0; }
report_desktop_recovery_path() { :; }
XDG_DATA_HOME="$task_dir/xdg"
XDG_CURRENT_DESKTOP=KDE
fixture_install_root="$task_dir/install"
legacy_app="$fixture_install_root/Skill Bill"
legacy_entry="$XDG_DATA_HOME/applications/skillbill.desktop"
legacy_launcher="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
STAGED_DESKTOP_APP_SOURCE="$task_dir/staged/Sona"
mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR" "$XDG_DATA_HOME/applications" "$STAGED_DESKTOP_APP_SOURCE/bin" "$STAGED_DESKTOP_APP_SOURCE/lib" "$task_dir/state/projects"
printf '#!/bin/sh\nexit 0\n' > "$STAGED_DESKTOP_APP_SOURCE/bin/Sona"
chmod +x "$STAGED_DESKTOP_APP_SOURCE/bin/Sona"
printf 'Sona icon\n' > "$STAGED_DESKTOP_APP_SOURCE/lib/Sona.png"
printf 'preserved run and project data\n' > "$task_dir/state/projects/sentinel"
info() { printf '%s\n' "$*"; }
ok() { info "$*"; }
err() { printf '%s\n' "$*" >&2; }
warn() { printf '%s\n' "$*" >> "$task_dir/warnings"; }
host_os() { printf linux; }
desktop_app_install_root() { printf '%s' "$fixture_install_root"; }
desktop_app_running_pids() { printf '%s\n' "$@" > "$task_dir/running-paths"; }
qdbus6() {
  [[ "$1" == org.kde.plasmashell && "$2" == /PlasmaShell && "$3" == org.kde.PlasmaShell.evaluateScript ]]
  printf '%s' "$4" > "$task_dir/pin-script"
  printf 'pin\n' >> "$task_dir/cache-calls"
  return "${pin_status:-0}"
}
update-desktop-database() { [[ "$1" == "$XDG_DATA_HOME/applications" ]]; printf 'database\n' >> "$task_dir/cache-calls"; }
kbuildsycoca6() { printf 'kde\n' >> "$task_dir/cache-calls"; }
seed_legacy() {
  mkdir -p "$legacy_app/bin" "$legacy_app/lib"
  printf 'legacy app\n' > "$legacy_app/bin/Skill Bill"
  ln -s "$legacy_app/bin/Skill Bill" "$legacy_launcher"
  printf '[Desktop Entry]\nName=Skill Bill\nExec="%s"\nIcon=%s/lib/Skill Bill.png\nStartupWMClass=dev-skillbill-desktop-app-DesktopLauncherKt\n' "$legacy_launcher" "$legacy_app" > "$legacy_entry"
}
assert_legacy_removed() {
  [[ ! -e "$legacy_app" && ! -L "$legacy_app" ]]
  [[ ! -e "$legacy_launcher" && ! -L "$legacy_launcher" ]]
  [[ ! -e "$legacy_entry" ]]
  [[ -x "$fixture_install_root/Sona/bin/Sona" ]]
  [[ "$(readlink "$RUNTIME_LAUNCHER_BIN_DIR/sona-app")" == "$fixture_install_root/Sona/bin/Sona" ]]
  [[ "$(cat "$task_dir/state/projects/sentinel")" == 'preserved run and project data' ]]
}

# A failed activation must leave the previous working installation available.
seed_legacy
mv() {
  if [[ "$1" == "$XDG_DATA_HOME/applications/sona.desktop.stage."* ]]; then return 1; fi
  command mv "$@"
}
if activate_staged_desktop_app; then err 'Accepted a failed menu registration.'; exit 1; fi
unset -f mv
[[ -f "$legacy_app/bin/Skill Bill" && -L "$legacy_launcher" && -f "$legacy_entry" ]]
[[ ! -e "$task_dir/cache-calls" ]]

# A real old Linux installation must disappear after Sona activates, including on retry.
activate_staged_desktop_app
assert_legacy_removed
grep -Fxq "$legacy_app/bin/Skill Bill" "$task_dir/running-paths"
grep -Fxq "$legacy_launcher" "$task_dir/running-paths"
grep -Fxq 'Name=Sona' "$XDG_DATA_HOME/applications/sona.desktop"
printf 'pin\ndatabase\nkde\n' > "$task_dir/expected-cache-calls"
cmp "$task_dir/expected-cache-calls" "$task_dir/cache-calls"
activate_staged_desktop_app
assert_legacy_removed

# Partial cleanup must remain retryable when the desktop entry or bundle is gone.
seed_legacy
rm "$legacy_entry"
cleanup_linux_legacy_desktop "$fixture_install_root"
assert_legacy_removed
seed_legacy
rm -rf "$legacy_app"
rm "$legacy_launcher"
cleanup_linux_legacy_desktop "$fixture_install_root"
assert_legacy_removed

# A cache or dock failure must report recovery without rolling back the new app.
seed_legacy
pin_status=1
activate_staged_desktop_app
assert_legacy_removed
grep -Fq 'Could not update the old KDE dock pin' "$task_dir/warnings"
pin_status=0

# An unrelated registration or symlinked bundle must never authorize deletion.
seed_legacy
printf 'unrelated menu entry\n' > "$legacy_entry"
cp "$task_dir/cache-calls" "$task_dir/cache-before"
activate_staged_desktop_app
[[ -f "$legacy_app/bin/Skill Bill" && -L "$legacy_launcher" ]]
[[ "$(cat "$legacy_entry")" == 'unrelated menu entry' ]]
cmp "$task_dir/cache-before" "$task_dir/cache-calls"
grep -Fq 'ownership could not be confirmed' "$task_dir/warnings"
rm -rf "$legacy_app"
rm "$legacy_entry" "$legacy_launcher"
seed_legacy
mv "$legacy_app" "$task_dir/unrelated-app"
ln -s "$task_dir/unrelated-app" "$legacy_app"
cleanup_linux_legacy_desktop "$fixture_install_root"
[[ -L "$legacy_app" && -f "$task_dir/unrelated-app/bin/Skill Bill" ]]

printf 'Legacy Linux desktop cleanup, activation failure, retry, diagnostics, and ownership checks passed.\n'
