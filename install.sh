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
DESKTOP_APP_INSTALL_RESULT="not installed"
RECOVERY_DESKTOP_APP_PATH=""
RECOVERY_RUNTIME_ROOT=""
CANDIDATE_RUNTIME_ACTIVE=0
MIGRATION_MAY_HAVE_COMMITTED=0
INSTALL_PUBLICATION_COMPLETE=0
RECOVERY_DESKTOP_APP_REPORTED=0
RECOVERY_RUNTIME_REPORTED=0
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

declare -a SUPPORTED_AGENTS=(claude codex junie cursor opencode devin)
declare -a AGENT_NAMES=()
declare -a AGENT_PATHS=()
declare -a RUNTIME_INSTALL_ARGS=()

AGENT_SELECTION_MODE="manual"
TELEMETRY_LEVEL="anonymous"
MCP_REGISTRATION="register"

usage() {
  cat <<USAGE
Usage: ./install.sh [--from-source] [--release TAG] [--reuse-last-selection]

By default a local checkout builds and installs from source. A standalone
downloaded installer uses checksum-verified prebuilt release images.
The desktop app builds from source for local checkouts and downloads a matching
release build for standalone installs. Desktop installation is required.
Both paths install a matched runtime and desktop pair: the installer stages
both builds and replaces nothing when their build identities differ.
The desktop starts only when the installed runtime has the same build identity.

Options:
  --from-source            Force building the runtime from source with Gradle.
                           Ignores --release. Requires a JDK.
  --release TAG            Install a specific release tag instead of the latest
                           published runtime release. Ignored with --from-source.
  --reuse-last-selection   Reuse the latest successful agent, telemetry,
                           and MCP choices from ~/.skill-bill. All platform
                           packs are installed on every run.
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
      --install-desktop-app)
        shift
        ;;
      --no-desktop-app)
        err "Desktop installation is required; --no-desktop-app is no longer supported."
        exit 1
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

# Resolve the newest runtime release tag, including prereleases.
# The API lists recent releases first. Retired plugin-v* / extension-v* releases
# remain on GitHub, so skip them and use the first versioned runtime tag.
resolve_latest_runtime_release_tag() {
  local api_url json tags tag best
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
  while IFS= read -r tag; do
    [[ -n "$tag" ]] || continue
    if [[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
      best="$tag"
      break
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
  if [[ -n "${STAGED_RUNTIME_ROOT:-}" && -d "$STAGED_RUNTIME_ROOT" ]]; then
    rm -rf "$STAGED_RUNTIME_ROOT"
  fi
  if [[ -n "${RECOVERY_RUNTIME_ROOT:-}" && -d "$RECOVERY_RUNTIME_ROOT" ]]; then
    if [[ "${MIGRATION_MAY_HAVE_COMMITTED:-0}" -eq 1 && "${INSTALL_PUBLICATION_COMPLETE:-0}" -eq 0 ]]; then
      if [[ "${RECOVERY_RUNTIME_REPORTED:-0}" -eq 0 ]]; then
        report_upgrade_recovery "Installation stopped after migration began."
      fi
    elif [[ "${INSTALL_PUBLICATION_COMPLETE:-0}" -eq 0 ]]; then
      if ! rm -rf "$RECOVERY_RUNTIME_ROOT"; then
        err "Could not remove the unused runtime recovery copy: $RECOVERY_RUNTIME_ROOT"
      fi
    fi
  fi
  if [[ -n "${RECOVERY_DESKTOP_APP_PATH:-}" && ( -e "$RECOVERY_DESKTOP_APP_PATH" || -L "$RECOVERY_DESKTOP_APP_PATH" ) ]]; then
    if [[ "${MIGRATION_MAY_HAVE_COMMITTED:-0}" -eq 1 && "${INSTALL_PUBLICATION_COMPLETE:-0}" -eq 0 ]]; then
      if [[ "${RECOVERY_DESKTOP_APP_REPORTED:-0}" -eq 0 ]]; then
        if declare -f report_upgrade_recovery >/dev/null 2>&1; then
          report_upgrade_recovery "Installation stopped after migration began."
        else
          err "Installation stopped after runtime activation. Desktop recovery copy: $RECOVERY_DESKTOP_APP_PATH"
        fi
      fi
    elif [[ "${INSTALL_PUBLICATION_COMPLETE:-0}" -eq 0 ]]; then
      if ! rm -rf "$RECOVERY_DESKTOP_APP_PATH"; then
        err "Could not remove the unused desktop recovery copy: $RECOVERY_DESKTOP_APP_PATH"
      fi
    fi
  fi
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
      # The latest-release redirect could be won by a retired plugin-v* / extension-v*
      # release that remains on GitHub, so pin the download to the same runtime tag
      # list_release_asset_names resolved.
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
# (pinned RELEASE_TAG or newest published runtime tag). Prints one name per line.
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
  if [[ "$CLEAN_INSTALL" -ne 1 ]]; then
    err "Refusing to wipe prior skill state without the explicit --clean choice."
    return 1
  fi
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
  info "Cleanup: after both runtime candidates pass parity, re-runs ./uninstall.sh while preserving copied source, durable databases, and the current desktop app."
  info "Agent symlinks: links Skill Bill skills into your selected agents' skill/command directories."
  info "Runtime: installs the Kotlin runtime under $RUNTIME_INSTALL_ROOT"
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    info "Desktop app: build and install from this checkout."
  else
    info "Desktop app: download and install the matching release build."
  fi
  info "Sona launchers: $RUNTIME_LAUNCHER_BIN_DIR/sona, $RUNTIME_LAUNCHER_BIN_DIR/sona-mcp, $RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  info "MCP registration: registers the Sona MCP server with your selected agents."
  echo ""
  info "Reverse everything with: $PLUGIN_DIR/uninstall.sh"
  echo ""
}

# One-time migration runs through the staged runtime so both candidate files
# share the runtime's config lock before cleanup can remove the legacy state.
migrate_legacy_config_to_durable_path() {
  local staged_cli="${STAGED_RUNTIME_ROOT:-}/runtime-cli.tmp/bin/runtime-cli"
  if [[ -z "${STAGED_RUNTIME_ROOT:-}" || ! -x "$staged_cli" ]]; then
    err "Installation stopped: the staged runtime cannot run the locked config migration. No config was moved."
    return 1
  fi
  local output
  if ! output="$("$staged_cli" --home "$HOME" install migrate-legacy-config 2>&1)"; then
    err "Installation stopped: the staged runtime could not migrate machine config. No cleanup ran."
    [[ -z "$output" ]] || err "$output"
    return 1
  fi
  [[ -z "$output" ]] || info "$output"
}

migrate_persisted_data() {
  local staged_cli="${STAGED_RUNTIME_ROOT:-}/runtime-cli.tmp/bin/runtime-cli"
  if [[ -z "${STAGED_RUNTIME_ROOT:-}" || ! -x "$staged_cli" ]]; then
    err "Installation stopped: the staged runtime is unavailable for data migration."
    return 1
  fi
  local output
  if ! output="$("$staged_cli" --home "$HOME" install migrate-data 2>&1)"; then
    err "Installation stopped before runtime activation: persisted data migration did not complete."
    [[ -z "$output" ]] || err "$output"
    return 1
  fi
  [[ -z "$output" ]] || info "$output"
}

# >>> retired IDE plugin cleanup (keep identical in install.sh and uninstall.sh)
# Removes copies of the retired Skill Bill IntelliJ plugin (id dev.skillbill.status) and
# VS Code extension (id skill-bill.skill-bill-status) from the user's IDEs. The helpers
# update the retired_* counters that cleanup_retired_ide_plugins declares local, through
# bash dynamic scoping. Never fails the caller.
retired_ide_plugin_jar_has_id() {
  local jar="$1" xml
  [[ -f "$jar" ]] || return 1
  # Capture first: piping into `grep -q` would SIGPIPE unzip under pipefail.
  xml="$(unzip -p "$jar" META-INF/plugin.xml 2>/dev/null || true)"
  [[ "$xml" == *"<id>dev.skillbill.status</id>"* ]]
}

retired_ide_plugin_dir_has_id() {
  local dir="$1" jar
  for jar in "$dir"/lib/*.jar; do
    [[ -f "$jar" ]] || continue
    if retired_ide_plugin_jar_has_id "$jar"; then
      return 0
    fi
  done
  return 1
}

retired_ide_plugin_note_removal() {
  local label="$1" seen
  retired_removed=$((retired_removed + 1))
  for seen in ${retired_labels[@]+"${retired_labels[@]}"}; do
    if [[ "$seen" == "$label" ]]; then
      return 0
    fi
  done
  retired_labels+=("$label")
}

retired_ide_plugin_remove() {
  local kind="$1" label="$2" path="$3"
  retired_found=$((retired_found + 1))
  if [[ -d "$path" ]]; then
    rm -rf -- "$path" 2>/dev/null || true
  else
    rm -f -- "$path" 2>/dev/null || true
  fi
  if [[ ! -e "$path" ]]; then
    ok "  removed ${kind} (${label}): ${path}"
    retired_ide_plugin_note_removal "$label"
  else
    warn "Could not remove ${path}"
  fi
  return 0
}

retired_ide_plugin_scan_root() {
  local root="$1" label="$2" candidate is_match
  for candidate in "$root"/*; do
    [[ -e "$candidate" ]] || continue
    [[ "$(dirname "$candidate")" == "$root" ]] || continue
    is_match=0
    if [[ -d "$candidate" ]]; then
      if retired_ide_plugin_dir_has_id "$candidate"; then
        is_match=1
      fi
    elif [[ -f "$candidate" && "$candidate" == *.jar ]]; then
      if retired_ide_plugin_jar_has_id "$candidate"; then
        is_match=1
      fi
    fi
    if [[ "$is_match" -eq 1 ]]; then
      retired_ide_plugin_remove "IntelliJ plugin" "$label" "$candidate"
    fi
  done
}

retired_ide_plugin_scan_vscode() {
  local root="$1" label="$2" cli="$3" entry
  local -a matches=()
  [[ -d "$root" ]] || return 0
  for entry in "$root"/skill-bill.skill-bill-status-*; do
    [[ -d "$entry" ]] || continue
    matches+=("$entry")
  done
  if [[ "${#matches[@]}" -eq 0 ]]; then
    return 0
  fi
  if command -v "$cli" >/dev/null 2>&1; then
    # </dev/null stops a headless CLI from waiting on stdin.
    "$cli" --uninstall-extension skill-bill.skill-bill-status >/dev/null 2>&1 </dev/null || true
  fi
  for entry in ${matches[@]+"${matches[@]}"}; do
    retired_ide_plugin_remove "VS Code extension" "$label" "$entry"
  done
}

cleanup_retired_ide_plugins() {
  local retired_found=0 retired_removed=0
  local -a retired_labels=()
  local -a intellij_roots=() intellij_labels=()
  local os data_home root label i
  os="$(uname -s 2>/dev/null || true)"
  case "$os" in
    Darwin|Linux) ;;
    *) return 0 ;;
  esac
  data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
  if [[ "$os" == "Darwin" ]]; then
    for root in \
      "$HOME/Library/Application Support/JetBrains/"*/plugins \
      "$HOME/Library/Application Support/Google/"AndroidStudio*/plugins; do
      [[ -d "$root" ]] || continue
      intellij_roots+=("$root")
      intellij_labels+=("$(basename "$(dirname "$root")")")
    done
  else
    # Modern layout: the product directory itself holds the plugin directories.
    for root in "$data_home/JetBrains/"* "$data_home/Google/"AndroidStudio*; do
      [[ -d "$root" ]] || continue
      intellij_roots+=("$root")
      intellij_labels+=("$(basename "$root")")
    done
    # Legacy layout: a plugins directory under the config product directory.
    for root in \
      "$HOME/.config/JetBrains/"*/plugins \
      "$HOME/.config/Google/"AndroidStudio*/plugins; do
      [[ -d "$root" ]] || continue
      intellij_roots+=("$root")
      intellij_labels+=("$(basename "$(dirname "$root")")")
    done
  fi
  if [[ "${#intellij_roots[@]}" -gt 0 ]]; then
    if command -v unzip >/dev/null 2>&1; then
      for i in "${!intellij_roots[@]}"; do
        retired_ide_plugin_scan_root "${intellij_roots[$i]}" "${intellij_labels[$i]}"
      done
    else
      warn "Skipped IntelliJ plugin cleanup: unzip is not installed."
    fi
  fi
  retired_ide_plugin_scan_vscode "$HOME/.vscode/extensions" "VS Code" code
  retired_ide_plugin_scan_vscode "$HOME/.vscode-insiders/extensions" "VS Code Insiders" code-insiders
  retired_ide_plugin_scan_vscode "$HOME/.vscode-oss/extensions" "VSCodium" codium
  retired_ide_plugin_scan_vscode "$HOME/.cursor/extensions" "Cursor" cursor
  if [[ "$retired_found" -eq 0 ]]; then
    info "No retired Skill Bill IDE plugins found."
  fi
  if [[ "$retired_removed" -gt 0 ]]; then
    for label in ${retired_labels[@]+"${retired_labels[@]}"}; do
      info "Restart ${label} if it is running to finish removing the Skill Bill plugin."
    done
    info "The Skill Bill desktop app replaces the IntelliJ plugin and VS Code extension; they are no longer supported."
  fi
  return 0
}
# <<< retired IDE plugin cleanup

# Every install starts from a clean slate: removes agent symlinks, native
# subagent symlinks, MCP registrations, runtime launchers, and clears the
# regenerated install output under ~/.skill-bill/ (the installed-skills staging
# cache). Live runtime-cli and runtime-mcp trees stay until
# activate_staged_runtime_pair retires them, so a JVM that still has those JARs
# mapped can load later classpath resources. This guarantees that generator
# changes — which the staging-cache content hash does not see — actually land
# on the next install. Runtime and user state (databases, QA workspaces,
# desktop settings, copied-in source) stays so the app can still show it after
# reinstall.
#
# Tests and dev iteration can opt out with SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1.
run_pre_install_uninstall() {
  if [[ "${SKILL_BILL_SKIP_PREINSTALL_UNINSTALL:-}" == "1" ]]; then
    warn "Skipping pre-install uninstall because SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1."
    return 0
  fi
  if [[ "${PRIOR_INSTALL_PRESENT:-unknown}" == no ]] || { [[ "${PRIOR_INSTALL_PRESENT:-unknown}" == unknown ]] && [[ ! -x "$RUNTIME_CLI_BIN" && ! -d "$SKILL_BILL_STATE_DIR" ]]; }; then
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
  # This flag limits the wipe to regenerated install output (installed-skills/,
  # the native link inventory). Live runtime trees stay until activation
  # retires them. Explicit uninstall still clears everything except databases
  # and backups.
  SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1 \
    SKILL_BILL_PRESERVE_DESKTOP_APP=1 \
    SKILL_BILL_PRESERVE_LAUNCHERS=1 \
    SKILL_BILL_STAGED_RUNTIME_CLI="$1/runtime-cli.tmp/bin/runtime-cli" \
    bash "$uninstall_script"
}

locate_packaged_runtime_bin() {
  local path="$1"
  local label="$2"
  if [[ ! -x "$path" ]]; then
    err "Missing packaged Kotlin $label runtime: $path"
    return 1
  fi
}

# SKILL-430: every runtime and desktop build carries a build-identity.properties
# record written by the shared skillbill.build-identity Gradle plugin. The
# installer stages both builds, compares their records with the same rule the
# desktop uses at startup, and replaces nothing live unless they match.
BUILD_IDENTITY_RECORD="build-identity.properties"
STAGED_RUNTIME_ROOT=""
STAGED_RUNTIME_ORIGIN=""
STAGED_DESKTOP_APP_SOURCE=""
DEFER_RUNTIME_ACTIVATION=0

read_build_identity_field() {
  local record="$1" key="$2"
  sed -n "s/^$key=//p" "$record" | tr -d '\r' | head -n1
}

# A dirty record names no exact source, so it matches only another dirty record
# of the same commit from the same install run. Clean records match on commit
# and release tag.
build_identities_match() {
  local runtime="$1" desktop="$2"
  [[ -f "$runtime" && -f "$desktop" ]] || return 1
  local runtime_commit desktop_commit runtime_dirty desktop_dirty runtime_run
  runtime_commit="$(read_build_identity_field "$runtime" commit)"
  desktop_commit="$(read_build_identity_field "$desktop" commit)"
  runtime_dirty="$(read_build_identity_field "$runtime" dirty)"
  desktop_dirty="$(read_build_identity_field "$desktop" dirty)"
  [[ -n "$runtime_commit" && "$runtime_commit" == "$desktop_commit" ]] || return 1
  [[ "$runtime_dirty" == true || "$runtime_dirty" == false ]] || return 1
  [[ "$desktop_dirty" == true || "$desktop_dirty" == false ]] || return 1
  if [[ "$runtime_dirty" == true || "$desktop_dirty" == true ]]; then
    runtime_run="$(read_build_identity_field "$runtime" install_run_id)"
    [[ "$runtime_dirty" == true && "$desktop_dirty" == true && -n "$runtime_run" &&
      "$runtime_run" == "$(read_build_identity_field "$desktop" install_run_id)" ]]
    return
  fi
  [[ "$(read_build_identity_field "$runtime" release_tag)" == "$(read_build_identity_field "$desktop" release_tag)" ]]
}

describe_build_identity() {
  local record="$1"
  if [[ ! -f "$record" ]]; then
    printf 'no build identity record (%s)' "$record"
    return 0
  fi
  local commit tag dirty run description
  commit="$(read_build_identity_field "$record" commit)"
  tag="$(read_build_identity_field "$record" release_tag)"
  dirty="$(read_build_identity_field "$record" dirty)"
  run="$(read_build_identity_field "$record" install_run_id)"
  description="commit ${commit:0:12}"
  [[ -z "$tag" ]] || description="release $tag, $description"
  if [[ "$dirty" == true && -n "$run" ]]; then
    description="$description (dirty, run $run)"
  elif [[ "$dirty" != false ]]; then
    description="$description (dirty)"
  fi
  printf '%s' "$description"
}

# Compose Desktop packages app resources under a different directory per OS.
desktop_app_build_identity_path() {
  local os="$1" app="$2"
  case "$os" in
    macos)
      printf '%s/Contents/app/resources/%s' "$app" "$BUILD_IDENTITY_RECORD"
      ;;
    windows)
      printf '%s/app/resources/%s' "$app" "$BUILD_IDENTITY_RECORD"
      ;;
    *)
      printf '%s/lib/app/resources/%s' "$app" "$BUILD_IDENTITY_RECORD"
      ;;
  esac
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
  if [[ "${DEFER_RUNTIME_ACTIVATION:-0}" == "1" ]]; then
    STAGED_RUNTIME_ROOT="$candidate_root"
    return 0
  fi
  activate_staged_runtime_pair "$candidate_root"
}

