#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_dir="$(mktemp -d)"
trap 'rm -rf "$task_dir"' EXIT
cleanup_calls=0
clean_install_state_if_requested() { return 0; }
run_pre_install_uninstall() { cleanup_calls=$((cleanup_calls + 1)); }
err() { printf '%s\n' "$*" >&2; }
source <(sed -n '/^stage_packaged_runtime_distribution() {/,/^}/p; /^install_packaged_runtime_pair() {/,/^}/p' "$repo_root/install.sh")
RUNTIME_CLI_INSTALL_DIR="$task_dir/live/runtime-cli"
RUNTIME_MCP_INSTALL_DIR="$task_dir/live/runtime-mcp"
mkdir -p "$task_dir/cli/bin" "$task_dir/mcp/bin" "$RUNTIME_CLI_INSTALL_DIR" "$RUNTIME_MCP_INSTALL_DIR"
printf 'old-cli\n' > "$RUNTIME_CLI_INSTALL_DIR/marker"
printf 'old-mcp\n' > "$RUNTIME_MCP_INSTALL_DIR/marker"
for kind in cli mcp; do
  cat > "$task_dir/$kind/bin/runtime-$kind" <<'CHECK'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "--check-packaged-contracts" ]]
[[ ! -e "$(dirname "$0")/../invalid-contract" ]]
CHECK
  chmod +x "$task_dir/$kind/bin/runtime-$kind"
  printf 'new-%s\n' "$kind" > "$task_dir/$kind/marker"
done
for kind in cli mcp; do
  touch "$task_dir/$kind/invalid-contract"
  if install_packaged_runtime_pair "$task_dir/cli" "$task_dir/mcp"; then
    err "Installer promoted a rejected $kind package."
    exit 1
  fi
  [[ "$cleanup_calls" == 0 ]]
  [[ "$(cat "$RUNTIME_CLI_INSTALL_DIR/marker")" == old-cli ]]
  [[ "$(cat "$RUNTIME_MCP_INSTALL_DIR/marker")" == old-mcp ]]
  [[ ! -e "$RUNTIME_CLI_INSTALL_DIR.tmp" && ! -e "$RUNTIME_MCP_INSTALL_DIR.tmp" ]]
  rm "$task_dir/$kind/invalid-contract"
done
source <(sed -n '/^install_packaged_runtime_distributions() {/,/^}/p; /^build_kotlin_runtime_distributions() {/,/^}/p; /^install_runtime_distributions() {/,/^}/p' "$repo_root/install.sh")
info() { return 0; }
warn() { return 0; }
ok() { err "Source install reported success after rejecting a package."; exit 1; }
INSTALL_SOURCE="source"
SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1
RUNTIME_KOTLIN_DIR="$task_dir/source-build"
RUNTIME_INSTALL_ROOT="$task_dir/live"
mkdir -p "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install" "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install"
cp -R "$task_dir/cli" "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli"
cp -R "$task_dir/mcp" "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp"
RUNTIME_CLI_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
RUNTIME_MCP_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp"
touch "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp/invalid-contract"
if install_runtime_distributions; then
  err "Normal source installer swallowed packaged contract parity failure."
  exit 1
fi
[[ "$cleanup_calls" == 0 ]]
[[ "$(cat "$RUNTIME_CLI_INSTALL_DIR/marker")" == old-cli ]]
[[ "$(cat "$RUNTIME_MCP_INSTALL_DIR/marker")" == old-mcp ]]
install_packaged_runtime_pair "$task_dir/cli" "$task_dir/mcp"
[[ "$cleanup_calls" == 1 ]]
[[ "$(cat "$RUNTIME_CLI_INSTALL_DIR/marker")" == new-cli ]]
[[ "$(cat "$RUNTIME_MCP_INSTALL_DIR/marker")" == new-mcp ]]
printf 'Runtime package parity smoke test passed.\n'
