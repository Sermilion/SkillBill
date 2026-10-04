#!/usr/bin/env bash
set -euo pipefail

ORIGINAL_ARGS=("$@")
INSTALLER_SCRIPT_SOURCE="${BASH_SOURCE[0]:-}"
INSTALLER_FROM_STDIN=0
if [[ -z "$INSTALLER_SCRIPT_SOURCE" || ! -f "$INSTALLER_SCRIPT_SOURCE" ]]; then
  INSTALLER_FROM_STDIN=1
fi

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
RUNTIME_KOTLIN_DIR="$PLUGIN_DIR/runtime-kotlin"
RUNTIME_CLI_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
RUNTIME_MCP_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp"
SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
RUNTIME_INSTALL_ROOT="${SKILL_BILL_RUNTIME_DIR:-$SKILL_BILL_STATE_DIR/runtime}"
RUNTIME_CLI_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-cli"
RUNTIME_MCP_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-mcp"
RUNTIME_CLI_BIN="$RUNTIME_CLI_INSTALL_DIR/bin/runtime-cli"
RUNTIME_MCP_BIN="$RUNTIME_MCP_INSTALL_DIR/bin/runtime-mcp"
RUNTIME_LAUNCHER_BIN_DIR="${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}"

# Install source. `auto` means a full local checkout installs from source, while a
# standalone downloaded installer falls back to published prebuilt release assets.
INSTALL_SOURCE="auto"
RELEASE_TAG="${SKILL_BILL_RELEASE_TAG:-}"
REUSE_LAST_SELECTION=0
CLEAN_INSTALL=0
RELEASE_REPO="${SKILL_BILL_RELEASE_REPO:-Sermilion/skill-bill}"
# Memoized result of resolve_latest_runtime_release_tag. The tag is consumed once per
# asset plus once per .sha256 sibling and by both list_release_asset_names callers, so
# resolving each time would multiply unauthenticated api.github.com requests.
RESOLVED_LATEST_RUNTIME_TAG=""
# Offline / test overrides. When SKILL_BILL_RELEASE_DIR is set, assets are copied
# from that local directory (no network). When SKILL_BILL_RELEASE_BASE_URL is set,
# curl is pointed at that base URL (supports file://) instead of the GitHub API.
SKILL_BILL_RELEASE_DIR="${SKILL_BILL_RELEASE_DIR:-}"
SKILL_BILL_RELEASE_BASE_URL="${SKILL_BILL_RELEASE_BASE_URL:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# The Kotlin runtime is compiled for Java 21, and build-logic:convention sits on
# the Gradle buildscript classpath, so the Gradle daemon JVM itself must be 21+.
# Reuse the same guard that is baked into the generated runtime start scripts so
# SKILL_BILL_JAVA_HOME resolves identically at build time and at run time.
BUILD_JVM_GUARD="$RUNTIME_KOTLIN_DIR/runtime-infra/host/src/main/resources/skillbill/infrastructure/host/jvm/skill-bill-java-guard.sh"

ensure_build_jvm() {
  [[ -r "$BUILD_JVM_GUARD" ]] || return 0
  local resolved
  # The guard exports JAVA_HOME, unsets it when the PATH java already qualifies,
  # or exits 1 after printing remediation to stderr. Run it in a subshell and
  # replay its decision here so a failure stays recoverable by the caller.
  resolved=$(. "$BUILD_JVM_GUARD" >/dev/null; printf '%s' "${JAVA_HOME:-}") || return 1
  if [[ -n "$resolved" ]]; then
    export JAVA_HOME="$resolved"
  else
    unset JAVA_HOME
  fi
}

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

if [[ "${SKILL_BILL_GOAL_CONTINUATION:-}" == "1" ]]; then
  err "Refusing to run install.sh during skill-bill goal-continuation."
  err "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  exit 64
fi

declare -a SUPPORTED_AGENTS=(claude codex junie cursor)
declare -a AGENT_NAMES=()
declare -a AGENT_PATHS=()
declare -a PLATFORM_PACKAGES=()
declare -a SELECTED_PLATFORM_PACKAGES=()
declare -a RUNTIME_INSTALL_ARGS=()

AGENT_SELECTION_MODE="manual"
PLATFORM_SELECTION_MODE="none"
TELEMETRY_LEVEL="anonymous"
MCP_REGISTRATION="register"