discard_staged_runtime() {
  if [[ -n "${STAGED_RUNTIME_ROOT:-}" ]]; then
    rm -rf "$STAGED_RUNTIME_ROOT"
    STAGED_RUNTIME_ROOT=""
  fi
}

# The staged runtime's record, or the live one when the test escape hatch
# staged none.
staged_runtime_build_identity_record() {
  if [[ -n "$STAGED_RUNTIME_ROOT" ]]; then
    printf '%s' "$STAGED_RUNTIME_ROOT/runtime-cli.tmp/$BUILD_IDENTITY_RECORD"
  else
    printf '%s' "$RUNTIME_CLI_INSTALL_DIR/$BUILD_IDENTITY_RECORD"
  fi
}

# The staged runtime must be the same build as the staged desktop app before
# either replaces anything live.
verify_staged_build_identities() {
  local runtime_record desktop_record
  runtime_record="$(staged_runtime_build_identity_record)"
  desktop_record="$(desktop_app_build_identity_path "$(host_os)" "$STAGED_DESKTOP_APP_SOURCE")"
  if build_identities_match "$runtime_record" "$desktop_record"; then
    info "Runtime and desktop app are the same build: $(describe_build_identity "$runtime_record")"
    return 0
  fi
  err "Installation stopped: the runtime and desktop app are not the same build. Nothing was replaced."
  err "  Runtime:     $(describe_build_identity "$runtime_record")"
  err "  Desktop app: $(describe_build_identity "$desktop_record")"
  return 1
}

# Call before discard_staged_runtime, which deletes the staged runtime record.
report_unstaged_desktop_app() {
  err "Installation stopped: the desktop app could not be staged ($DESKTOP_APP_INSTALL_RESULT). Nothing was replaced."
  err "  Runtime:     $(describe_build_identity "$(staged_runtime_build_identity_record)")"
  err "  Desktop app: not staged ($DESKTOP_APP_INSTALL_RESULT)"
}

# Move a live runtime tree aside instead of unlinking it. Open JAR file
# descriptors in running JVMs keep working after rename; rm -rf does not.
retire_live_runtime_tree() {
  local live="$1"
  if [[ ! -e "$live" && ! -L "$live" ]]; then
    return 0
  fi
  local retired_root dest
  retired_root="$(dirname "$live")/retired"
  mkdir -p "$retired_root" || return 1
  dest="$retired_root/$(basename "$live").$(date -u +%Y%m%dT%H%M%SZ)-$$"
  if [[ -e "$dest" || -L "$dest" ]]; then
    dest="${dest}.$RANDOM"
  fi
  mv "$live" "$dest" || return 1
  printf '%s' "$dest"
}