usage() {
  cat <<USAGE
Usage: ./install.sh [--from-source] [--release TAG] [--reuse-last-selection]

By default a local checkout builds and installs from source. A standalone
downloaded installer uses checksum-verified prebuilt release images.

Options:
  --from-source            Force building the runtime from source with Gradle.
                           Ignores --release. Requires a JDK.
  --release TAG            Install a specific release tag instead of the latest
                           stable release. Ignored with --from-source.
  --reuse-last-selection   Reuse the latest successful agent, platform,
                           telemetry, and MCP choices from ~/.skill-bill.
  --clean                  Wipe ~/.skill-bill/skills/, ~/.skill-bill/platform-packs/,
                           and ~/.skill-bill/orchestration/ before staging the
                           candidate tree. Useful for a clean-slate install.
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --from-source)
        INSTALL_SOURCE="source"
        shift
        ;;
      --reuse-last-selection)
        REUSE_LAST_SELECTION=1
        shift
        ;;
      --clean)
        CLEAN_INSTALL=1
        shift
        ;;
      --release)
        if [[ $# -lt 2 || -z "$(trim_string "$2")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
        RELEASE_TAG="$2"
        if [[ "$INSTALL_SOURCE" == "auto" ]]; then
          INSTALL_SOURCE="prebuilt"
        fi
        shift 2
        ;;
      --release=*)
        RELEASE_TAG="${1#--release=}"
        if [[ -z "$(trim_string "$RELEASE_TAG")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
        if [[ "$INSTALL_SOURCE" == "auto" ]]; then
          INSTALL_SOURCE="prebuilt"
        fi
        shift
        ;;
      *)
        err "Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done
}

local_source_checkout_available() {
  [[ -d "$SKILLS_DIR" ]] &&
    [[ -d "$PLATFORM_PACKS_DIR" ]] &&
    [[ -d "$PLUGIN_DIR/orchestration" ]] &&
    [[ -x "$RUNTIME_KOTLIN_DIR/gradlew" ]]
}

resolve_install_source() {
  if [[ "$INSTALL_SOURCE" != "auto" ]]; then
    return 0
  fi
  if [[ -n "$RELEASE_TAG" || -n "$SKILL_BILL_RELEASE_DIR" || -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    INSTALL_SOURCE="prebuilt"
    return 0
  fi
  if local_source_checkout_available; then
    INSTALL_SOURCE="source"
  else
    INSTALL_SOURCE="prebuilt"
  fi
}

# Resolve the newest runtime release tag from the GitHub Releases list.
# The GitHub "latest release" endpoint cannot be used because it returns the most
# recently published release of any kind, which a plugin-v* release would win. Only plain
# vMAJOR.MINOR.PATCH tags are considered, and the winner is the highest version
# rather than the first list entry.
resolve_latest_runtime_release_tag() {
  local api_url json tags tag key best best_key
  if [[ -n "$RESOLVED_LATEST_RUNTIME_TAG" ]]; then
    printf '%s' "$RESOLVED_LATEST_RUNTIME_TAG"
    return 0
  fi
  api_url="https://api.github.com/repos/$RELEASE_REPO/releases?per_page=100"
  if ! json="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api_url")"; then
    err "Failed to query releases: $api_url"
    return 1
  fi
  tags="$(printf '%s' "$json" | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/')"
  best=""
  best_key=""
  while IFS= read -r tag; do
    [[ -n "$tag" ]] || continue
    key="$(printf '%s' "${tag#v}" \
      | awk -F. 'NF==3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ {
          printf "%010d.%010d.%010d", $1, $2, $3
        }')"
    [[ -n "$key" ]] || continue
    if [[ -z "$best_key" || "$key" > "$best_key" ]]; then
      best_key="$key"
      best="$tag"
    fi
  done <<< "$tags"
  if [[ -z "$best" ]]; then
    err "Failed to resolve latest runtime release tag from: $api_url"
    return 1
  fi
  RESOLVED_LATEST_RUNTIME_TAG="$best"
  printf '%s' "$best"
}

# Prime the memo in the PARENT shell, before any command-substitution helper needs the
# tag: an assignment made inside `$(...)` would be discarded, so without this the API
# would be queried once per asset, once per .sha256 sibling and once per asset listing.
# Best effort — a failure here is re-reported loudly by the resolver at the point of use.
init_latest_runtime_release_tag() {
  if [[ -n "$RELEASE_TAG" || -n "$SKILL_BILL_RELEASE_DIR" ]]; then
    return 0
  fi
  if [[ -z "$RESOLVED_LATEST_RUNTIME_TAG" ]]; then
    RESOLVED_LATEST_RUNTIME_TAG="$(resolve_latest_runtime_release_tag 2>/dev/null || true)"
  fi
  return 0
}

resolve_release_installer_tag() {
  if [[ -n "$RELEASE_TAG" ]]; then
    printf '%s' "$RELEASE_TAG"
    return 0
  fi
  resolve_latest_runtime_release_tag
}

bootstrap_release_installer_if_needed() {
  if [[ "$INSTALLER_FROM_STDIN" -ne 1 ]]; then
    return 0
  fi
  if [[ "${SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "$SKILL_BILL_RELEASE_DIR" || -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    return 0
  fi
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    return 0
  fi

  if ! command -v curl >/dev/null 2>&1; then
    err "curl is required to resolve and fetch the release installer."
    exit 1
  fi

  local tag installer_url installer
  tag="$(resolve_release_installer_tag)" || exit 1
  installer_url="https://raw.githubusercontent.com/$RELEASE_REPO/$tag/install.sh"
  info "Standalone installer: using release installer $tag."
  if ! installer="$(curl -fsSL "$installer_url")"; then
    err "Failed to fetch release installer: $installer_url"
    exit 1
  fi

  local bootstrap_args=("${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")
  if [[ -z "$RELEASE_TAG" ]]; then
    bootstrap_args=(--release "$tag" "${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")
  fi

  export SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED=1
  exec bash -s -- "${bootstrap_args[@]+"${bootstrap_args[@]}"}" <<<"$installer"
}


host_os() {
  local uname_s
  uname_s="$(uname -s 2>/dev/null || printf 'unknown')"
  case "$uname_s" in
    Darwin*)
      printf 'macos'
      ;;
    Linux*)
      printf 'linux'
      ;;
    MINGW*|MSYS*|CYGWIN*)
      printf 'windows'
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

# Prebuilt-path dependency gate. The prebuilt install needs only near-universal
# tools — NO JDK and NO gh CLI — so verify them up front and fail loudly with a
# single message listing everything missing. Skipped under --from-source, where
# Gradle (and its JDK) are the real dependencies.
check_prebuilt_dependencies() {
  local missing=()

  if ! command -v curl >/dev/null 2>&1; then
    missing+=("curl (to download release assets)")
  fi
  if ! command -v tar >/dev/null 2>&1; then
    missing+=("tar (to unpack runtime images)")
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    missing+=("unzip (to unpack .zip runtime images)")
  fi
  if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
    missing+=("shasum or sha256sum (to verify .sha256 checksums)")
  fi

  if [[ ${#missing[@]} -gt 0 ]]; then
    err "Cannot run the prebuilt install: missing required tools."
    local item
    for item in "${missing[@]}"; do
      err "  - $item"
    done
    err "Install the tools above, or re-run with --from-source to build from source instead."
    return 1
  fi
}

# Map `uname -m` to the canonical arch segment used in release asset names.
detect_host_arch() {
  local uname_m
  uname_m="$(uname -m 2>/dev/null || printf 'unknown')"
  case "$uname_m" in
    arm64|aarch64)
      printf 'arm64'
      ;;
    x86_64|amd64)
      printf 'x64'
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

# Canonical `<os>-<arch>` token (macos-arm64/macos-x64/windows-x64/linux-x64).
# Returns an explicit unsupported signal (empty stdout, exit 1) for any host that
# is not one of the four published targets so the prebuilt path can auto-fall back
# to --from-source.
HOST_TOKEN_UNSUPPORTED=""
host_token() {
  local os arch token
  os="$(host_os)"
  arch="$(detect_host_arch)"
  token="$os-$arch"
  case "$token" in
    macos-arm64|macos-x64|windows-x64|linux-x64)
      printf '%s' "$token"
      return 0
      ;;
    *)
      HOST_TOKEN_UNSUPPORTED="$token"
      return 1
      ;;
  esac
}

# Work directory for all downloads. Removed by an EXIT trap so a failed/partial
# fetch never leaves artifacts behind. The trap is registered ONCE at top level
# (not inside prebuilt_work_dir) because prebuilt_work_dir is frequently called
# inside $(...) command substitutions — a trap registered there would fire when
# the subshell exits and wipe the directory mid-install.
PREBUILT_WORK_DIR=""
cleanup_prebuilt_work_dir() {
  if [[ -n "$PREBUILT_WORK_DIR" && -d "$PREBUILT_WORK_DIR" ]]; then
    rm -rf "$PREBUILT_WORK_DIR"
  fi
}

# SKILL-76 subtask 2 (F-006): the staged reconcile candidate dirs (~/.skill-bill/
# .candidate-*) must ALWAYS be reaped on crash/signal, not only on the happy path.
# Composed into the single EXIT trap below so it never clobbers the prebuilt-work-dir
# cleanup. Guarded (discard_authored_candidates is a no-op when the dirs are absent),
# and it only ever removes the .candidate-* staging dirs, never a mid-commit live tree
# (the runtime apply moves into the live skills/ dir, which is not a candidate path).
cleanup_install_exit() {
  cleanup_prebuilt_work_dir
  # discard_authored_candidates is defined later in this file; on an early-exit before
  # its definition the candidate dirs do not exist yet, so guard the call.
  if declare -f discard_authored_candidates >/dev/null 2>&1; then
    discard_authored_candidates
  fi
}
trap cleanup_install_exit EXIT

# Create the shared download work dir once, in the PARENT shell, before any
# command-substitution helper needs it. Idempotent.
init_prebuilt_work_dir() {
  if [[ -z "$PREBUILT_WORK_DIR" ]]; then
    PREBUILT_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/skill-bill-release.XXXXXX")"
  fi
}

prebuilt_work_dir() {
  init_prebuilt_work_dir
  printf '%s' "$PREBUILT_WORK_DIR"
}

# Compute the SHA-256 of a file as a bare lowercase hex string.
compute_sha256() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    sha256sum "$file" | awk '{print $1}'
  fi
}

# Fetch a single named release asset into the work dir and print its local path.
# Resolution order (overrides win, no network when an override is set):
#   1. SKILL_BILL_RELEASE_DIR  → copy the named file from that local directory.
#   2. SKILL_BILL_RELEASE_BASE_URL → curl "<base>/<name>" (supports file://).
#   3. GitHub release download URL for RELEASE_REPO at RELEASE_TAG, or at the resolved
#      newest runtime tag when RELEASE_TAG is unset.
# Fails loudly and removes any partial download on error.
fetch_release_asset() {
  local name="$1"
  local work_dir dest
  work_dir="$(prebuilt_work_dir)"
  dest="$work_dir/$name"

  if [[ -n "$SKILL_BILL_RELEASE_DIR" ]]; then
    if [[ ! -f "$SKILL_BILL_RELEASE_DIR/$name" ]]; then
      err "Release asset not found in SKILL_BILL_RELEASE_DIR: $name"
      return 1
    fi
    cp "$SKILL_BILL_RELEASE_DIR/$name" "$dest"
    printf '%s' "$dest"
    return 0
  fi

  local url
  if [[ -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    url="${SKILL_BILL_RELEASE_BASE_URL%/}/$name"
  else
    local ref tag
    if [[ -n "$RELEASE_TAG" ]]; then
      ref="download/$RELEASE_TAG"
    else
      # The latest-release redirect would be won by a plugin-v* release, so pin the
      # download to the same runtime tag list_release_asset_names resolved.
      if ! tag="$(resolve_latest_runtime_release_tag)"; then
        return 1
      fi
      ref="download/$tag"
    fi
    url="https://github.com/$RELEASE_REPO/releases/$ref/$name"
  fi

  if ! curl -fsSL "$url" -o "$dest"; then
    err "Failed to download release asset: $name"
    err "  from: $url"
    rm -f "$dest"
    return 1
  fi
  printf '%s' "$dest"
}

# Verify a downloaded asset against its `.sha256` sibling (format `<hex>␣␣<name>`).
# On mismatch or missing checksum, fail loudly and remove the asset so no partial
# state survives.
verify_sha256() {
  local asset_path="$1"
  local name
  name="$(basename "$asset_path")"
  local checksum_path
  checksum_path="$(fetch_release_asset "$name.sha256")" || return 1

  local expected actual
  expected="$(awk '{print $1}' "$checksum_path" | head -n1)"
  if [[ -z "$expected" ]]; then
    err "Empty or malformed checksum for $name"
    rm -f "$asset_path" "$checksum_path"
    return 1
  fi
  actual="$(compute_sha256 "$asset_path")"
  if [[ "$actual" != "$expected" ]]; then
    err "Checksum mismatch for $name"
    err "  expected: $expected"
    err "  actual:   $actual"
    rm -f "$asset_path"
    return 1
  fi
  ok "  verified checksum: $name"
}

# List the asset filenames available for the target release. Offline
# (SKILL_BILL_RELEASE_DIR) → directory listing; otherwise → GitHub Releases API
# (pinned RELEASE_TAG or latest stable). Prints one name per line.
list_release_asset_names() {
  if [[ -n "$SKILL_BILL_RELEASE_DIR" ]]; then
    if [[ ! -d "$SKILL_BILL_RELEASE_DIR" ]]; then
      err "SKILL_BILL_RELEASE_DIR is not a directory: $SKILL_BILL_RELEASE_DIR"
      return 1
    fi
    (cd "$SKILL_BILL_RELEASE_DIR" && find . -maxdepth 1 -type f -printf '%f\n' 2>/dev/null \
      || ls -1 "$SKILL_BILL_RELEASE_DIR")
    return 0
  fi

  local api_url tag
  if [[ -n "$RELEASE_TAG" ]]; then
    api_url="https://api.github.com/repos/$RELEASE_REPO/releases/tags/$RELEASE_TAG"
  else
    if ! tag="$(resolve_latest_runtime_release_tag)"; then
      return 1
    fi
    api_url="https://api.github.com/repos/$RELEASE_REPO/releases/tags/$tag"
  fi
  local json
  if ! json="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api_url")"; then
    err "Failed to query release metadata: $api_url"
    return 1
  fi
  # Extract asset names from the JSON without requiring jq. The GitHub API emits
  # one "name":"<asset>" per asset inside the "assets" array.
  printf '%s' "$json" | grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
}

# Resolve the skills bundle asset name for the current release.
# Queries list_release_asset_names (offline or GitHub API) and returns the
# first filename matching skill-bill-skills-*.tar.gz. Fails loudly when not found.
resolve_skills_bundle_asset_name() {
  local names name
  if ! names="$(list_release_asset_names)"; then
    err "Failed to list release assets while resolving skills bundle name."
    return 1
  fi
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    case "$name" in
      skill-bill-skills-*.tar.gz)
        printf '%s' "$name"
        return 0
        ;;
    esac
  done <<< "$names"
  err "No skill-bill-skills-*.tar.gz asset found in release."
  return 1
}

# Bootstrap PLUGIN_DIR from a GitHub release when no trusted local tree is present.
# For a non-piped install the local tree wins when SKILLS_DIR exists and this is a no-op.
# A piped install (INSTALLER_FROM_STDIN=1) cannot trust a CWD-relative skills/ dir, so it
# always fetches the bundle: print an info line, resolve the bundle asset name, fetch and
# verify the .tar.gz, extract into a subdir of PREBUILT_WORK_DIR, then re-point PLUGIN_DIR,
# SKILLS_DIR, and PLATFORM_PACKS_DIR to the extracted root.
bundle_bootstrap_if_needed() {
  if [[ "$INSTALLER_FROM_STDIN" -ne 1 && -d "$SKILLS_DIR" ]]; then
    return 0
  fi

  if [[ "$INSTALLER_FROM_STDIN" -eq 1 ]]; then
    info "Piped install — fetching skills bundle from release."
  else
    info "SKILLS_DIR not found — fetching skills bundle from release."
  fi
  check_prebuilt_dependencies || return 1
  init_prebuilt_work_dir
  init_latest_runtime_release_tag

  local asset_name
  if ! asset_name="$(resolve_skills_bundle_asset_name)"; then
    err "Cannot proceed without a skills bundle."
    return 1
  fi

  local asset_path
  if ! asset_path="$(fetch_release_asset "$asset_name")"; then
    err "Failed to fetch skills bundle: $asset_name"
    return 1
  fi

  verify_sha256 "$asset_path" || return 1

  local extract_dir
  extract_dir="$PREBUILT_WORK_DIR/skills-bundle"
  mkdir -p "$extract_dir"
  tar -xzf "$asset_path" -C "$extract_dir"

  if [[ -d "$extract_dir/skills" ]]; then
    PLUGIN_DIR="$extract_dir"
  else
    local subdir
    subdir="$(find "$extract_dir" -mindepth 2 -maxdepth 2 -type d -name skills -print 2>/dev/null | head -n1)"
    if [[ -n "$subdir" ]]; then
      PLUGIN_DIR="$(dirname "$subdir")"
    else
      err "Skills bundle layout is unrecognised: no skills/ directory found under $extract_dir"
      return 1
    fi
  fi
  SKILLS_DIR="$PLUGIN_DIR/skills"
  PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"

  ok "Skills bundle extracted; PLUGIN_DIR set to: $PLUGIN_DIR"
}

# Wipe the three skill-state subdirectories when --clean was passed.
# Runs after bundle_bootstrap_if_needed and before copy_in_authored_source.
clean_install_state_if_requested() {
  if [[ "$CLEAN_INSTALL" -ne 1 ]]; then
    return 0
  fi
  clean_install_state
}

clean_install_state() {
  if [[ -z "$SKILL_BILL_STATE_DIR" ]]; then
    err "--clean: SKILL_BILL_STATE_DIR is empty; refusing to wipe."
    return 1
  fi
  info "--clean: wiping prior skill state under $SKILL_BILL_STATE_DIR"
  rm -rf \
    "$SKILL_BILL_STATE_DIR/skills" \
    "$SKILL_BILL_STATE_DIR/platform-packs" \
    "$SKILL_BILL_STATE_DIR/orchestration" \
    "$SKILL_BILL_BASELINE_MANIFEST"
  ok "Prior skill state wiped."
}

# Resolve the prebuilt asset filenames for this host by SUFFIX matching, not by
# exact filename: runtime-cli `.zip` and runtime-mcp `.zip` whose names end with
# `<host-token>.zip`. Writes to the named output vars. Returns 1 (unsupported signal) when the host
# token is unsupported OR no runtime asset matches it, so the caller auto-falls
# back to --from-source.
RESOLVED_RUNTIME_CLI_ASSET=""
RESOLVED_RUNTIME_MCP_ASSET=""
resolve_release_assets() {
  local token
  if ! token="$(host_token)"; then
    return 1
  fi

  local names name
  init_latest_runtime_release_tag
  if ! names="$(list_release_asset_names)"; then
    return 1
  fi

  RESOLVED_RUNTIME_CLI_ASSET=""
  RESOLVED_RUNTIME_MCP_ASSET=""

  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    case "$name" in
      *.sha256) continue ;;
    esac
    case "$name" in
      runtime-cli-*"$token".zip)
        RESOLVED_RUNTIME_CLI_ASSET="$name"
        ;;
      runtime-mcp-*"$token".zip)
        RESOLVED_RUNTIME_MCP_ASSET="$name"
        ;;
    esac
  done <<< "$names"

  if [[ -z "$RESOLVED_RUNTIME_CLI_ASSET" || -z "$RESOLVED_RUNTIME_MCP_ASSET" ]]; then
    HOST_TOKEN_UNSUPPORTED="$token"
    return 1
  fi
}




# AC6: print exactly what the installer will change on the system and how to
# reverse it, BEFORE any mutation runs.
print_install_plan() {
  echo ""
  printf "${CYAN}━━━ What this installer will change ━━━${NC}\n"
  echo ""
  info "Cleanup: after both runtime candidates pass parity, re-runs ./uninstall.sh while preserving copied source and durable databases."
  info "Agent symlinks: links Skill Bill skills into your selected agents' skill/command directories."
  info "Runtime: installs the Kotlin runtime under $RUNTIME_INSTALL_ROOT"
  info "Launchers: $RUNTIME_LAUNCHER_BIN_DIR/skill-bill, $RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
  info "MCP registration: registers the skill-bill MCP server with your selected agents."
  echo ""
  info "Reverse everything with: $PLUGIN_DIR/uninstall.sh"
  echo ""
}

# One-time migration: older installs kept config.json inside the wiped
# ~/.skill-bill/. Move it to the durable XDG location (~/.config/skill-bill/,
# outside the wipe zone) BEFORE the pre-install uninstall so user settings —
# external_addon_sources, telemetry choices, install_id — survive every install.
# Skipped when the user pins a config path via SKILL_BILL_CONFIG_PATH, or when
# the durable copy already exists (never clobber it).
migrate_legacy_config_to_durable_path() {
  if [[ -n "${SKILL_BILL_CONFIG_PATH:-}" ]]; then
    return 0
  fi
  local legacy="$SKILL_BILL_STATE_DIR/config.json"
  local durable="$HOME/.config/skill-bill/config.json"
  if [[ -f "$legacy" && ! -f "$durable" ]]; then
    mkdir -p "$(dirname "$durable")"
    if mv "$legacy" "$durable"; then
      info "Migrated Skill Bill config to durable location: $durable"
    else
      warn "Could not migrate config to $durable; leaving it at $legacy."
    fi
  fi
}

# Every install starts from a clean slate: removes agent symlinks, native
# subagent symlinks, MCP registrations, runtime launchers, and wipes
# ~/.skill-bill/ (including the installed-skills staging cache, runtime
# binaries, while preserving persistent state DBs). This guarantees that
# generator changes — which the staging-cache content hash does not see —
# actually land on the next install without deleting durable workflow state.
#
# Tests and dev iteration can opt out with SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1.
run_pre_install_uninstall() {
  if [[ "${SKILL_BILL_SKIP_PREINSTALL_UNINSTALL:-}" == "1" ]]; then
    warn "Skipping pre-install uninstall because SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1."
    return 0
  fi
  if [[ ! -x "$RUNTIME_CLI_BIN" && ! -d "$SKILL_BILL_STATE_DIR" ]]; then
    info "No prior Skill Bill install detected; skipping pre-install cleanup."
    return 0
  fi
  local uninstall_script="$PLUGIN_DIR/uninstall.sh"
  if [[ ! -x "$uninstall_script" ]]; then
    err "Cannot run pre-install cleanup: $uninstall_script is missing or not executable."
    exit 1
  fi
  echo ""
  printf "${CYAN}━━━ Pre-install cleanup ━━━${NC}\n"
  echo ""
  info "Running uninstall.sh first so every install starts from a clean slate."
  # PRESERVE the copied-in self-contained source (skills/, platform-packs/,
  # orchestration/ + the reserved baseline-manifest path) and durable *.db state
  # across the pre-install wipe, while still clearing runtime/ and
  # installed-skills/.
  # This flag is ONLY set for the install-driven pre-install uninstall; an
  # explicit ./uninstall.sh (flag unset) still fully removes ~/.skill-bill.
  SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1 bash "$uninstall_script"
}

locate_packaged_runtime_bin() {
  local path="$1"
  local label="$2"
  if [[ ! -x "$path" ]]; then
    err "Missing packaged Kotlin $label runtime: $path"
    return 1
  fi
}

stage_packaged_runtime_distribution() {
  local source_dir="$1" target_dir="$2" label="$3"
  local tmp_dir="$target_dir.tmp"
  if [[ ! -d "$source_dir" ]]; then
    err "Missing packaged Kotlin $label distribution: $source_dir"
    return 1
  fi
  rm -rf "$tmp_dir" || return 1
  mkdir -p "$(dirname "$target_dir")" || return 1
  cp -R "$source_dir" "$tmp_dir" || return 1
}

install_packaged_runtime_pair() {
  local cli_source="$1" mcp_source="$2"
  local candidate_root
  candidate_root="$(mktemp -d "${TMPDIR:-/tmp}/skill-bill-runtime-candidates.XXXXXX")" || return 1
  local cli_target="$candidate_root/runtime-cli" mcp_target="$candidate_root/runtime-mcp"
  if ! stage_packaged_runtime_distribution "$cli_source" "$cli_target" "CLI" ||
     ! stage_packaged_runtime_distribution "$mcp_source" "$mcp_target" "MCP"; then
    rm -rf "$candidate_root"
    return 1
  fi
  local candidate
  for candidate in "$cli_target.tmp/bin/runtime-cli" "$mcp_target.tmp/bin/runtime-mcp"; do
    if ! "$candidate" --check-packaged-contracts; then
      err "Packaged runtime contract parity failed. Both live distributions and durable databases were preserved."
      rm -rf "$candidate_root"
      return 1
    fi
  done
  if ! clean_install_state_if_requested || ! run_pre_install_uninstall; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! mkdir -p "$(dirname "$RUNTIME_CLI_INSTALL_DIR")" "$(dirname "$RUNTIME_MCP_INSTALL_DIR")"; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! rm -rf "$RUNTIME_CLI_INSTALL_DIR" || ! mv "$cli_target.tmp" "$RUNTIME_CLI_INSTALL_DIR"; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! rm -rf "$RUNTIME_MCP_INSTALL_DIR" || ! mv "$mcp_target.tmp" "$RUNTIME_MCP_INSTALL_DIR"; then
    rm -rf "$candidate_root"
    return 1
  fi
  rm -rf "$candidate_root"
}