replace_live_runtime_tree() {
  local live="$1" staged="$2"
  local retired=""
  retired="$(retire_live_runtime_tree "$live")" || return 1
  if ! mv "$staged" "$live"; then
    if [[ -n "$retired" ]]; then
      if [[ -e "$live" || -L "$live" ]] || ! mv "$retired" "$live"; then
        err "Could not restore the previous runtime tree at $live. Recovery copy: $retired"
      fi
    fi
    return 1
  fi
}

prune_retired_runtime_trees() {
  local retired_root
  retired_root="$(dirname "$RUNTIME_CLI_INSTALL_DIR")/retired"
  [[ -d "$retired_root" ]] || return 0
  local prefix listing newest path
  for prefix in runtime-cli runtime-mcp; do
    listing="$(find "$retired_root" -maxdepth 1 \( -type d -o -type l \) -name "$prefix.*" 2>/dev/null | sort -r)" || true
    newest=""
    while IFS= read -r path; do
      [[ -n "$path" ]] || continue
      if [[ -z "$newest" ]]; then
        newest="$path"
        continue
      fi
      rm -rf "$path"
    done <<< "$listing"
  done
}

# Replace the live runtime-cli and runtime-mcp with a pair that
# install_packaged_runtime_pair staged and contract-checked under candidate_root.
# The previous live trees are renamed into runtime/retired/ so in-flight JVMs
# keep their mapped JARs. Only the newest retired copy of each tree is kept.
activate_staged_runtime_pair() {
  local candidate_root="$1"
  local cli_target="$candidate_root/runtime-cli" mcp_target="$candidate_root/runtime-mcp"
  if ! clean_install_state_if_requested || ! run_pre_install_uninstall "$candidate_root"; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! mkdir -p "$(dirname "$RUNTIME_CLI_INSTALL_DIR")" "$(dirname "$RUNTIME_MCP_INSTALL_DIR")"; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! replace_live_runtime_tree "$RUNTIME_CLI_INSTALL_DIR" "$cli_target.tmp"; then
    rm -rf "$candidate_root"
    return 1
  fi
  if ! replace_live_runtime_tree "$RUNTIME_MCP_INSTALL_DIR" "$mcp_target.tmp"; then
    rm -rf "$candidate_root"
    return 1
  fi
  prune_retired_runtime_trees
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
# errors in the upstream candidate (contract drift, malformed manifests) surface before
# any live tree is touched. Stale contract versions in the preserved local copy are
# tolerated because upstream always wins.
#
# The shell performs NO whole-tree rm/mv swap of skills/ — the runtime per-skill apply
# is the sole writer of the live skill dirs.
reconcile_and_commit_authored_source() {
  local report status
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
    err "Reconciliation failed; copied source and baseline were preserved. Runtime data may already have been migrated."
    discard_authored_candidates
    return 1
  fi

  # FAIL-CLOSED: a missing/unparseable summary line aborts the install rather than
  # treating an empty report as a clean run.
  if ! parse_reconcile_report "$report"; then
    err "Could not parse the reconcile machine report; copied source and baseline were preserved. Runtime data may already have been migrated."
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

install_packaged_runtime_distributions() {
  info "Installing packaged Kotlin runtime to: $RUNTIME_INSTALL_ROOT"
  install_packaged_runtime_pair \
    "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" \
    "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp" || return 1
  if [[ "${DEFER_RUNTIME_ACTIVATION:-0}" == "1" ]]; then
    ok "Kotlin runtime staged"
    return 0
  fi
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
  if [[ "${DEFER_RUNTIME_ACTIVATION:-0}" == "1" ]]; then
    STAGED_RUNTIME_ORIGIN=" from prebuilt release"
    ok "Kotlin runtime staged from prebuilt release"
    return 0
  fi
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

# The runtime source build resolves this once, in the parent shell, and the
# desktop source build reuses it, so both build identity records carry the same
# release tag. The leading v is dropped because jpackage rejects it.
source_build_release_version() {
  if [[ -z "${SOURCE_BUILD_RELEASE_VERSION+x}" ]]; then
    SOURCE_BUILD_RELEASE_VERSION="$(release_version_for_untracked_build_tree)"
    SOURCE_BUILD_RELEASE_VERSION="${SOURCE_BUILD_RELEASE_VERSION#v}"
  fi
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

  source_build_release_version
  local stamped_version="$SOURCE_BUILD_RELEASE_VERSION"

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
  "$runtime_bin" install --help 2>/dev/null | grep "replay-last-selection" >/dev/null
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
    return 1
  fi
  if [[ -L "$link_path" && "$(readlink "$link_path")" != "$target" ]]; then
    err "Refusing to replace an unrelated launcher: $link_path"
    return 1
  fi

  ln -sfn "$target" "$link_path"
  ok "  linked $name → $target"
}

install_skill_bill_launcher() {
  local name="$1"
  local launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/$name"
  local launcher_temp="${2:-$launcher_path.stage.$$}"
  local marker="# skill-bill managed launcher"

  if [[ -z "${2:-}" && -e "$launcher_path" && ! -L "$launcher_path" ]]; then
    if ! grep -qF "$marker" "$launcher_path" 2>/dev/null; then
      err "Refusing to replace an unrelated launcher: $launcher_path"
      return 1
    fi
  fi

  if [[ -z "${2:-}" && -L "$launcher_path" ]]; then
    err "Refusing to replace an unclassified launcher link: $launcher_path"
    return 1
  fi
  if [[ "$name" == "skill-bill" ]]; then
    cat > "$launcher_temp" <<LAUNCHER
#!/usr/bin/env bash
$marker
printf '%s\\n' 'skill-bill has been renamed to sona. Run sona instead.'
exit 0
LAUNCHER
  else
    cat > "$launcher_temp" <<LAUNCHER
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
  fi
  chmod +x "$launcher_temp" || { rm -f "$launcher_temp"; return 1; }
  [[ -z "${2:-}" ]] || return 0
  mv -f "$launcher_temp" "$launcher_path" || { rm -f "$launcher_temp"; return 1; }
  if [[ "$name" == "skill-bill" ]]; then
    ok "  installed $name notice launcher"
  else
    ok "  installed $name launcher → $RUNTIME_CLI_BIN"
  fi
}

install_runtime_launchers() {
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  info "Installing runtime launchers to: $RUNTIME_LAUNCHER_BIN_DIR"
  install_skill_bill_launcher "sona" || return 1
  install_skill_bill_launcher "skill-bill" || return 1
  install_runtime_launcher "sona-mcp" "$RUNTIME_MCP_BIN" || return 1
  local obsolete_mcp="$RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
  if [[ -L "$obsolete_mcp" && "$(readlink "$obsolete_mcp")" == "$RUNTIME_MCP_BIN" ]]; then
    rm -f "$obsolete_mcp" || { err "Could not remove owned legacy MCP launcher: $obsolete_mcp"; return 1; }
  elif [[ -e "$obsolete_mcp" || -L "$obsolete_mcp" ]]; then
    warn "Preserving unrelated launcher at $obsolete_mcp"
  fi

  if path_contains_dir "$RUNTIME_LAUNCHER_BIN_DIR"; then
    ok "  launcher directory is on PATH"
  else
    warn "  launcher directory is not on PATH: $RUNTIME_LAUNCHER_BIN_DIR"
    warn "  set SKILL_BILL_BIN_DIR to a PATH directory before running ./install.sh, or add this directory to PATH"
  fi
}




# Resolve and install the desktop package independently of runtime-image selection.
RESOLVED_DESKTOP_APP_ASSET=""
resolve_desktop_app_release_asset() {
  local token names name
  if ! token="$(host_token)"; then
    return 1
  fi
  if ! names="$(list_release_asset_names)"; then
    return 1
  fi

  RESOLVED_DESKTOP_APP_ASSET=""
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    case "$name" in
      skill-bill-desktop-*-"$token".tar.gz)
        RESOLVED_DESKTOP_APP_ASSET="$name"
        ;;
    esac
  done <<< "$names"
  return 0
}

desktop_host_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$path"
  else
    printf '%s' "$path"
  fi
}

desktop_app_install_root() {
  local os="$1"
  if [[ -n "${SKILL_BILL_DESKTOP_APP_DIR:-}" ]]; then
    desktop_host_path "$SKILL_BILL_DESKTOP_APP_DIR"
    return 0
  fi
  case "$os" in
    macos)
      printf '%s' "$HOME/Applications"
      ;;
    linux)
      printf '%s' "${XDG_DATA_HOME:-$HOME/.local/share}/skillbill/desktop"
      ;;
    windows)
      if [[ -n "${LOCALAPPDATA:-}" ]]; then
        desktop_host_path "$LOCALAPPDATA/SkillBill/Desktop"
      else
        printf '%s' "$HOME/AppData/Local/SkillBill/Desktop"
      fi
      ;;
    *)
      return 1
      ;;
  esac
}

desktop_app_target_path() {
  local os="$1" install_root="$2"
  case "$os" in
    macos)
      printf '%s/Sona.app' "$install_root"
      ;;
    *)
      printf '%s/Sona' "$install_root"
      ;;
  esac
}

desktop_app_executable_path() {
  local os="$1" app_target="$2"
  case "$os" in
    macos)
      printf '%s/Contents/MacOS/Sona' "$app_target"
      ;;
    windows)
      printf '%s/Sona.exe' "$app_target"
      ;;
    *)
      printf '%s/bin/Sona' "$app_target"
      ;;
  esac
}

acquire_desktop_recovery_copy() {
  local os="$1" install_root="$2" app_source="$3" candidate executable record attempt
  mkdir -p "$install_root" || {
    err "Could not create the desktop install directory for a recovery copy: $install_root"
    return 1
  }
  for attempt in {1..20}; do
    local suffix="$$.$RANDOM.$attempt"
    candidate="$install_root/.Sona.recovery.$suffix"
    [[ "$os" != "macos" ]] || candidate="$candidate.app"
    if mkdir "$candidate" 2>/dev/null; then
      RECOVERY_DESKTOP_APP_PATH="$candidate"
      break
    fi
    if [[ -e "$candidate" || -L "$candidate" ]]; then
      continue
    fi
  done
  if [[ -z "$RECOVERY_DESKTOP_APP_PATH" ]]; then
    err "Could not reserve a collision-free desktop recovery path under $install_root."
    return 1
  fi
  if ! cp -R "$app_source/." "$RECOVERY_DESKTOP_APP_PATH/"; then
    err "Could not create the desktop recovery copy: $RECOVERY_DESKTOP_APP_PATH"
    return 1
  fi
  executable="$(desktop_app_executable_path "$os" "$RECOVERY_DESKTOP_APP_PATH")"
  if [[ ! -f "$executable" || ( "$os" != windows && ! -x "$executable" ) ]]; then
    err "Desktop recovery copy has no usable app executable: $executable"
    return 1
  fi
  record="$(desktop_app_build_identity_path "$os" "$RECOVERY_DESKTOP_APP_PATH")"
  if ! build_identities_match "$(staged_runtime_build_identity_record)" "$record"; then
    err "Desktop recovery copy does not match the staged runtime: $RECOVERY_DESKTOP_APP_PATH"
    return 1
  fi
}