# Copy the clone's authored skill/platform/orchestration source into the
# Skill Bill state dir as REAL files (not symlinks) BEFORE any skill linking,
# so that deleting the clone after a successful install leaves a fully
# functional install. Uses the same atomic copy idiom as
# install_packaged_runtime_distribution (rm -rf tmp; cp -R src tmp; rm -rf
# target; mv tmp target). Only authored source lives in these trees:
# content.md, native-agents/, platform.yaml. Generated SKILL.md wrappers and
# support pointers are render OUTPUT into installed-skills staging and never
# appear under the clone's skills/, so an unfiltered cp -R is source-safe.
# SKILL-76 subtask 2 candidate paths: the clone source is staged into these
# .candidate dirs FIRST (no swap), reconciled against the existing copy + baseline,
# and only swapped into place once the conflict decision is accept/no-conflict. An
# abort discards the candidates and leaves the existing install fully intact.
# SKILL-76 subtask 2: the candidate clone source is staged into a SINGLE candidate
# REPO ROOT containing skills/ + platform-packs/, so reconcile --upstream-repo-root,
# --upstream-skills, and --upstream-platform-packs all point at the same staged tree
# (F-008: support-pointer source and skill source come from one tree).
SKILL_BILL_CANDIDATE_ROOT="$SKILL_BILL_STATE_DIR/.candidate-source"
SKILL_BILL_CANDIDATE_SKILLS="$SKILL_BILL_CANDIDATE_ROOT/skills"
SKILL_BILL_CANDIDATE_PLATFORM_PACKS="$SKILL_BILL_CANDIDATE_ROOT/platform-packs"
SKILL_BILL_CANDIDATE_ORCHESTRATION="$SKILL_BILL_CANDIDATE_ROOT/orchestration"
SKILL_BILL_CANDIDATE_AGENT_ADDONS="$SKILL_BILL_CANDIDATE_ROOT/agent-addons"
SKILL_BILL_BASELINE_MANIFEST="$SKILL_BILL_STATE_DIR/baseline-manifest.json"

# Stage one source tree into a candidate dir without touching the live target.
stage_authored_candidate() {
  local source_dir="$1"
  local candidate_dir="$2"
  local label="$3"
  if [[ ! -d "$source_dir" ]]; then
    err "Missing authored $label source: $source_dir"
    return 1
  fi
  rm -rf "$candidate_dir"
  mkdir -p "$(dirname "$candidate_dir")"
  cp -R "$source_dir" "$candidate_dir"
}

# Reap the staged candidate dirs. Guarded so it is safe to call from the EXIT trap
# (no-op when the dirs are already gone). Never touches the live source trees.
discard_authored_candidates() {
  rm -rf \
    "$SKILL_BILL_CANDIDATE_ROOT" \
    "$SKILL_BILL_CANDIDATE_ORCHESTRATION"
}

# Step 1 (decision strictly BEFORE any live-tree mutation): stage the clone's
# authored source into candidate dirs. No live tree is mutated here.
copy_in_authored_source() {
  info "Staging authored skill source candidates under: $SKILL_BILL_STATE_DIR"
  mkdir -p "$SKILL_BILL_CANDIDATE_ROOT"
  stage_authored_candidate "$SKILLS_DIR" "$SKILL_BILL_CANDIDATE_SKILLS" "skills source"
  stage_authored_candidate "$PLATFORM_PACKS_DIR" "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS" "platform-packs source"
  stage_authored_candidate "$PLUGIN_DIR/orchestration" "$SKILL_BILL_CANDIDATE_ORCHESTRATION" "orchestration source"
  if [[ -d "$PLUGIN_DIR/agent-addons" ]]; then
    stage_authored_candidate "$PLUGIN_DIR/agent-addons" "$SKILL_BILL_CANDIDATE_AGENT_ADDONS" "agent-addons source"
  else
    mkdir -p "$SKILL_BILL_CANDIDATE_AGENT_ADDONS"
  fi
  ok "Authored source candidates staged under $SKILL_BILL_STATE_DIR"
}

# Adopt-always non-skill-keyed trees from the candidate into the live state dir.
# orchestration/ is NOT part of the per-skill baseline in this subtask (it is shared
# source, not a skill), so it is replaced wholesale from the candidate every install.
#
# The platform-packs tree is intentionally NOT copied here. The runtime `install reconcile
# --apply` is the SOLE writer of ALL reconciled skill dirs in BOTH skills/ AND
# platform-packs/, and it also adopts the non-skill platform-pack files (platform.yaml,
# addon markdown, pack-level metadata) from upstream. Blanket-copying the pack tree here
# would clobber per-skill pack content BEFORE the apply classifies it, silently defeating
# keep-local/conflict for platform-pack skills.
adopt_non_skill_source_trees() {
  # orchestration: wholesale atomic replace.
  if [[ -d "$SKILL_BILL_CANDIDATE_ORCHESTRATION" ]]; then
    rm -rf "$SKILL_BILL_STATE_DIR/orchestration"
    mv "$SKILL_BILL_CANDIDATE_ORCHESTRATION" "$SKILL_BILL_STATE_DIR/orchestration"
  fi
}

# Parse the line-oriented machine report (mirrors the SKILL-74 line protocol). FAIL-CLOSED:
# if the `reconcile_summary:` line is absent or unparseable the caller MUST abort rather
# than treat an empty report as a clean run.
RECONCILE_FAILURE_KIND=""
parse_reconcile_report() {
  local report="$1"
  local summary_line applied
  summary_line="$( { printf '%s\n' "$report" | grep -m1 '^reconcile_summary:'; } || true )"
  if [[ -z "$summary_line" ]]; then
    return 1
  fi
  applied="$( { printf '%s' "$summary_line" | grep -o 'applied=[a-z]*' | cut -d= -f2; } || true )"
  if [[ -z "$applied" ]]; then
    return 1
  fi
  return 0
}

# Step 2-4: reconcile the staged candidate against the existing local copy, then call the
# runtime per-skill APPLY (which owns the per-skill file ops + baseline refresh). Runs
# AFTER the runtime CLI is installed so run_runtime_cli is available.
#
# UPSTREAM ALWAYS WINS: every skill with an upstream counterpart is installed from
# upstream, overwriting any local edit, and skills/ + platform-packs/ mirror the source.
# There is no prompt and no keep-local path.
# - adopt: upstream differs from the local copy → the live skill dir is replaced.
# - unchanged: upstream and local are byte-identical → nothing is written.
# - prune: a skills/ or platform-packs/ entry upstream no longer ships is DELETED.
# - locally-authored: a user-owned agent-addons/ entry is NEVER written or deleted.
#
# The compute pass runs first purely as a pre-mutation failure detector: enumeration
# errors in the upstream candidate (contract drift, malformed manifests) surface as
# RECONCILE_FAILURE_KIND=compute BEFORE any live tree is touched. Stale contract
# versions in the preserved local copy are tolerated because upstream always wins.
#
# The shell performs NO whole-tree rm/mv swap of skills/ — the runtime per-skill apply
# is the sole writer of the live skill dirs.
reconcile_and_commit_authored_source() {
  local report status
  RECONCILE_FAILURE_KIND=""
  info "Reconciling staged source against existing copy and baseline manifest..."
  # Compute the per-skill plan against UPSTREAM=candidate, LOCAL=existing copy. The
  # upstream repo root, skills, and platform-packs all come from the one staged
  # candidate tree (F-008). No mutation here.
  report="$(run_runtime_cli install reconcile \
    --repo-root "$SKILL_BILL_STATE_DIR" \
    --skills "$SKILL_BILL_STATE_DIR/skills" \
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs" \
    --upstream-repo-root "$SKILL_BILL_CANDIDATE_ROOT" \
    --upstream-skills "$SKILL_BILL_CANDIDATE_SKILLS" \
    --upstream-platform-packs "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS")" || status=$?
  if [[ -n "${status:-}" ]]; then
    RECONCILE_FAILURE_KIND="compute"
    err "Reconciliation failed; leaving the existing install untouched."
    discard_authored_candidates
    return 1
  fi

  # FAIL-CLOSED: a missing/unparseable summary line aborts the install rather than
  # treating an empty report as a clean run.
  if ! parse_reconcile_report "$report"; then
    RECONCILE_FAILURE_KIND="parse"
    err "Could not parse the reconcile machine report; aborting the install. Nothing was changed."
    discard_authored_candidates
    return 1
  fi

  # Hand the per-skill file ops to the runtime apply while the candidate remains a
  # complete repo root for support-pointer validation. The runtime is the SOLE writer of
  # the live skill dirs in BOTH skills/ AND platform-packs/ (and of the non-skill
  # platform-pack files); locally-authored skills are preserved by construction.
  local apply_args=(
    install reconcile --apply
    --repo-root "$SKILL_BILL_STATE_DIR"
    --skills "$SKILL_BILL_STATE_DIR/skills"
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs"
    --upstream-repo-root "$SKILL_BILL_CANDIDATE_ROOT"
    --upstream-skills "$SKILL_BILL_CANDIDATE_SKILLS"
    --upstream-platform-packs "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS"
  )
  if ! run_runtime_cli "${apply_args[@]}" >/dev/null; then
    RECONCILE_FAILURE_KIND="apply"
    err "Runtime reconcile apply failed; some skills may not have been updated."
    discard_authored_candidates
    return 1
  fi
  adopt_non_skill_source_trees
  # RESERVED SEAM (subtask 2): the baseline manifest lives at
  # "$SKILL_BILL_BASELINE_MANIFEST" and is part of the preserved self-contained source
  # set (uninstall.sh preserve-mode). The runtime apply already refreshed it.
  discard_authored_candidates
  ok "Authored source reconciled and committed into $SKILL_BILL_STATE_DIR"
}

reconcile_and_commit_authored_source_with_recovery() {
  if reconcile_and_commit_authored_source; then
    return 0
  fi
  if [[ "$RECONCILE_FAILURE_KIND" != "compute" ]]; then
    return 1
  fi
  warn "Reconciliation failed against the preserved copied source; retrying once with a clean copied-source reset."
  warn "Durable workflow and review state DBs are preserved."
  clean_install_state
  copy_in_authored_source
  reconcile_and_commit_authored_source
}

install_packaged_runtime_distributions() {
  info "Installing packaged Kotlin runtime to: $RUNTIME_INSTALL_ROOT"
  install_packaged_runtime_pair \
    "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" \
    "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp" || return 1
  locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI" || return 1
  locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP" || return 1
  ok "Kotlin runtime installed"
}

# Unpack a runtime image archive into a directory and print the top-level image
# directory that contains bin/<base> (the layout install_packaged_runtime_distribution
# expects as its source_dir). The Badass runtimeZip keeps the installDist layout
# under a single top-level directory, so we locate bin/<base> robustly rather than
# assuming a fixed top-dir name.
unpack_runtime_image() {
  local archive="$1"
  local base="$2"
  local extract_dir="$3"

  mkdir -p "$extract_dir"
  case "$archive" in
    *.zip)
      unzip -q -o "$archive" -d "$extract_dir"
      ;;
    *.tar.gz|*.tgz)
      tar -xzf "$archive" -C "$extract_dir"
      ;;
    *)
      err "Unsupported runtime image format: $archive"
      return 1
      ;;
  esac

  local bin_path
  bin_path="$(find "$extract_dir" -type f -path "*/bin/$base" -print 2>/dev/null | head -n1)"
  if [[ -z "$bin_path" ]]; then
    err "Could not locate bin/$base inside $archive"
    return 1
  fi
  chmod +x "$bin_path"
  # The source_dir for install_packaged_runtime_distribution is the directory two
  # levels above bin/<base> (i.e. the image root that contains bin/ and lib/).
  printf '%s' "$(dirname "$(dirname "$bin_path")")"
}

# Prebuilt runtime install: resolve + fetch + verify the runtime-cli/runtime-mcp
# image zips for this host, unpack each, and feed the unpacked image dirs into the
# EXISTING install_packaged_runtime_distribution (cp→.tmp + atomic mv). No Gradle,
# no JDK.
install_prebuilt_runtime_distributions() {
  local work_dir cli_archive mcp_archive cli_src mcp_src
  init_prebuilt_work_dir
  init_latest_runtime_release_tag
  work_dir="$(prebuilt_work_dir)"

  info "Fetching prebuilt runtime images for host token: $(host_token)"
  cli_archive="$(fetch_release_asset "$RESOLVED_RUNTIME_CLI_ASSET")" || return 1
  verify_sha256 "$cli_archive" || return 1
  mcp_archive="$(fetch_release_asset "$RESOLVED_RUNTIME_MCP_ASSET")" || return 1
  verify_sha256 "$mcp_archive" || return 1

  cli_src="$(unpack_runtime_image "$cli_archive" "runtime-cli" "$work_dir/extract-cli")" || return 1
  mcp_src="$(unpack_runtime_image "$mcp_archive" "runtime-mcp" "$work_dir/extract-mcp")" || return 1

  info "Installing packaged Kotlin runtime to: $RUNTIME_INSTALL_ROOT"
  install_packaged_runtime_pair "$cli_src" "$mcp_src" || return 1
  locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI" || return 1
  locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP" || return 1
  ok "Kotlin runtime installed from prebuilt release"
}

# Dispatcher: source builds for local checkouts, prebuilt downloads for release
# installs, and source fallback when no prebuilt artifact matches this host.
# Preserves the SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD test escape hatch by
# routing through build_kotlin_runtime_distributions for the source path.
install_runtime_distributions() {
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    info "Installing runtime from source (--from-source); ignoring any --release tag."
    build_kotlin_runtime_distributions || return 1
    return 0
  fi

  # The test escape hatch short-circuits to the durable/build copy path without a
  # network fetch, regardless of source. Honor it on the prebuilt path too.
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    build_kotlin_runtime_distributions || return 1
    return 0
  fi

  check_prebuilt_dependencies || exit 1

  if resolve_release_assets; then
    install_prebuilt_runtime_distributions || return 1
    return 0
  fi

  INSTALL_SOURCE="source"
  warn "No prebuilt runtime artifact matched this host (token: ${HOST_TOKEN_UNSUPPORTED:-unknown}); falling back to a from-source Gradle build."
  build_kotlin_runtime_distributions || return 1
}

release_version_for_untracked_build_tree() {
  if git -C "$RUNTIME_KOTLIN_DIR" describe --tags --abbrev=0 --match 'v[0-9]*' >/dev/null 2>&1; then
    return 0
  fi
  resolve_release_installer_tag 2>/dev/null || true
}

build_kotlin_runtime_distributions() {
  # Build output path: Gradle application installDist bin scripts at:
  # runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli
  # runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp
  # Install path: durable copied distributions under ~/.skill-bill/runtime/.
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    warn "Skipping packaged Kotlin runtime distribution build because SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1."
    if [[ -x "$RUNTIME_CLI_BUILD_BIN" && -x "$RUNTIME_MCP_BUILD_BIN" ]]; then
      install_packaged_runtime_distributions || return 1
    else
      locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI" || return 1
      locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP" || return 1
    fi
    return 0
  fi

  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"
  if [[ ! -x "$gradlew" ]]; then
    err "Missing Gradle wrapper: $gradlew"
    return 1
  fi

  if ! ensure_build_jvm; then
    err "Building the Kotlin runtime requires a Java 21+ JVM (see the message above)."
    return 1
  fi

  local stamped_version
  stamped_version="$(release_version_for_untracked_build_tree)"

  info "Building packaged Kotlin runtime distributions..."
  rm -rf \
    "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" \
    "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp" || return 1
  (
    cd "$RUNTIME_KOTLIN_DIR" || exit 1
    if [[ -n "$stamped_version" ]]; then
      RELEASE_VERSION="$stamped_version" ./gradlew -q :runtime-cli:installDist :runtime-mcp:installDist
    else
      ./gradlew -q :runtime-cli:installDist :runtime-mcp:installDist
    fi
  ) || return 1
  locate_packaged_runtime_bin "$RUNTIME_CLI_BUILD_BIN" "CLI" || return 1
  locate_packaged_runtime_bin "$RUNTIME_MCP_BUILD_BIN" "MCP" || return 1
  install_packaged_runtime_distributions || return 1
  ok "Kotlin runtime distributions ready"
}

run_runtime_cli() {
  SKILL_BILL_RUNTIME_EXECUTABLE="$RUNTIME_CLI_BIN" "$RUNTIME_CLI_BIN" --home "$HOME" "$@"
}

runtime_cli_supports_selection_replay() {
  local runtime_bin="$1"
  [[ -x "$runtime_bin" ]] || return 1
  "$runtime_bin" install --help 2>/dev/null | grep -q "replay-last-selection"
}

build_selection_replay_runtime_cli() {
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    return 1
  fi

  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"
  if [[ ! -x "$gradlew" ]]; then
    return 1
  fi

  ensure_build_jvm || return 1

  info "Preparing source runtime CLI for saved selection replay..."
  (
    cd "$RUNTIME_KOTLIN_DIR"
    ./gradlew -q :runtime-cli:installDist
  )
}

# The already-installed runtime CLI (RUNTIME_CLI_BIN) is a leftover from whatever
# release is currently on disk, but replay validates the platform-pack manifests
# from the release being installed NOW ($PLATFORM_PACKS_DIR, already bootstrapped
# to the new bundle by this point). When a release bumps the platform-pack shell
# contract version, the old installed CLI rejects the new manifests before it ever
# gets replaced. Fetch the matching prebuilt CLI for THIS release into the scratch
# work dir (never the durable install dir, so a failed/offline fetch never disturbs
# the existing install) and prefer it over the stale installed binary.
REPLAY_RUNTIME_CLI_BIN=""
REPLAY_RUNTIME_CLI_FETCH_ATTEMPTED=0
fetch_prebuilt_runtime_cli_for_replay() {
  if [[ "$REPLAY_RUNTIME_CLI_FETCH_ATTEMPTED" -eq 1 ]]; then
    [[ -n "$REPLAY_RUNTIME_CLI_BIN" && -x "$REPLAY_RUNTIME_CLI_BIN" ]]
    return $?
  fi
  REPLAY_RUNTIME_CLI_FETCH_ATTEMPTED=1

  [[ "$INSTALL_SOURCE" == "source" ]] && return 1
  check_prebuilt_dependencies >/dev/null 2>&1 || return 1
  resolve_release_assets || return 1

  local archive src bin_path
  archive="$(fetch_release_asset "$RESOLVED_RUNTIME_CLI_ASSET")" || return 1
  verify_sha256 "$archive" || return 1
  src="$(unpack_runtime_image "$archive" "runtime-cli" "$(prebuilt_work_dir)/extract-cli-replay")" || return 1
  bin_path="$src/bin/runtime-cli"
  [[ -x "$bin_path" ]] || return 1
  REPLAY_RUNTIME_CLI_BIN="$bin_path"
}