acquire_runtime_recovery_copy() {
  local candidate cli_source mcp_source cli_bin mcp_bin cli_record mcp_record desktop_record
  cli_source="$STAGED_RUNTIME_ROOT/runtime-cli.tmp"
  mcp_source="$STAGED_RUNTIME_ROOT/runtime-mcp.tmp"
  mkdir -p "$RUNTIME_INSTALL_ROOT" || {
    err "Could not create the runtime install directory for a recovery copy: $RUNTIME_INSTALL_ROOT"
    return 1
  }
  candidate="$RUNTIME_INSTALL_ROOT/.runtime-recovery.$$.${RANDOM}"
  if [[ -e "$candidate" || -L "$candidate" ]] || ! mkdir "$candidate"; then
    err "Could not reserve a runtime recovery path under $RUNTIME_INSTALL_ROOT."
    return 1
  fi
  RECOVERY_RUNTIME_ROOT="$candidate"
  if ! cp -R "$cli_source" "$candidate/runtime-cli" || ! cp -R "$mcp_source" "$candidate/runtime-mcp"; then
    err "Could not create the matching runtime recovery copy: $candidate"
    return 1
  fi
  cli_bin="$candidate/runtime-cli/bin/runtime-cli"
  mcp_bin="$candidate/runtime-mcp/bin/runtime-mcp"
  cli_record="$candidate/runtime-cli/$BUILD_IDENTITY_RECORD"
  mcp_record="$candidate/runtime-mcp/$BUILD_IDENTITY_RECORD"
  desktop_record="$(desktop_app_build_identity_path "$(host_os)" "$RECOVERY_DESKTOP_APP_PATH")"
  if [[ ! -x "$cli_bin" || ! -x "$mcp_bin" ]] ||
     ! build_identities_match "$cli_record" "$mcp_record" ||
     ! build_identities_match "$cli_record" "$desktop_record"; then
    err "Runtime recovery copy is unusable or does not match the desktop recovery copy: $candidate"
    return 1
  fi
}

report_upgrade_recovery() {
  local reason="${1:-}" os desktop_executable cli_record desktop_record
  RECOVERY_RUNTIME_REPORTED=1
  RECOVERY_DESKTOP_APP_REPORTED=1
  os="$(host_os)"
  cli_record="${RECOVERY_RUNTIME_ROOT:-}/runtime-cli/$BUILD_IDENTITY_RECORD"
  desktop_record="$(desktop_app_build_identity_path "$os" "${RECOVERY_DESKTOP_APP_PATH:-}")"
  if [[ -z "${RECOVERY_RUNTIME_ROOT:-}" || ! -x "$RECOVERY_RUNTIME_ROOT/runtime-cli/bin/runtime-cli" ||
        ! -x "$RECOVERY_RUNTIME_ROOT/runtime-mcp/bin/runtime-mcp" ||
        ! -d "${RECOVERY_DESKTOP_APP_PATH:-}" ]] ||
     ! build_identities_match "$cli_record" "$RECOVERY_RUNTIME_ROOT/runtime-mcp/$BUILD_IDENTITY_RECORD" ||
     ! build_identities_match "$cli_record" "$desktop_record"; then
    err "$reason Migration may have committed. Recovery artifacts could not be verified. Preserve the existing data under $SKILL_BILL_STATE_DIR and retry installation; runtime recovery path: ${RECOVERY_RUNTIME_ROOT:-unavailable}; desktop recovery path: ${RECOVERY_DESKTOP_APP_PATH:-unavailable}."
    return 1
  fi
  desktop_executable="$(desktop_app_executable_path "$os" "$RECOVERY_DESKTOP_APP_PATH")"
  err "$reason Migration may have committed. Do not start the old runtime against migrated data. Matching recovery runtime: $RECOVERY_RUNTIME_ROOT; matching desktop: $RECOVERY_DESKTOP_APP_PATH. Preserve data under $SKILL_BILL_STATE_DIR, then retry installation with: $PLUGIN_DIR/install.sh"
  err "Recovery executables: $RECOVERY_RUNTIME_ROOT/runtime-cli/bin/runtime-cli and $RECOVERY_RUNTIME_ROOT/runtime-mcp/bin/runtime-mcp; desktop executable: $desktop_executable"
}

discard_upgrade_recovery_copies() {
  if [[ -n "${RECOVERY_RUNTIME_ROOT:-}" && -d "$RECOVERY_RUNTIME_ROOT" ]]; then
    if rm -rf "$RECOVERY_RUNTIME_ROOT"; then RECOVERY_RUNTIME_ROOT=""
    else warn "Retained runtime recovery copy after successful publication: $RECOVERY_RUNTIME_ROOT"; fi
  fi
  if [[ -n "${RECOVERY_DESKTOP_APP_PATH:-}" && -d "$RECOVERY_DESKTOP_APP_PATH" ]]; then
    if rm -rf "$RECOVERY_DESKTOP_APP_PATH"; then RECOVERY_DESKTOP_APP_PATH=""
    else warn "Retained desktop recovery copy after successful publication: $RECOVERY_DESKTOP_APP_PATH"; fi
  fi
}

report_desktop_recovery_path() {
  local reason="${1:-}" os executable record
  if [[ "${MIGRATION_MAY_HAVE_COMMITTED:-0}" -eq 1 ]]; then
    report_upgrade_recovery "$reason"
    return $?
  fi
  RECOVERY_DESKTOP_APP_REPORTED=1
  os="$(host_os)"
  if [[ -z "${RECOVERY_DESKTOP_APP_PATH:-}" || ! -d "$RECOVERY_DESKTOP_APP_PATH" ]]; then
    err "$reason No retained desktop recovery copy exists; the migration remains committed and the active runtime is $RUNTIME_CLI_INSTALL_DIR."
    return 1
  fi
  executable="$(desktop_app_executable_path "$os" "$RECOVERY_DESKTOP_APP_PATH")"
  record="$(desktop_app_build_identity_path "$os" "$RECOVERY_DESKTOP_APP_PATH")"
  if [[ ! -f "$executable" || ( "$os" != windows && ! -x "$executable" ) ]] ||
     ! build_identities_match "$(staged_runtime_build_identity_record)" "$record"; then
    err "$reason Retained desktop recovery copy could not be verified at $RECOVERY_DESKTOP_APP_PATH. Migration remains committed; active runtime: $RUNTIME_CLI_INSTALL_DIR."
    return 1
  fi
  err "$reason Migration remains committed. The restored desktop app may not match the active runtime at $RUNTIME_CLI_INSTALL_DIR."
  case "$os" in
    macos) err "Open the matching recovery app bundle: $RECOVERY_DESKTOP_APP_PATH" ;;
    windows) err "Launch the matching recovery app: start \"\" \"$executable\"" ;;
    *) err "Launch the matching recovery app: \"$executable\"" ;;
  esac
}

desktop_quoted_exec_path() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}

is_owned_windows_desktop_launcher() {
  local path="$1" windows_executable="$2"
  [[ -f "$path" && ! -L "$path" ]] || return 1
  tr -d '\r' < "$path" | grep -Fxq 'REM Managed by Sona install.sh' &&
    tr -d '\r' < "$path" | grep -Fxq "\"$windows_executable\" %*"
}

check_desktop_app_target_ownership() {
  local os="$1" app_target="$2" executable="$3" windows_executable launcher
  [[ -e "$app_target" || -L "$app_target" ]] || return 0
  if [[ ! -d "$app_target" || -L "$app_target" || ! -f "$executable" || ( "$os" != windows && ! -x "$executable" ) ]]; then
    err "Refusing to replace an unclassified desktop app target: $app_target. Choose a different install root."
    return 1
  fi
  if [[ "$os" == windows ]]; then
    if command -v cygpath >/dev/null 2>&1; then windows_executable="$(cygpath -w "$executable")"; else windows_executable="${executable//\//\\}"; fi
    for launcher in "$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd" "$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"; do
      if is_owned_windows_desktop_launcher "$launcher" "$windows_executable"; then return 0; fi
    done
  else
    for launcher in "$RUNTIME_LAUNCHER_BIN_DIR/sona-app" "$RUNTIME_LAUNCHER_BIN_DIR/sona"; do
      if [[ -L "$launcher" && "$(readlink "$launcher")" == "$executable" ]]; then return 0; fi
    done
  fi
  err "Refusing to replace an unclassified desktop app target: $app_target. No Sona desktop launcher points to its executable. Choose a different install root."
  return 1
}

check_desktop_app_launcher_ownership() {
  local os="$1" app_target="$2" executable="$3" cli_path gui_path windows_executable candidate name
  local cli_kind=managed gui_kind=link menu_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/sona.desktop"
  candidate="$RUNTIME_LAUNCHER_BIN_DIR/sona-mcp"
  if [[ -e "$candidate" || -L "$candidate" ]] && { [[ ! -L "$candidate" ]] || [[ "$(readlink "$candidate")" != "$RUNTIME_MCP_BIN" ]]; }; then
    err "Refusing to replace an unrelated launcher: $candidate"
    return 1
  fi
  for name in skill-bill; do
    candidate="$RUNTIME_LAUNCHER_BIN_DIR/$name"
    if [[ -e "$candidate" || -L "$candidate" ]] && { [[ -L "$candidate" ]] || [[ ! -f "$candidate" ]] || ! grep -qF '# skill-bill managed launcher' "$candidate"; }; then
      err "Refusing to replace an unrelated launcher: $candidate"
      return 1
    fi
  done
  cli_path="$RUNTIME_LAUNCHER_BIN_DIR/sona"
  gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  if [[ "$os" == "windows" ]]; then
    cli_path="$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"
    gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"
    if command -v cygpath >/dev/null 2>&1; then windows_executable="$(cygpath -w "$executable")"; else windows_executable="${executable//\//\\}"; fi
    cli_kind=windows-desktop
    gui_kind=windows-desktop
    candidate="$RUNTIME_LAUNCHER_BIN_DIR/sona"
    if [[ -e "$candidate" || -L "$candidate" ]] && { [[ -L "$candidate" ]] || [[ ! -f "$candidate" ]] || ! grep -qF '# skill-bill managed launcher' "$candidate"; }; then
      err "Refusing to replace an unrelated CLI launcher: $candidate"
      return 1
    fi
  fi
  for candidate in "$cli_path" "$gui_path"; do
    local kind="$gui_kind" expected="$executable"
    [[ "$candidate" != "$cli_path" ]] || kind="$cli_kind"
    [[ "$kind" != windows-desktop ]] || expected="$windows_executable"
    if [[ -e "$candidate" || -L "$candidate" ]]; then
      local owned=0
      if [[ "$kind" == windows-desktop ]]; then
        is_owned_windows_desktop_launcher "$candidate" "$expected" && owned=1
        [[ "$candidate" != "$cli_path" ]] || { [[ -f "$candidate" && ! -L "$candidate" ]] && grep -qF '# skill-bill managed launcher' "$candidate" && owned=1; }
      elif [[ -L "$candidate" && "$(readlink "$candidate")" == "$expected" ]]; then
        owned=1
      elif [[ "$candidate" == "$cli_path" && -f "$candidate" && ! -L "$candidate" ]] && grep -qF '# skill-bill managed launcher' "$candidate"; then
        owned=1
      fi
      if [[ "$owned" -eq 0 ]]; then err "Refusing to replace an unrelated desktop launcher: $candidate"; return 1; fi
    fi
  done
  if [[ "$os" == linux ]]; then
    local quoted_new quoted_old expected_icon
    quoted_new="$(desktop_quoted_exec_path "$gui_path")"
    quoted_old="$(desktop_quoted_exec_path "$cli_path")"
    expected_icon="$app_target/lib/Sona.png"
    if [[ -e "$menu_file" || -L "$menu_file" ]] && { [[ ! -f "$menu_file" || -L "$menu_file" ]] || { ! grep -Fxq "Exec=$quoted_new" "$menu_file" && ! grep -Fxq "Exec=$quoted_old" "$menu_file"; } || ! grep -Fxq 'Name=Sona' "$menu_file" || ! grep -Fxq 'Type=Application' "$menu_file" || ! grep -Fxq "Icon=$expected_icon" "$menu_file" || ! grep -Fxq 'StartupWMClass=dev-skillbill-desktop-app-DesktopLauncherKt' "$menu_file"; }; then
      err "Refusing to overwrite an unrelated desktop entry: $menu_file"
      return 1
    fi
  fi
}