run_selection_runtime_cli() {
  local runtime_bin=""
  if runtime_cli_supports_selection_replay "$RUNTIME_CLI_BUILD_BIN"; then
    runtime_bin="$RUNTIME_CLI_BUILD_BIN"
  elif fetch_prebuilt_runtime_cli_for_replay 1>&2 && runtime_cli_supports_selection_replay "$REPLAY_RUNTIME_CLI_BIN"; then
    runtime_bin="$REPLAY_RUNTIME_CLI_BIN"
  elif runtime_cli_supports_selection_replay "$RUNTIME_CLI_BIN"; then
    runtime_bin="$RUNTIME_CLI_BIN"
  elif build_selection_replay_runtime_cli 1>&2 && runtime_cli_supports_selection_replay "$RUNTIME_CLI_BUILD_BIN"; then
    runtime_bin="$RUNTIME_CLI_BUILD_BIN"
  else
    err "Cannot reuse saved install selections: no Skill Bill runtime CLI is available before cleanup."
    err "The runtime CLI must support 'install replay-last-selection'."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  SKILL_BILL_RUNTIME_EXECUTABLE="$runtime_bin" "$runtime_bin" --home "$HOME" "$@"
}

current_telemetry_level_from_config() {
  local output
  local config_path
  local telemetry_level

  if ! output="$(run_selection_runtime_cli telemetry status 2>/dev/null)"; then
    return 2
  fi

  config_path="$(printf '%s\n' "$output" | awk -F': ' '$1 == "config_path" { print $2; exit }')"
  telemetry_level="$(printf '%s\n' "$output" | awk -F': ' '$1 == "telemetry_level" { print $2; exit }')"
  case "$telemetry_level" in
    anonymous|full|off)
      ;;
    *)
      return 2
      ;;
  esac
  [[ -n "$config_path" ]] || return 2
  [[ -f "$config_path" ]] || return 1
  printf '%s\n' "$telemetry_level"
}

path_contains_dir() {
  local candidate="$1"
  case ":${PATH:-}:" in
    *":$candidate:"*) return 0 ;;
    *) return 1 ;;
  esac
}

install_runtime_launcher() {
  local name="$1"
  local target="$2"
  local link_path="$RUNTIME_LAUNCHER_BIN_DIR/$name"

  if [[ -e "$link_path" && ! -L "$link_path" ]]; then
    warn "  skipped $link_path (exists and is not a symlink)"
    return 0
  fi

  ln -sfn "$target" "$link_path"
  ok "  linked $name → $target"
}

install_skill_bill_launcher() {
  local launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/skill-bill"
  local marker="# skill-bill managed launcher"

  if [[ -e "$launcher_path" && ! -L "$launcher_path" ]]; then
    if ! grep -qF "$marker" "$launcher_path" 2>/dev/null; then
      warn "  skipped $launcher_path (exists and is not a Skill Bill managed launcher)"
      return 0
    fi
  fi

  rm -f "$launcher_path"
  cat > "$launcher_path" <<LAUNCHER
#!/usr/bin/env bash
$marker
set -euo pipefail

runtime_cli="$RUNTIME_CLI_BIN"
installer_url="https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh"

shell_quote() {
  case "\$1" in
    (*[!A-Za-z0-9_./:=@%+-]*|'')
      printf "'%s'" "\$(printf '%s' "\$1" | sed "s/'/'\\\\''/g")"
      ;;
    (*)
      printf '%s' "\$1"
      ;;
  esac
}

if [[ "\${1:-}" == "update" ]]; then
  shift
  installer_args=(--reuse-last-selection)
  dry_run=0
  format=text
  release_selected=0
  passthrough=()
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      --dry-run)
        dry_run=1
        passthrough+=("\$1")
        shift
        ;;
      --format)
        format="\${2:-text}"
        passthrough+=("\$1" "\${2:-}")
        shift 2
        ;;
      --format=*)
        format="\${1#--format=}"
        passthrough+=("\$1")
        shift
        ;;
      *)
        if [[ "\$1" == "--release" || "\$1" == --release=* ]]; then
          release_selected=1
        fi
        installer_args+=("\$1")
        passthrough+=("\$1")
        shift
        ;;
    esac
  done

  command="curl -fsSL \$installer_url | bash -s --"
  for arg in "\${installer_args[@]}"; do
    command+=" \$(shell_quote "\$arg")"
  done

  if [[ "\$dry_run" -eq 1 ]]; then
    exec "\$runtime_cli" update "\${passthrough[@]+\${passthrough[@]}}"
  fi

  if [[ "\$release_selected" -eq 0 ]]; then
    check_output="\$("\$runtime_cli" update-check 2>&1)"
    check_status="\$(printf '%s\n' "\$check_output" | awk -F': ' '/^status:/{print \$2; exit}')"
    if [[ "\$check_status" != "update_available" ]]; then
      exec "\$runtime_cli" update "\${passthrough[@]+\${passthrough[@]}}"
    fi
  fi

  exec bash -c "\$command"
fi

exec "\$runtime_cli" "\$@"
LAUNCHER
  chmod +x "$launcher_path"
  ok "  installed skill-bill launcher → $RUNTIME_CLI_BIN"
}

install_runtime_launchers() {
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  info "Installing runtime launchers to: $RUNTIME_LAUNCHER_BIN_DIR"
  install_skill_bill_launcher
  install_runtime_launcher "skill-bill-mcp" "$RUNTIME_MCP_BIN"

  if path_contains_dir "$RUNTIME_LAUNCHER_BIN_DIR"; then
    ok "  launcher directory is on PATH"
  else
    warn "  launcher directory is not on PATH: $RUNTIME_LAUNCHER_BIN_DIR"
    warn "  set SKILL_BILL_BIN_DIR to a PATH directory before running ./install.sh, or add this directory to PATH"
  fi
}















get_agent_path() {
  run_runtime_cli install agent-path "$1"
}

trim_string() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

prompt_input_available() {
  { : < /dev/tty; } 2>/dev/null
}

read_prompt_input() {
  local target_var="$1"
  if prompt_input_available; then
    IFS= read -r "$target_var" < /dev/tty
  else
    IFS= read -r "$target_var"
  fi
}

normalize_platform_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_/-'
}

normalize_agent_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]'
}