prepare_desktop_app_launcher() {
  local os="$1" app_target="$2" executable="$3"
  local launcher_path launcher_temp windows_executable applications_dir desktop_file quoted_launcher
  local staged_desktop=""
  local expected_icon="$app_target/lib/Sona.png"
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR" || return 1
  check_desktop_app_launcher_ownership "$os" "$app_target" "$executable" || return 1
  launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  [[ "$os" != "windows" ]] || launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"
  if [[ "$os" == "linux" ]]; then
    applications_dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
    desktop_file="$applications_dir/sona.desktop"
    quoted_launcher="$(desktop_quoted_exec_path "$launcher_path")"
    if [[ -e "$desktop_file" || -L "$desktop_file" ]]; then
      local old_launcher="$RUNTIME_LAUNCHER_BIN_DIR/sona"
      [[ "$os" != "windows" ]] || old_launcher="$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"
      local old_quoted_launcher="$(desktop_quoted_exec_path "$old_launcher")"
      if [[ ! -f "$desktop_file" || -L "$desktop_file" ]] || { ! grep -Fxq "Exec=$quoted_launcher" "$desktop_file" && ! grep -Fxq "Exec=$old_quoted_launcher" "$desktop_file"; } || ! grep -Fxq "Icon=$expected_icon" "$desktop_file"; then
        err "Refusing to overwrite an unrelated desktop entry: $desktop_file"
        return 1
      fi
    fi
    mkdir -p "$applications_dir" || return 1
    staged_desktop="$desktop_file.stage.${DESKTOP_TRANSACTION_ID:-$$}"
    if ! cat > "$staged_desktop" <<ENTRY
[Desktop Entry]
Name=Sona
Type=Application
Exec=$quoted_launcher
Icon=$expected_icon
StartupWMClass=dev-skillbill-desktop-app-DesktopLauncherKt
Terminal=false
Categories=Development;
ENTRY
    then
      rm -f "$staged_desktop"
      return 1
    fi
  fi

  launcher_temp="$launcher_path.stage.${DESKTOP_TRANSACTION_ID:-$$}"
  if [[ "$os" == "windows" ]]; then
    if command -v cygpath >/dev/null 2>&1; then windows_executable="$(cygpath -w "$executable")"; else windows_executable="${executable//\//\\}"; fi
    { printf '@echo off\r\n'; printf 'REM Managed by Sona install.sh\r\n'; printf '"%s" %%*\r\n' "$windows_executable"; } > "$launcher_temp" || { rm -f "$staged_desktop"; return 1; }
  else
    ln -s "$executable" "$launcher_temp" || { rm -f "$staged_desktop"; return 1; }
  fi
}

discard_desktop_app_launcher_stage() {
  local os="$1" launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  [[ "$os" != "windows" ]] || launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"
  local stage="$launcher_path.stage.${DESKTOP_TRANSACTION_ID:-$$}"
  if [[ -e "$stage" || -L "$stage" ]] && ! rm -f "$stage"; then err "Could not remove desktop launcher stage: $stage"; fi
  if [[ "$os" == "linux" ]]; then
    stage="${XDG_DATA_HOME:-$HOME/.local/share}/applications/sona.desktop.stage.${DESKTOP_TRANSACTION_ID:-$$}"
    if [[ -e "$stage" || -L "$stage" ]] && ! rm -f "$stage"; then err "Could not remove desktop menu stage: $stage"; fi
  fi
}

install_desktop_app_launcher() {
  local os="$1" app_target="$2" executable="$3"
  local launcher_path launcher_temp desktop_file launcher_backup="" desktop_backup="" staged_desktop=""
  launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  [[ "$os" != "windows" ]] || launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"
  launcher_temp="$launcher_path.stage.$$"
  check_desktop_app_launcher_ownership "$os" "$app_target" "$executable" || { discard_desktop_app_launcher_stage "$os"; return 1; }
  if [[ "$os" == "linux" ]]; then
    desktop_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/sona.desktop"
    staged_desktop="$desktop_file.stage.$$"
    [[ -f "$desktop_file" ]] && { desktop_backup="$desktop_file.bak.$$"; cp -p "$desktop_file" "$desktop_backup" || { discard_desktop_app_launcher_stage "$os"; return 1; }; }
  fi
  if [[ -e "$launcher_path" || -L "$launcher_path" ]]; then launcher_backup="$launcher_path.bak.$$"; mv "$launcher_path" "$launcher_backup" || { [[ -z "$desktop_backup" ]] || rm -f "$desktop_backup"; discard_desktop_app_launcher_stage "$os"; return 1; }; fi
  if ! mv "$launcher_temp" "$launcher_path"; then
    [[ -z "$launcher_backup" ]] || mv "$launcher_backup" "$launcher_path"
    [[ -z "$desktop_backup" ]] || rm -f "$desktop_backup"
    discard_desktop_app_launcher_stage "$os"
    return 1
  fi
  if [[ "$os" == "linux" ]]; then
    if ! mv "$staged_desktop" "$desktop_file"; then
      rm -f "$launcher_path"
      [[ -z "$launcher_backup" ]] || mv "$launcher_backup" "$launcher_path"
      [[ -z "$desktop_backup" ]] || mv "$desktop_backup" "$desktop_file"
      discard_desktop_app_launcher_stage "$os"
      return 1
    fi
    rm -f "$desktop_backup"
  fi
  [[ -z "$launcher_backup" ]] || rm -f "$launcher_backup"
  local old_launcher="$RUNTIME_LAUNCHER_BIN_DIR/sona"
  [[ "$os" != "windows" ]] || old_launcher="$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"
  if [[ "$os" == "windows" ]]; then
    local windows_executable
    if command -v cygpath >/dev/null 2>&1; then windows_executable="$(cygpath -w "$executable")"; else windows_executable="${executable//\//\\}"; fi
    if [[ -f "$old_launcher" && ! -L "$old_launcher" ]] && grep -Fxq 'REM Managed by Sona install.sh' "$old_launcher" && tr -d '\r' < "$old_launcher" | grep -Fxq "\"$windows_executable\" %*"; then
      rm -f "$old_launcher" || { err "Could not retire the old desktop launcher $old_launcher"; return 1; }
    fi
  elif [[ -L "$old_launcher" ]] && [[ "$(readlink "$old_launcher")" == "$executable" ]]; then
    rm -f "$old_launcher" || { err "Could not retire the old desktop launcher $old_launcher"; return 1; }
  fi
}

linux_legacy_desktop_is_owned() {
  local legacy_app="$1/Skill Bill"
  local legacy_launcher="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
  local legacy_entry="${XDG_DATA_HOME:-$HOME/.local/share}/applications/skillbill.desktop"
  local has_owner=0
  [[ ! -L "$legacy_app" ]] || return 1
  if [[ -e "$legacy_launcher" || -L "$legacy_launcher" ]]; then
    [[ -L "$legacy_launcher" && "$(readlink "$legacy_launcher")" == "$legacy_app/bin/Skill Bill" ]] || return 1
    has_owner=1
  fi
  if [[ -e "$legacy_entry" || -L "$legacy_entry" ]]; then
    [[ -f "$legacy_entry" && ! -L "$legacy_entry" ]] || return 1
    grep -Fxq 'Name=Skill Bill' "$legacy_entry" || return 1
    grep -Fxq "Exec=$(desktop_quoted_exec_path "$legacy_launcher")" "$legacy_entry" || return 1
    grep -Fxq "Icon=$legacy_app/lib/Skill Bill.png" "$legacy_entry" || return 1
    grep -Fxq 'StartupWMClass=dev-skillbill-desktop-app-DesktopLauncherKt' "$legacy_entry" || return 1
    has_owner=1
  fi
  [[ "$has_owner" -eq 1 ]]
}

migrate_kde_desktop_pin() {
  case "${XDG_CURRENT_DESKTOP:-}:${KDE_FULL_SESSION:-}" in
    *KDE*|*:true) ;;
    *) return 0 ;;
  esac
  local dbus_command
  if command -v qdbus6 >/dev/null 2>&1; then
    dbus_command=qdbus6
  elif command -v qdbus >/dev/null 2>&1; then
    dbus_command=qdbus
  else
    warn "Could not update the old KDE dock pin because qdbus is unavailable. Unpin Skill Bill and pin Sona."
    return 0
  fi
  if ! "$dbus_command" org.kde.plasmashell /PlasmaShell org.kde.PlasmaShell.evaluateScript '
    var surfaces = desktops().concat(panels());
    for (var i = 0; i < surfaces.length; i++) {
      var widgets = surfaces[i].widgets();
      for (var j = 0; j < widgets.length; j++) {
        var widget = widgets[j];
        widget.currentConfigGroup = ["General"];
        var entries = String(widget.readConfig("launchers", [])).split(",");
        var changed = false;
        for (var k = 0; k < entries.length; k++) {
          if (entries[k] === "applications:skillbill.desktop") {
            entries[k] = "applications:sona.desktop";
            changed = true;
          }
        }
        if (changed) widget.writeConfig("launchers", entries);
      }
    }
  ' >/dev/null; then
    warn "Could not update the old KDE dock pin. Unpin Skill Bill and pin Sona."
  fi
}

cleanup_linux_legacy_desktop() {
  local install_root="$1" legacy_app="$1/Skill Bill"
  local legacy_launcher="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
  local applications_dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  local legacy_entry="$applications_dir/skillbill.desktop"
  if [[ -e "$legacy_app" || -L "$legacy_app" || -e "$legacy_launcher" || -L "$legacy_launcher" || -e "$legacy_entry" || -L "$legacy_entry" ]]; then
    if linux_legacy_desktop_is_owned "$install_root"; then
      if rm -rf -- "$legacy_app" && rm -f -- "$legacy_launcher" "$legacy_entry"; then
        ok "Removed the previous desktop installation."
      else
        warn "Could not finish removing the previous desktop installation under $install_root. Re-run the installer to retry."
      fi
    else
      warn "Preserved legacy desktop files whose ownership could not be confirmed under $install_root."
      return 0
    fi
  fi
  migrate_kde_desktop_pin
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$applications_dir" || warn "Could not refresh the desktop application database."
  fi
  if command -v kbuildsycoca6 >/dev/null 2>&1; then
    kbuildsycoca6 || warn "Could not refresh the KDE application cache."
  elif command -v kbuildsycoca5 >/dev/null 2>&1; then
    kbuildsycoca5 || warn "Could not refresh the KDE application cache."
  fi
}

BUILT_DESKTOP_APP_SOURCE=""
# Replacing the app bundle on disk does not affect a copy that is already running, so
# the installer stops it and relaunches the new build. Matching the full executable
# path keeps unrelated processes out of reach.
desktop_app_running_pids() {
  local executable pattern flags="-f"
  command -v pgrep >/dev/null 2>&1 || return 0
  # BSD pgrep (macOS) leaves out its own ancestors unless -a is given, so an app whose
  # built-in terminal runs the installer would be missed. procps pgrep excludes only
  # itself, and -a means "list full command lines" there.
  case "$(uname -s 2>/dev/null || true)" in
    Darwin|*BSD) flags="-af" ;;
  esac
  for executable in "$@"; do
    pattern="$(printf '%s' "$executable" | sed 's/[][\.*^$+?(){}|]/\\&/g')"
    pgrep $flags -- "^$pattern( |\$)" 2>/dev/null || true
  done
}

# Succeeds when one of the given pids is the installer or one of its ancestors, which
# happens when the installer runs in the desktop app's built-in terminal.
desktop_app_hosts_installer() {
  local pid="$$" parent candidate
  while [[ -n "$pid" && "$pid" -gt 1 ]]; do
    for candidate in "$@"; do
      [[ "$candidate" == "$pid" ]] && return 0
    done
    parent="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d '[:space:]')" || return 1
    [[ "$parent" != "$pid" ]] || return 1
    pid="$parent"
  done
  return 1
}

# Runs a command in its own session, so it survives the desktop app closing the
# terminal the installer runs in.
run_detached() {
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$@" </dev/null >/dev/null 2>&1 &
  elif command -v perl >/dev/null 2>&1; then
    nohup perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV or exit 127' "$@" </dev/null >/dev/null 2>&1 &
  else
    nohup "$@" </dev/null >/dev/null 2>&1 &
  fi
}

# Stopping an app that hosts the installer would end the installer with it, so a
# detached helper waits for the installer to exit, stops the app, then launches the
# new build. Its output goes to the desktop restart log.
schedule_desktop_app_restart() {
  local os="$1" app_target="$2" executable="$3" log="/dev/null" helper
  shift 3
  if [[ -n "${SKILL_BILL_STATE_DIR:-}" ]] && mkdir -p "$SKILL_BILL_STATE_DIR/desktop" 2>/dev/null; then
    log="$SKILL_BILL_STATE_DIR/desktop/restart.log"
  fi
  helper="$(declare -f warn stop_desktop_app launch_desktop_app)"'
exec >>"$1" 2>&1
SKILL_BILL_STATE_DIR=$2 installer=$3 os=$4 app_target=$5 executable=$6
shift 6
while kill -0 "$installer" 2>/dev/null; do sleep 0.2; done
stop_desktop_app "$@"
launch_desktop_app "$os" "$app_target" "$executable"'
  run_detached bash -c "$helper" desktop-restart "$log" "${SKILL_BILL_STATE_DIR:-}" "$$" "$os" "$app_target" "$executable" "$@"
}

stop_desktop_app() {
  local pid attempt
  kill -TERM "$@" 2>/dev/null || true
  for attempt in $(seq 1 50); do
    for pid in "$@"; do
      if kill -0 "$pid" 2>/dev/null; then
        sleep 0.2
        continue 2
      fi
    done
    return 0
  done
  kill -KILL "$@" 2>/dev/null || true
}

launch_desktop_app() {
  local os="$1" app_target="$2" executable="$3"
  case "$os" in
    macos)
      open "$app_target"
      ;;
    linux)
      if command -v systemd-run >/dev/null 2>&1 && command -v systemctl >/dev/null 2>&1 &&
        systemctl --user show-environment >/dev/null 2>&1; then
        systemd-run --user --collect --quiet --service-type=exec \
          --unit="skill-bill-desktop-$$" -- "$executable"
      else
        warn "Desktop service manager unavailable; launching the app with nohup."
        local log_dir="$SKILL_BILL_STATE_DIR/desktop"
        mkdir -p "$log_dir" || return 1
        nohup "$executable" >>"$log_dir/desktop.log" 2>&1 &
      fi
      ;;
    *)
      nohup "$executable" >/dev/null 2>&1 &
      ;;
  esac
}

build_source_desktop_app() {
  local os="$1" desktop_dir="$RUNTIME_KOTLIN_DIR/../desktop"
  if [[ ! -x "$desktop_dir/gradlew" ]]; then
    warn "Desktop app source is missing from this checkout: $desktop_dir"
    DESKTOP_APP_INSTALL_RESULT="source missing"
    return 1
  fi
  if ! ensure_build_jvm; then
    DESKTOP_APP_INSTALL_RESULT="build JVM unavailable"
    return 1
  fi
  info "Building desktop app from source."
  # --no-daemon still starts a single-use daemon. Keep it outside the shared
  # registry so another checkout's gradlew --stop cannot cancel this build.
  if ! (cd "$desktop_dir" || exit 1
    [[ -z "${SOURCE_BUILD_RELEASE_VERSION:-}" ]] || export RELEASE_VERSION="$SOURCE_BUILD_RELEASE_VERSION"
    ./gradlew --no-daemon -Dorg.gradle.daemon.registry.base="$PWD/.gradle/installer-daemon" -q createDistributable); then
    warn "Could not build the desktop app from source; keeping the existing app install."
    DESKTOP_APP_INSTALL_RESULT="build failed"
    return 1
  fi
  BUILT_DESKTOP_APP_SOURCE="$(desktop_app_target_path "$os" "$desktop_dir/app/build/compose/binaries/main/app")"
}

install_selected_desktop_app() {
  stage_selected_desktop_app && activate_staged_desktop_app
}