array_contains() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    if [[ "$item" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

add_agent_selection() {
  local agent="$1"
  if ! array_contains "$agent" "${AGENT_NAMES[@]:-}"; then
    AGENT_NAMES+=("$agent")
    AGENT_PATHS+=("$(get_agent_path "$agent")")
  fi
}

apply_default_agent_selection() {
  local detected_output
  local supported_agent

  detected_output="$(run_runtime_cli install detect-agents 2>/dev/null || true)"
  if [[ -n "$(trim_string "$detected_output")" ]]; then
    AGENT_SELECTION_MODE="detected"
    AGENT_NAMES=()
    AGENT_PATHS=()
    info "No agents entered; using detected configured agents."
    return 0
  fi

  info "No configured agents detected; defaulting to every supported agent."
  AGENT_SELECTION_MODE="manual"
  AGENT_NAMES=()
  AGENT_PATHS=()
  for supported_agent in "${SUPPORTED_AGENTS[@]:-}"; do
    add_agent_selection "$supported_agent"
  done
}

resolve_agent_selection() {
  local token="$1"
  local normalized
  local index
  local all_index
  local agent

  token="$(trim_string "$token")"
  [[ -n "$token" ]] || return 1

  if [[ "$token" =~ ^[0-9]+$ ]]; then
    index=$((token - 1))
    if (( index >= 0 && index < ${#SUPPORTED_AGENTS[@]} )); then
      printf '%s\n' "${SUPPORTED_AGENTS[$index]}"
      return 0
    fi
    all_index=${#SUPPORTED_AGENTS[@]}
    if (( index == all_index )); then
      printf '__all__\n'
      return 0
    fi
    return 1
  fi

  normalized="$(normalize_agent_token "$token")"
  if [[ "$normalized" == "all" ]]; then
    printf '__all__\n'
    return 0
  fi

  for agent in "${SUPPORTED_AGENTS[@]}"; do
    if [[ "$normalized" == "$agent" ]]; then
      printf '%s\n' "$agent"
      return 0
    fi
  done

  return 1
}

format_agent_list() {
  local result=""
  local agent

  for agent in "$@"; do
    if [[ -z "$result" ]]; then
      result="$agent"
    else
      result="$result, $agent"
    fi
  done

  printf '%s' "$result"
}

prompt_for_agent_mode() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose agent selection mode."
    printf "  1. manual - choose one or more supported agents\n"
    printf "  2. detected - let the runtime detect configured agents from your home directory\n"
    printf "${CYAN}▸${NC} Enter agent mode [1]: "
    if ! read_prompt_input input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|1|manual)
        AGENT_SELECTION_MODE="manual"
        return 0
        ;;
      2|detected|auto)
        AGENT_SELECTION_MODE="detected"
        AGENT_NAMES=()
        AGENT_PATHS=()
        return 0
        ;;
      *)
        warn "Enter 1, 2, manual, detected, or press Enter for manual."
        ;;
    esac
  done
}

prompt_for_manual_agent_selection() {
  local input
  local raw_tokens=()
  local invalid_tokens=()
  local token
  local resolved
  local i
  local option_number
  local supported_agent

  while true; do
    echo ""
    info "Available agents:"
    for i in "${!SUPPORTED_AGENTS[@]}"; do
      printf "  %s. %s\n" "$((i + 1))" "${SUPPORTED_AGENTS[$i]}"
    done
    option_number=$(( ${#SUPPORTED_AGENTS[@]} + 1 ))
    printf "  %s. all (install to every supported agent)\n" "$option_number"
    info "Choose one or more agents (comma-separated)."
    printf "${CYAN}▸${NC} Enter agents [detected/all]: "
    if ! read_prompt_input input; then
      input=""
    fi

    if [[ -z "$(trim_string "$input")" ]]; then
      apply_default_agent_selection
      return 0
    fi

    AGENT_NAMES=()
    AGENT_PATHS=()
    invalid_tokens=()
    IFS=',' read -ra raw_tokens <<< "$input"

    for token in "${raw_tokens[@]:-}"; do
      token="$(trim_string "$token")"
      [[ -z "$token" ]] && continue
      resolved="$(resolve_agent_selection "$token" 2>/dev/null || true)"
      if [[ -z "$resolved" ]]; then
        invalid_tokens+=("$token")
        continue
      fi
      if [[ "$resolved" == "__all__" ]]; then
        for supported_agent in "${SUPPORTED_AGENTS[@]:-}"; do
          add_agent_selection "$supported_agent"
        done
        continue
      fi
      add_agent_selection "$resolved"
    done

    if [[ ${#invalid_tokens[@]} -gt 0 ]]; then
      warn "Unknown agent selection: $(printf '%s, ' "${invalid_tokens[@]}" | sed 's/, $//')"
      continue
    fi

    if [[ ${#AGENT_NAMES[@]} -eq 0 ]]; then
      warn "No valid agents selected. Choose at least one agent."
      continue
    fi

    return 0
  done
}

prompt_for_agent_selection() {
  prompt_for_agent_mode
  if [[ "$AGENT_SELECTION_MODE" == "manual" ]]; then
    prompt_for_manual_agent_selection
  fi
}

display_platform_name() {
  local label="${1//-/ }"
  printf '%s' "$label"
}

build_platform_packages() {
  local pack_dir
  local package
  local manifest

  PLATFORM_PACKAGES=()
  if [[ ! -d "$PLATFORM_PACKS_DIR" ]]; then
    return 0
  fi

  while IFS= read -r pack_dir; do
    package="$(basename "$pack_dir")"
    manifest="$pack_dir/platform.yaml"
    # Fallback packs are installed with the horizontal review base. Packs with a required
    # baseline layer are selected transitively with that baseline, so neither is a direct choice.
    if grep -q '^fallback_capabilities:' "$manifest"; then
      continue
    fi
    if grep -q '^  baseline_layers:' "$manifest" && grep -q '^      required: true' "$manifest"; then
      continue
    fi
    if [[ ${#PLATFORM_PACKAGES[@]} -eq 0 ]] || ! array_contains "$package" "${PLATFORM_PACKAGES[@]}"; then
      PLATFORM_PACKAGES+=("$package")
    fi
  done < <(find "$PLATFORM_PACKS_DIR" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/platform.yaml' ';' -print | sort)
}

resolve_platform_selection() {
  local token="$1"
  local normalized
  local package
  local index
  local all_index
  local none_index

  token="$(trim_string "$token")"
  [[ -n "$token" ]] || return 1

  if [[ "$token" =~ ^[0-9]+$ ]]; then
    index=$((token - 1))
    if (( index >= 0 && index < ${#PLATFORM_PACKAGES[@]} )); then
      printf '%s\n' "${PLATFORM_PACKAGES[$index]}"
      return 0
    fi
    all_index=${#PLATFORM_PACKAGES[@]}
    none_index=$(( ${#PLATFORM_PACKAGES[@]} + 1 ))
    if (( index == all_index )); then
      printf '__all__\n'
      return 0
    fi
    if (( index == none_index )); then
      printf '__none__\n'
      return 0
    fi
    return 1
  fi

  normalized="$(normalize_platform_token "$token")"
  case "$normalized" in
    all)
      printf '__all__\n'
      return 0
      ;;
    none|base|baseskills|baseonly)
      printf '__none__\n'
      return 0
      ;;
  esac

  for package in "${PLATFORM_PACKAGES[@]}"; do
    if [[ "$normalized" == "$(normalize_platform_token "$package")" ]]; then
      printf '%s\n' "$package"
      return 0
    fi
    if [[ "$normalized" == "$(normalize_platform_token "$(display_platform_name "$package")")" ]]; then
      printf '%s\n' "$package"
      return 0
    fi
  done

  return 1
}

format_platform_list() {
  local result=""
  local package
  local label

  for package in "$@"; do
    label="$(display_platform_name "$package")"
    if [[ -z "$result" ]]; then
      result="$label"
    else
      result="$result, $label"
    fi
  done

  printf '%s' "$result"
}

prompt_for_platform_selection() {
  local input
  local i
  local option_number
  local none_option_number
  local package
  local token
  local resolved
  local invalid_tokens=()
  local raw_tokens=()

  if [[ ${#PLATFORM_PACKAGES[@]} -eq 0 ]]; then
    PLATFORM_SELECTION_MODE="none"
    SELECTED_PLATFORM_PACKAGES=()
    return 0
  fi

  while true; do
    echo ""
    info "Available optional platforms:"
    for i in "${!PLATFORM_PACKAGES[@]}"; do
      package="${PLATFORM_PACKAGES[$i]}"
      printf "  %s. %s (%s)\n" "$((i + 1))" "$(display_platform_name "$package")" "$package"
    done
    option_number=$(( ${#PLATFORM_PACKAGES[@]} + 1 ))
    none_option_number=$(( ${#PLATFORM_PACKAGES[@]} + 2 ))
    printf "  %s. all (install every platform pack)\n" "$option_number"
    printf "  %s. base only (skip optional platform packs)\n" "$none_option_number"
    info "Base skills and the manifest-declared generic review fallback are always installed."
    info "Required composed packs are installed with their selectable baseline pack."
    info "Optional platform packs are resolved by the runtime from platform-packs/ manifests."
    info "Choose one or more optional platform numbers (comma-separated). Names still work if you prefer them."
    printf "${CYAN}▸${NC} Enter platforms [base only] (e.g. 1,3 or %s): " "$option_number"
    if ! read_prompt_input input; then
      input=""
    fi

    if [[ -z "$(trim_string "$input")" ]]; then
      PLATFORM_SELECTION_MODE="none"
      SELECTED_PLATFORM_PACKAGES=()
      info "No platforms entered; installing base skills only."
      return 0
    fi

    PLATFORM_SELECTION_MODE="selected"
    SELECTED_PLATFORM_PACKAGES=()
    invalid_tokens=()
    IFS=',' read -ra raw_tokens <<< "$input"

    for token in "${raw_tokens[@]}"; do
      token="$(trim_string "$token")"
      [[ -z "$token" ]] && continue
      resolved="$(resolve_platform_selection "$token" 2>/dev/null || true)"
      if [[ -z "$resolved" ]]; then
        invalid_tokens+=("$token")
        continue
      fi
      if [[ "$resolved" == "__all__" ]]; then
        PLATFORM_SELECTION_MODE="all"
        SELECTED_PLATFORM_PACKAGES=("${PLATFORM_PACKAGES[@]}")
        break
      fi
      if [[ "$resolved" == "__none__" ]]; then
        PLATFORM_SELECTION_MODE="none"
        SELECTED_PLATFORM_PACKAGES=()
        break
      fi
      if [[ ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]] || ! array_contains "$resolved" "${SELECTED_PLATFORM_PACKAGES[@]}"; then
        SELECTED_PLATFORM_PACKAGES+=("$resolved")
      fi
    done

    if [[ ${#invalid_tokens[@]} -gt 0 ]]; then
      warn "Unknown platform selection: $(printf '%s, ' "${invalid_tokens[@]}" | sed 's/, $//')"
      continue
    fi

    if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]]; then
      warn "No valid platforms selected. Choose a platform, all, or base only."
      continue
    fi

    return 0
  done
}

prompt_for_telemetry_preference() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose a telemetry level. You can change it later with the Skill Bill telemetry command."
    printf "  1. anonymous (default) - aggregate counts, no content\n"
    printf "  2. full - includes finding details, learnings, rejection notes\n"
    printf "  3. off - no telemetry\n"
    printf "${CYAN}▸${NC} Enter telemetry level [1]: "
    if ! read_prompt_input input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|1|anonymous)
        TELEMETRY_LEVEL="anonymous"
        return 0
        ;;
      2|full)
        TELEMETRY_LEVEL="full"
        return 0
        ;;
      3|off)
        TELEMETRY_LEVEL="off"
        return 0
        ;;
      *)
        warn "Enter 1, 2, 3, anonymous, full, off, or press Enter for the default."
        ;;
    esac
  done
}

replay_last_install_selection() {
  local output
  local error_output
  local stderr_file
  local kind
  local value
  local extra

  stderr_file="$(mktemp)"
  if ! output="$(
    run_selection_runtime_cli install replay-last-selection \
      --skills "$SKILLS_DIR" \
      --platform-packs "$PLATFORM_PACKS_DIR" 2>"$stderr_file"
  )"; then
    err "Cannot reuse saved install selections."
    if [[ -n "$(trim_string "$output")" ]]; then
      err "$(trim_string "$output")"
    fi
    error_output="$(trim_string "$(cat "$stderr_file")")"
    rm -f "$stderr_file"
    if [[ -n "$error_output" ]]; then
      err "$error_output"
    fi
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  rm -f "$stderr_file"

  AGENT_SELECTION_MODE="manual"
  AGENT_NAMES=()
  AGENT_PATHS=()
  PLATFORM_SELECTION_MODE="none"
  SELECTED_PLATFORM_PACKAGES=()
  TELEMETRY_LEVEL="anonymous"
  MCP_REGISTRATION="register"

  while IFS=$'\t' read -r kind value extra; do
    [[ -z "${kind:-}" ]] && continue
    case "$kind" in
      agent)
        if [[ -z "${value:-}" || -z "${extra:-}" ]]; then
          err "Cannot reuse saved install selections: malformed replay agent entry."
          exit 1
        fi
        AGENT_NAMES+=("$value")
        AGENT_PATHS+=("$extra")
        ;;
      platform-mode)
        case "$value" in
          none|selected|all)
            PLATFORM_SELECTION_MODE="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown platform mode '$value'."
            exit 1
            ;;
        esac
        ;;
      platform)
        if [[ -z "${value:-}" ]]; then
          err "Cannot reuse saved install selections: malformed replay platform entry."
          exit 1
        fi
        SELECTED_PLATFORM_PACKAGES+=("$value")
        ;;
      telemetry)
        case "$value" in
          anonymous|full|off)
            TELEMETRY_LEVEL="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown telemetry level '$value'."
            exit 1
            ;;
        esac
        ;;
      mcp)
        case "$value" in
          register|skip)
            MCP_REGISTRATION="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown MCP registration choice '$value'."
            exit 1
            ;;
        esac
        ;;
      *)
        err "Cannot reuse saved install selections: unknown replay field '$kind'."
        exit 1
        ;;
    esac
  done <<< "$output"

  if value="$(current_telemetry_level_from_config)"; then
    if [[ "$value" != "$TELEMETRY_LEVEL" ]]; then
      info "Preserving current telemetry config level '$value' instead of saved install selection '$TELEMETRY_LEVEL'."
    fi
    TELEMETRY_LEVEL="$value"
  else
    telemetry_config_status=$?
    if [[ "$telemetry_config_status" -ne 1 ]]; then
      err "Cannot reuse saved install selections: current telemetry configuration could not be read or validated."
      exit 1
    fi
  fi

  if [[ ${#AGENT_NAMES[@]} -eq 0 ]]; then
    err "Cannot reuse saved install selections: saved selection has no agents."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]]; then
    err "Cannot reuse saved install selections: selected platform mode has no saved platform slugs."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  if [[ "$PLATFORM_SELECTION_MODE" == "all" ]]; then
    SELECTED_PLATFORM_PACKAGES=("${PLATFORM_PACKAGES[@]}")
  fi

  ok "Reusing latest successful install selections from $SKILL_BILL_STATE_DIR/install-selection.json"
}

build_runtime_install_args() {
  local i

  RUNTIME_INSTALL_ARGS=(
    install
    apply
    --repo-root "$SKILL_BILL_STATE_DIR"
    --skills "$SKILL_BILL_STATE_DIR/skills"
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs"
    --agent-mode "$AGENT_SELECTION_MODE"
    --platform-mode "$PLATFORM_SELECTION_MODE"
    --telemetry "$TELEMETRY_LEVEL"
    --mcp "$MCP_REGISTRATION"
    --replace-existing-skill-bill-links
    --runtime-install-root "$RUNTIME_INSTALL_ROOT"
    --runtime-cli-build-dir "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli"
    --runtime-mcp-build-dir "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp"
    --runtime-cli-install-dir "$RUNTIME_CLI_INSTALL_DIR"
    --runtime-mcp-install-dir "$RUNTIME_MCP_INSTALL_DIR"
    --runtime-launcher-bin-dir "$RUNTIME_LAUNCHER_BIN_DIR"
    --runtime-mcp-bin "$RUNTIME_MCP_BIN"
  )

  if [[ "$AGENT_SELECTION_MODE" == "manual" && ${#AGENT_NAMES[@]} -gt 0 ]]; then
    for i in "${!AGENT_NAMES[@]}"; do
      RUNTIME_INSTALL_ARGS+=(--agent "${AGENT_NAMES[$i]}")
      # claude pins no single --agent-target so the runtime fans skills across every discovered
      # config root (~/.claude plus ~/.claude-<name> profiles); other agents keep their single path.
      if [[ "${AGENT_NAMES[$i]}" != "claude" ]]; then
        RUNTIME_INSTALL_ARGS+=(--agent-target "${AGENT_NAMES[$i]}=${AGENT_PATHS[$i]}")
      fi
    done
  fi

  if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -gt 0 ]]; then
    for i in "${!SELECTED_PLATFORM_PACKAGES[@]}"; do
      RUNTIME_INSTALL_ARGS+=(--platform "${SELECTED_PLATFORM_PACKAGES[$i]}")
    done
  fi
}

apply_runtime_install() {
  local status=0

  info "Applying install through the runtime plan/apply path."
  run_runtime_cli "${RUNTIME_INSTALL_ARGS[@]}" || status=$?
  if [[ "$status" -ne 0 ]]; then
    err "Runtime install apply failed."
    return "$status"
  fi
  ok "Runtime install apply completed"
}

apply_external_addon_overlay() {
  if [[ ! -d "$SKILL_BILL_STATE_DIR/platform-packs" ]]; then
    return 0
  fi
  info "Applying external addon overlay onto installed platform packs."
  local status=0
  run_runtime_cli install apply-external-addons \
    --repo-root "$SKILL_BILL_STATE_DIR" \
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs" || status=$?
  if [[ "$status" -ne 0 ]]; then
    err "External addon overlay failed; aborting the install."
    return "$status"
  fi
  ok "External addon overlay completed"
}

print_codex_roots_summary() {
  local roots root
  roots="$(run_runtime_cli install codex-roots 2>/dev/null)" || return 0
  [[ -z "$roots" ]] && return 0
  while IFS= read -r root; do
    [[ -z "$root" ]] && continue
    info "Codex config root: $root"
  done <<< "$roots"
}

print_claude_roots_summary() {
  local roots root
  roots="$(run_runtime_cli install claude-roots 2>/dev/null)" || return 0
  [[ -z "$roots" ]] && return 0
  while IFS= read -r root; do
    [[ -z "$root" ]] && continue
    info "Claude config root: $root"
  done <<< "$roots"
}

print_postinstall_path_warning() {
  if path_contains_dir "$RUNTIME_LAUNCHER_BIN_DIR"; then
    return 0
  fi
  local rc_file
  case "${SHELL:-}" in
    */fish) rc_file="${HOME}/.config/fish/config.fish" ;;
    */zsh)  rc_file="${HOME}/.zshrc" ;;
    *)      rc_file="${HOME}/.bashrc" ;;
  esac
  warn "Launcher directory is not on PATH: $RUNTIME_LAUNCHER_BIN_DIR"
  case "${SHELL:-}" in
    */fish)
      warn "  Add it permanently (copy-paste, or add to $rc_file):"
      warn "    fish_add_path $RUNTIME_LAUNCHER_BIN_DIR"
      ;;
    *)
      warn "  Add it for the current session (copy-paste):"
      warn "    export PATH=\"$RUNTIME_LAUNCHER_BIN_DIR:\$PATH\""
      warn "  Or add that line to $rc_file, then: source $rc_file"
      ;;
  esac
}

print_postinstall_agent_warning() {
  [[ "$AGENT_SELECTION_MODE" != "detected" ]] && return 0
  local detected
  detected="$(run_runtime_cli install detect-agents 2>/dev/null)" || return 0
  [[ -n "$detected" ]] && return 0
  warn "No supported agents were detected — skills are not linked to any agent."
  warn "  Install a supported agent (claude, codex, junie, or cursor),"
  warn "  then re-run ./install.sh. Choose 'manual' to select your agent explicitly."
}

selected_agent_label() {
  if [[ "$AGENT_SELECTION_MODE" == "detected" ]]; then
    printf 'runtime detection'
    return 0
  fi
  format_agent_list "${AGENT_NAMES[@]}"
}

selected_platform_label() {
  case "$PLATFORM_SELECTION_MODE" in
    all)
      printf 'all'
      ;;
    none)
      printf 'base only'
      ;;
    *)
      format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}"
      ;;
  esac
}



run_full_install() {
  bundle_bootstrap_if_needed
  print_install_plan
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    build_platform_packages
    replay_last_install_selection
  fi
  migrate_legacy_config_to_durable_path
  install_runtime_distributions || return 1
  copy_in_authored_source
  reconcile_and_commit_authored_source_with_recovery
  if [[ "$REUSE_LAST_SELECTION" -ne 1 ]]; then
    build_platform_packages
  fi

  echo ""
  printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
  echo ""
  info "Supported agents: claude, codex, junie, cursor"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Install behavior: reuse saved choices, then delegate planning and apply to the Kotlin runtime."
  else
    info "Install behavior: collect choices, then delegate planning and apply to the Kotlin runtime."
    prompt_for_agent_selection
    prompt_for_platform_selection
    prompt_for_telemetry_preference
  fi
  install_runtime_launchers
  build_runtime_install_args

  echo ""
  SELECTED_PLATFORM_LABEL="$(selected_platform_label)"
  info "Plugin:         $PLUGIN_DIR"
  info "Agents:         $(selected_agent_label)"
  info "Platforms:      $SELECTED_PLATFORM_LABEL"
  info "Telemetry:      $TELEMETRY_LEVEL"
  info "MCP:            $MCP_REGISTRATION"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Selections:     reused latest successful install selection"
  fi
  echo ""

  apply_external_addon_overlay
  apply_runtime_install

  printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
  echo ""
  info "Source of truth: $PLUGIN_DIR/skills/"
  info "Staging cache:   $SKILL_BILL_STATE_DIR/installed-skills"
  info "Platforms:       $SELECTED_PLATFORM_LABEL"
  info "Launchers:       $RUNTIME_LAUNCHER_BIN_DIR/skill-bill, $RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
  info "Telemetry:       $TELEMETRY_LEVEL"
  info "MCP:             $MCP_REGISTRATION"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Selections:      reused latest successful install selection"
  fi
  if [[ "$AGENT_SELECTION_MODE" == "manual" && ${#AGENT_NAMES[@]} -gt 0 ]]; then
    for i in "${!AGENT_NAMES[@]}"; do
      info "Installed agent: ${AGENT_NAMES[$i]} → ${AGENT_PATHS[$i]}"
    done
  else
    info "Installed agents were resolved by runtime detection."
  fi

  print_claude_roots_summary
  print_codex_roots_summary

  print_postinstall_path_warning
  print_postinstall_agent_warning

  echo ""
  info "Edit skills in: $PLUGIN_DIR/skills/"
  if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
    info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
  fi
  info "Run './install.sh' again to reinstall with different agent, platform, telemetry, or MCP choices."
  info "Next step:       open your agent and run /skill-bill"
  echo ""
}

parse_args "$@"
bootstrap_release_installer_if_needed
resolve_install_source
run_full_install