# Build or fetch the desktop app and validate it without touching the live
# install. Leaves the app directory in STAGED_DESKTOP_APP_SOURCE.
stage_selected_desktop_app() {
  local os token archive work_dir extract_dir app_source
  STAGED_DESKTOP_APP_SOURCE=""
  DESKTOP_APP_INSTALL_RESULT="staging failed"
  os="$(host_os)"
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    build_source_desktop_app "$os" || return 1
    init_prebuilt_work_dir || return 1
    work_dir="$(prebuilt_work_dir)"
    if [[ -z "$work_dir" ]] || ! work_dir="$(mktemp -d "$work_dir/desktop-source.XXXXXX")"; then
      err "Could not create a private desktop staging directory."
      return 1
    fi
    app_source="$(desktop_app_target_path "$os" "$work_dir")"
    if ! cp -R "$BUILT_DESKTOP_APP_SOURCE" "$app_source"; then
      err "Could not snapshot the desktop build before checking runtime parity."
      return 1
    fi
  else
    if ! token="$(host_token)"; then
      err "No desktop app release target is supported for this host."
      DESKTOP_APP_INSTALL_RESULT="not available for $os"
      return 1
    fi
    if ! resolve_desktop_app_release_asset; then
      warn "Could not query desktop release assets; keeping the existing app install."
      DESKTOP_APP_INSTALL_RESULT="release lookup failed"
      return 1
    fi
    if [[ -z "$RESOLVED_DESKTOP_APP_ASSET" ]]; then
      err "The selected release has no required desktop app build for $token."
      DESKTOP_APP_INSTALL_RESULT="not available for $token"
      return 1
    fi

    init_prebuilt_work_dir
    work_dir="$(prebuilt_work_dir)"
    if [[ -z "$work_dir" ]]; then
      err "Could not create a work directory for the desktop app download."
      DESKTOP_APP_INSTALL_RESULT="download failed"
      return 1
    fi
    info "Fetching desktop app for $token."
    if ! archive="$(fetch_release_asset "$RESOLVED_DESKTOP_APP_ASSET")" || ! verify_sha256 "$archive"; then
      warn "Could not download and verify the desktop app; keeping the existing app install."
      DESKTOP_APP_INSTALL_RESULT="download failed"
      return 1
    fi

    extract_dir="$work_dir/extract-desktop"
    if ! mkdir -p "$extract_dir"; then
      DESKTOP_APP_INSTALL_RESULT="extract failed"
      return 1
    fi
    local member normalized_member
    while IFS= read -r member; do
      normalized_member="${member#./}"
      [[ -n "$normalized_member" ]] || continue
      if [[ "$normalized_member" == /* || "$normalized_member" == *"/../"* || "$normalized_member" == ../* || "$normalized_member" == */.. ]]; then
        err "Desktop archive contains an unsafe path: $member"
        DESKTOP_APP_INSTALL_RESULT="archive rejected"
        return 1
      fi
      case "$normalized_member" in
        LICENSE|Sona|Sona/*|Sona.app|Sona.app/*)
          ;;
        *)
          err "Desktop archive contains an unexpected path: $member"
          DESKTOP_APP_INSTALL_RESULT="archive rejected"
          return 1
          ;;
      esac
    done < <(tar -tzf "$archive")
    if ! tar -xzf "$archive" -C "$extract_dir"; then
      err "Could not extract the desktop app archive."
      DESKTOP_APP_INSTALL_RESULT="extract failed"
      return 1
    fi

    app_source="$(desktop_app_target_path "$os" "$extract_dir")"
  fi
  if [[ ! -d "$app_source" ]]; then
    err "Desktop distribution does not contain the expected app directory for $os."
    DESKTOP_APP_INSTALL_RESULT="archive rejected"
    return 1
  fi
  local source_executable
  source_executable="$(desktop_app_executable_path "$os" "$app_source")"
  if [[ ! -f "$source_executable" ]]; then
    err "Desktop distribution is missing its app launcher: $source_executable"
    DESKTOP_APP_INSTALL_RESULT="archive rejected"
    return 1
  fi
  if [[ "$os" != "windows" && ! -x "$source_executable" ]]; then
    err "Desktop app launcher is not executable: $source_executable"
    DESKTOP_APP_INSTALL_RESULT="archive rejected"
    return 1
  fi
  DESKTOP_APP_INSTALL_RESULT="staged"
  STAGED_DESKTOP_APP_SOURCE="$app_source"
}

# Replace the live desktop app with the one stage_selected_desktop_app staged.
activate_staged_desktop_app() {
  local os app_source app_target executable install_root staged_app backup_app launcher_path
  local previous_app_moved=0
  local DESKTOP_TRANSACTION_ID="$$.$RANDOM"
  os="$(host_os)"
  app_source="$STAGED_DESKTOP_APP_SOURCE"
  install_root="$(desktop_app_install_root "$os")" || return 1
  app_target="$(desktop_app_target_path "$os" "$install_root")"
  executable="$(desktop_app_executable_path "$os" "$app_target")"
  check_desktop_app_target_ownership "$os" "$app_target" "$executable" || { DESKTOP_APP_INSTALL_RESULT="app target refused"; return 1; }
  local expected_gui_stage="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.stage.$DESKTOP_TRANSACTION_ID"
  [[ "$os" != windows ]] || expected_gui_stage="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd.stage.$DESKTOP_TRANSACTION_ID"
  local expected_menu_stage="${XDG_DATA_HOME:-$HOME/.local/share}/applications/sona.desktop.stage.$DESKTOP_TRANSACTION_ID"
  if [[ -e "$expected_gui_stage" || -L "$expected_gui_stage" || ( "$os" == linux && ( -e "$expected_menu_stage" || -L "$expected_menu_stage" ) ) ]]; then
    err "Desktop launcher stage path already exists. Preserving it for recovery: $expected_gui_stage $expected_menu_stage"
    DESKTOP_APP_INSTALL_RESULT="launcher refused"
    return 1
  fi
  if ! prepare_desktop_app_launcher "$os" "$app_target" "$executable"; then
    DESKTOP_APP_INSTALL_RESULT="launcher refused"
    return 1
  fi
  launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  [[ "$os" != "windows" ]] || launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"
  local -a running_pids=() running_paths=("$executable")
  if [[ "$os" == "linux" ]] && linux_legacy_desktop_is_owned "$install_root"; then
    running_paths+=("$install_root/Skill Bill/bin/Skill Bill" "$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop")
  fi
  local running_pid
  if [[ -e "$launcher_path" || -L "$launcher_path" ]]; then
    running_paths+=("$launcher_path")
  fi
  local old_launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona"
  [[ "$os" != "windows" ]] || old_launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"
  if [[ -L "$old_launcher_path" ]] && [[ "$(readlink "$old_launcher_path")" == "$executable" ]]; then
    running_paths+=("$old_launcher_path")
  fi
  while IFS= read -r running_pid; do
    [[ -n "$running_pid" ]] && running_pids+=("$running_pid")
  done < <(desktop_app_running_pids "${running_paths[@]}")

  if ! mkdir -p "$install_root"; then
    discard_desktop_app_launcher_stage "$os"
    return 1
  fi
  local transaction_id="$DESKTOP_TRANSACTION_ID"
  staged_app="$install_root/.Sona.installing.$transaction_id"
  backup_app="$install_root/.Sona.previous.$transaction_id"
  if [[ -e "$staged_app" || -L "$staged_app" || -e "$backup_app" || -L "$backup_app" ]]; then
    err "Desktop transaction paths already exist. Preserving them for recovery: $staged_app $backup_app"
    discard_desktop_app_launcher_stage "$os"
    return 1
  fi
  if ! cp -R "$app_source" "$staged_app"; then
    if [[ -e "$staged_app" || -L "$staged_app" ]] && ! rm -rf "$staged_app"; then
      err "Could not remove the partial desktop app stage: $staged_app"
    fi
    discard_desktop_app_launcher_stage "$os"
    err "Could not stage the desktop app under $install_root."
    DESKTOP_APP_INSTALL_RESULT="install failed"
    return 1
  fi
  if [[ -e "$app_target" || -L "$app_target" ]]; then
    if ! mv "$app_target" "$backup_app"; then
      if ! rm -rf "$staged_app"; then err "Could not remove desktop app stage: $staged_app"; fi
      discard_desktop_app_launcher_stage "$os"
      err "Could not move the previous desktop app out of the way."
      DESKTOP_APP_INSTALL_RESULT="install failed"
      return 1
    fi
    previous_app_moved=1
  fi
  if ! mv "$staged_app" "$app_target"; then
    if [[ "$previous_app_moved" -eq 1 && ( -e "$backup_app" || -L "$backup_app" ) ]]; then
      if [[ -e "$app_target" || -L "$app_target" ]] || ! mv "$backup_app" "$app_target"; then
        err "Could not restore the previous desktop app. Recovery copy: $backup_app"
      fi
    fi
    if [[ -e "$staged_app" || -L "$staged_app" ]] && ! rm -rf "$staged_app"; then err "Could not remove desktop app stage: $staged_app"; fi
    discard_desktop_app_launcher_stage "$os"
    err "Could not install the desktop app under $app_target."
    DESKTOP_APP_INSTALL_RESULT="install failed"
    return 1
  fi
  local cli_stage="$RUNTIME_LAUNCHER_BIN_DIR/sona.stage.$transaction_id"
  local compat_stage="$RUNTIME_LAUNCHER_BIN_DIR/skill-bill.stage.$transaction_id"
  local mcp_stage="$RUNTIME_LAUNCHER_BIN_DIR/sona-mcp.stage.$transaction_id"
  local cli_path="$RUNTIME_LAUNCHER_BIN_DIR/sona"
  local old_gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona"
  local gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  [[ "$os" != windows ]] || { cli_path="$RUNTIME_LAUNCHER_BIN_DIR/sona"; old_gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona.cmd"; gui_path="$RUNTIME_LAUNCHER_BIN_DIR/sona-app.cmd"; }
  local menu_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/sona.desktop"
  local menu_stage="$menu_file.stage.$transaction_id"
  local expected_gui_executable="$executable"
  if [[ "$os" == windows ]]; then
    if command -v cygpath >/dev/null 2>&1; then expected_gui_executable="$(cygpath -w "$executable")"; else expected_gui_executable="${executable//\//\\}"; fi
  fi
  local -a tx_targets=() tx_stages=() tx_backups=() tx_kinds=() tx_expected=() tx_published=()
  for tx_target in "$cli_stage" "$compat_stage" "$mcp_stage"; do
    if [[ -e "$tx_target" || -L "$tx_target" ]]; then
      err "A transaction stage path already exists. Preserving it for recovery: $tx_target"
      discard_desktop_app_launcher_stage "$os"
      DESKTOP_APP_INSTALL_RESULT="launcher failed"
      return 1
    fi
  done
  if ! install_skill_bill_launcher sona "$cli_stage" || ! install_skill_bill_launcher skill-bill "$compat_stage" ||
     ! ln -s "$RUNTIME_MCP_BIN" "$mcp_stage"; then
    err "Could not stage runtime launchers."
    for tx_target in "$cli_stage" "$compat_stage" "$mcp_stage"; do
      if [[ -e "$tx_target" || -L "$tx_target" ]] && ! rm -f "$tx_target"; then err "Could not remove launcher stage: $tx_target"; fi
    done
    discard_desktop_app_launcher_stage "$os"
    if [[ "$previous_app_moved" -eq 1 && ( -e "$backup_app" || -L "$backup_app" ) ]]; then
      if [[ -e "$app_target" || -L "$app_target" ]]; then
        if [[ -f "$executable" ]] && build_identities_match "$(staged_runtime_build_identity_record)" "$(desktop_app_build_identity_path "$os" "$app_target")"; then
          if ! rm -rf "$app_target"; then err "Could not remove the failed candidate desktop app: $app_target"; fi
        else
          err "Preserving an unclassified app entry at $app_target"
        fi
      fi
      if [[ -e "$app_target" || -L "$app_target" ]] || ! mv "$backup_app" "$app_target"; then err "Could not restore the previous desktop app. Recovery copy: $backup_app"; fi
      report_desktop_recovery_path "Launcher staging failed after runtime activation."
    fi
    DESKTOP_APP_INSTALL_RESULT="launcher failed"
    return 1
  fi
  tx_targets+=("$gui_path"); tx_stages+=("$gui_path.stage.$transaction_id")
  if [[ "$os" == windows ]]; then tx_kinds+=(windows-desktop); else tx_kinds+=(link); fi
  tx_expected+=("$expected_gui_executable")
  if [[ "$os" == linux ]]; then tx_targets+=("$menu_file"); tx_stages+=("$menu_stage"); tx_kinds+=(menu); tx_expected+=("$gui_path"); fi
  if [[ "$os" == windows ]]; then tx_targets+=("$old_gui_path"); tx_stages+=(""); tx_kinds+=(windows-desktop); tx_expected+=(""); fi
  tx_targets+=("$cli_path" "$RUNTIME_LAUNCHER_BIN_DIR/sona-mcp" "$RUNTIME_LAUNCHER_BIN_DIR/skill-bill")
  tx_stages+=("$cli_stage" "$mcp_stage" "$compat_stage")
  tx_kinds+=(managed link managed)
  tx_expected+=("" "$RUNTIME_MCP_BIN" "")
  local tx_count=${#tx_targets[@]} tx_index tx_rollback tx_target tx_stage tx_backup tx_owned
  for ((tx_index=0; tx_index<tx_count; tx_index++)); do tx_backups+=(""); tx_published+=(0); done
  local tx_failed=0
  for ((tx_index=0; tx_index<tx_count; tx_index++)); do
    if ! check_desktop_app_launcher_ownership "$os" "$app_target" "$executable"; then tx_failed=1; break; fi
    tx_target="${tx_targets[$tx_index]}"
    tx_stage="${tx_stages[$tx_index]}"
    if [[ -e "$tx_target" || -L "$tx_target" ]]; then
      tx_backup="$tx_target.backup.$transaction_id.$tx_index"
      if [[ -e "$tx_backup" || -L "$tx_backup" ]] || ! mv "$tx_target" "$tx_backup"; then tx_failed=1; break; fi
      tx_backups[$tx_index]="$tx_backup"
    fi
    if [[ -n "$tx_stage" ]]; then
      if [[ -e "$tx_target" || -L "$tx_target" ]] || ! mv "$tx_stage" "$tx_target"; then tx_failed=1; break; fi
      tx_published[$tx_index]=1
    fi
  done
  if [[ "$tx_failed" -ne 0 ]]; then
    err "Launcher publication failed; restoring entries changed by this install."
    for ((tx_rollback=tx_index; tx_rollback>=0; tx_rollback--)); do
      (( tx_rollback < tx_count )) || continue
      tx_target="${tx_targets[$tx_rollback]}"
      tx_stage="${tx_stages[$tx_rollback]}"
      tx_owned=0
      if [[ "${tx_published[$tx_rollback]}" -eq 1 && ( -e "$tx_target" || -L "$tx_target" ) ]]; then
        case "${tx_kinds[$tx_rollback]}" in
          link) [[ -L "$tx_target" && "$(readlink "$tx_target")" == "${tx_expected[$tx_rollback]}" ]] && tx_owned=1 ;;
          managed) [[ -f "$tx_target" && ! -L "$tx_target" ]] && grep -qF '# skill-bill managed launcher' "$tx_target" && tx_owned=1 ;;
          windows-desktop) is_owned_windows_desktop_launcher "$tx_target" "${tx_expected[$tx_rollback]}" && tx_owned=1 ;;
          menu) grep -Fxq "Exec=$(desktop_quoted_exec_path "${tx_expected[$tx_rollback]}")" "$tx_target" 2>/dev/null && tx_owned=1 ;;
        esac
        if [[ "$tx_owned" -eq 1 ]]; then rm -f "$tx_target" || err "Could not remove this attempt's launcher at $tx_target"; fi
      fi
      tx_backup="${tx_backups[$tx_rollback]}"
      if [[ -n "$tx_backup" && ( -e "$tx_backup" || -L "$tx_backup" ) ]]; then
        if [[ -e "$tx_target" || -L "$tx_target" ]]; then
          err "Could not restore $tx_target because another entry now occupies it. Recovery copy: $tx_backup"
        elif ! mv "$tx_backup" "$tx_target"; then
          err "Could not restore $tx_target. Recovery copy: $tx_backup"
        fi
      fi
    done
    if [[ -e "$app_target" || -L "$app_target" ]]; then
      if [[ -f "$executable" ]] && build_identities_match "$(staged_runtime_build_identity_record)" "$(desktop_app_build_identity_path "$os" "$app_target")"; then
        if ! rm -rf "$app_target"; then err "Could not remove the failed candidate desktop app: $app_target"; fi
      else
        err "Preserving an unclassified app entry at $app_target"
      fi
    fi
    if [[ "$previous_app_moved" -eq 1 && ( -e "$backup_app" || -L "$backup_app" ) ]]; then
      if [[ -e "$app_target" || -L "$app_target" ]] || ! mv "$backup_app" "$app_target"; then err "Could not restore the previous desktop app. Recovery copy: $backup_app"; fi
    fi
    for tx_target in "$cli_stage" "$compat_stage" "$mcp_stage"; do
      if [[ -e "$tx_target" || -L "$tx_target" ]] && ! rm -f "$tx_target"; then err "Could not remove launcher stage: $tx_target"; fi
    done
    discard_desktop_app_launcher_stage "$os"
    report_desktop_recovery_path "Launcher publication failed after runtime activation."
    DESKTOP_APP_INSTALL_RESULT="launcher failed"
    return 1
  fi
  for ((tx_index=0; tx_index<tx_count; tx_index++)); do
    tx_backup="${tx_backups[$tx_index]}"
    [[ -z "$tx_backup" ]] && continue
    if [[ -d "$tx_backup" && ! -L "$tx_backup" ]]; then rm -rf "$tx_backup" || warn "Retained publication backup for recovery: $tx_backup"
    else rm -f "$tx_backup" || warn "Retained publication backup for recovery: $tx_backup"; fi
  done
  local obsolete_mcp="$RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
  if [[ -L "$obsolete_mcp" && "$(readlink "$obsolete_mcp")" == "$RUNTIME_MCP_BIN" ]]; then
    rm -f "$obsolete_mcp" || { err "Could not remove owned legacy MCP launcher: $obsolete_mcp"; DESKTOP_APP_INSTALL_RESULT="launcher failed"; return 1; }
  elif [[ -e "$obsolete_mcp" || -L "$obsolete_mcp" ]]; then
    warn "Preserving unrelated launcher at $obsolete_mcp"
  fi
  if [[ "$os" == "linux" ]]; then
    cleanup_linux_legacy_desktop "$install_root"
  fi
  ok "Desktop app installed at $app_target"
  DESKTOP_APP_INSTALL_RESULT="installed at $app_target"
  if [[ ${#running_pids[@]} -gt 0 ]] && desktop_app_hosts_installer "${running_pids[@]}"; then
    schedule_desktop_app_restart "$os" "$app_target" "$executable" "${running_pids[@]}"
    ok "The desktop app runs this installer, so it restarts with the new build when the installer exits."
  elif [[ ${#running_pids[@]} -gt 0 ]]; then
    stop_desktop_app "${running_pids[@]}"
    if launch_desktop_app "$os" "$app_target" "$executable"; then
      ok "Restarted the running desktop app."
    else
      err "Stopped the previous desktop app but could not start the new one; open it from $app_target."
      DESKTOP_APP_INSTALL_RESULT="restart failed"
      return 1
    fi
  fi
  if [[ "$previous_app_moved" -eq 1 && ( -e "$backup_app" || -L "$backup_app" ) ]]; then
    if ! rm -rf "$backup_app"; then warn "Retained previous desktop app for recovery: $backup_app"; fi
  fi
  if [[ -n "$RECOVERY_DESKTOP_APP_PATH" ]]; then
    discard_upgrade_recovery_copies
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
            :
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
  info "Installing all platform packs, regardless of saved platform choices."
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
    --platform-mode all
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
  warn "  Install a supported agent (claude, codex, junie, cursor, opencode, or devin),"
  warn "  then re-run ./install.sh. Choose 'manual' to select your agent explicitly."
}

selected_agent_label() {
  if [[ "$AGENT_SELECTION_MODE" == "detected" ]]; then
    printf 'runtime detection'
    return 0
  fi
  format_agent_list "${AGENT_NAMES[@]}"
}

run_full_install() {
  PRIOR_INSTALL_PRESENT=no
  if [[ -x "$RUNTIME_CLI_BIN" || -d "$SKILL_BILL_STATE_DIR" ]]; then PRIOR_INSTALL_PRESENT=yes; fi
  bundle_bootstrap_if_needed
  print_install_plan
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    replay_last_install_selection
  fi
  cleanup_retired_ide_plugins
  # A dirty tree has no exact source identity, so both builds of this run share
  # one run id and only match each other.
  export SKILL_BILL_INSTALL_RUN_ID="${SKILL_BILL_INSTALL_RUN_ID:-$(date +%s)-$$-$RANDOM}"
  DEFER_RUNTIME_ACTIVATION=1
  install_runtime_distributions || return 1
  DEFER_RUNTIME_ACTIVATION=0
  if ! stage_selected_desktop_app; then
    report_unstaged_desktop_app
    discard_staged_runtime
    return 1
  fi
  if ! verify_staged_build_identities; then
    discard_staged_runtime
    return 1
  fi
  local preflight_os preflight_root preflight_app preflight_executable
  preflight_os="$(host_os)"
  preflight_root="$(desktop_app_install_root "$preflight_os")" || { discard_staged_runtime; return 1; }
  preflight_app="$(desktop_app_target_path "$preflight_os" "$preflight_root")"
  preflight_executable="$(desktop_app_executable_path "$preflight_os" "$preflight_app")"
  check_desktop_app_target_ownership "$preflight_os" "$preflight_app" "$preflight_executable" || {
    discard_staged_runtime
    return 1
  }
  check_desktop_app_launcher_ownership "$preflight_os" "$preflight_app" "$preflight_executable" || {
    discard_staged_runtime
    return 1
  }
  acquire_desktop_recovery_copy "$preflight_os" "$preflight_root" "$STAGED_DESKTOP_APP_SOURCE" || {
    discard_staged_runtime
    return 1
  }
  acquire_runtime_recovery_copy || {
    discard_staged_runtime
    return 1
  }
  MIGRATION_MAY_HAVE_COMMITTED=1
  migrate_legacy_config_to_durable_path || {
    discard_staged_runtime
    return 1
  }
  migrate_persisted_data || {
    discard_staged_runtime
    return 1
  }
  if [[ -n "$STAGED_RUNTIME_ROOT" ]]; then
    if ! activate_staged_runtime_pair "$STAGED_RUNTIME_ROOT"; then
      local live_runtime_record="$RUNTIME_CLI_INSTALL_DIR/$BUILD_IDENTITY_RECORD"
      local recovery_record="$(desktop_app_build_identity_path "$preflight_os" "$RECOVERY_DESKTOP_APP_PATH")"
      if build_identities_match "$live_runtime_record" "$recovery_record"; then
        STAGED_RUNTIME_ROOT=""
        CANDIDATE_RUNTIME_ACTIVE=1
      fi
      return 1
    fi
    STAGED_RUNTIME_ROOT=""
    CANDIDATE_RUNTIME_ACTIVE=1
    locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI" || return 1
    locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP" || return 1
    ok "Kotlin runtime installed$STAGED_RUNTIME_ORIGIN"
  fi
  copy_in_authored_source
  reconcile_and_commit_authored_source

  echo ""
  printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
  echo ""
  info "Supported agents: claude, codex, junie, cursor, opencode, devin"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Install behavior: reuse saved choices, then delegate planning and apply to the Kotlin runtime."
  else
    info "Install behavior: collect choices, then delegate planning and apply to the Kotlin runtime."
    prompt_for_agent_selection
    prompt_for_telemetry_preference
  fi
  build_runtime_install_args

  echo ""
  info "Plugin:         $PLUGIN_DIR"
  info "Agents:         $(selected_agent_label)"
  info "Platforms:      all"
  info "Telemetry:      $TELEMETRY_LEVEL"
  info "MCP:            $MCP_REGISTRATION"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Selections:     reused latest successful install selection"
  fi
  echo ""

  apply_external_addon_overlay
  apply_runtime_install
  if ! activate_staged_desktop_app; then
    err "Installation incomplete: the required desktop app step failed. $DESKTOP_APP_INSTALL_RESULT."
    return 1
  fi

  printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
  INSTALL_PUBLICATION_COMPLETE=1
  echo ""
  info "Source of truth: $PLUGIN_DIR/skills/"
  info "Staging cache:   $SKILL_BILL_STATE_DIR/installed-skills"
  info "Platforms:       all"
  info "Sona (visible app name): CLI $RUNTIME_LAUNCHER_BIN_DIR/sona, MCP $RUNTIME_LAUNCHER_BIN_DIR/sona-mcp, desktop $RUNTIME_LAUNCHER_BIN_DIR/sona-app"
  info "Telemetry:       $TELEMETRY_LEVEL"
  info "MCP:             $MCP_REGISTRATION"
  info "Desktop app:     $DESKTOP_APP_INSTALL_RESULT"
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
  info "Run './install.sh' again to change agent, telemetry, or MCP choices."
  info "Next step:       open your agent and run /sona"
  echo ""
}

parse_args "$@"
bootstrap_release_installer_if_needed
resolve_install_source
run_full_install
