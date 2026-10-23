#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_SH="$REPO_ROOT/install.sh"

FAKE_HOME=""
RELEASE_DIR=""
STUB_TMP=""
WORK_TMPDIRS=()
READONLY_ROOT=""
cleanup() {
  # The failure-tolerance scenario makes a plugin root read-only; rm -rf cannot
  # delete the fake home until it is writable again.
  if [[ -n "$READONLY_ROOT" ]]; then chmod u+w "$READONLY_ROOT" 2>/dev/null || true; fi
  [[ -n "$FAKE_HOME" ]] && rm -rf "$FAKE_HOME"
  [[ -n "$RELEASE_DIR" ]] && rm -rf "$RELEASE_DIR"
  [[ -n "$STUB_TMP" ]] && rm -f "$STUB_TMP"
  local d; for d in "${WORK_TMPDIRS[@]+"${WORK_TMPDIRS[@]}"}"; do rm -rf "$d"; done
}
trap cleanup EXIT

pass() { printf '[PASS] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; exit 1; }

assert_file_exists() {
  local label="$1" path="$2"
  [[ -e "$path" ]] && { pass "$label"; return; }
  fail "$label — not found: $path"
}

assert_file_absent() {
  local label="$1" path="$2"
  [[ ! -e "$path" ]] && { pass "$label"; return; }
  fail "$label — unexpectedly present: $path"
}

host_token() {
  local s m
  s="$(uname -s)" m="$(uname -m)"
  case "$s" in
    Darwin) [[ "$m" == arm64 ]] && printf 'macos-arm64' || printf 'macos-x64' ;;
    Linux)  printf 'linux-x64' ;;
    MINGW*|MSYS*|CYGWIN*) printf 'windows-x64' ;;
    *)      printf 'linux-x64' ;;
  esac
}

# Writes a jar whose META-INF/plugin.xml declares the given IntelliJ plugin id.
make_plugin_jar() {
  local jar="$1" plugin_id="$2" tmp
  tmp="$(mktemp -d)"
  mkdir -p "$tmp/META-INF" "$(dirname "$jar")"
  printf '<idea-plugin><id>%s</id></idea-plugin>\n' "$plugin_id" >"$tmp/META-INF/plugin.xml"
  (cd "$tmp" && zip -q -r "$jar" META-INF)
  rm -rf "$tmp"
}

make_stub_runtime_cli() {
  local stub_path="$1"
  cat >"$stub_path" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

args=("$@")
i=0
home_dir=""
cmd_parts=()

while [[ $i -lt ${#args[@]} ]]; do
  arg="${args[$i]}"
  if [[ "$arg" == "--home" ]]; then
    i=$(( i + 1 ))
    home_dir="${args[$i]}"
  else
    cmd_parts+=("$arg")
  fi
  i=$(( i + 1 ))
done

[[ ${#cmd_parts[@]} -eq 0 ]] && { echo "stub: no subcommand" >&2; exit 1; }

sub="${cmd_parts[0]:-}"
sub2="${cmd_parts[1]:-}"
cmd="$sub${sub2:+ $sub2}"

flag_value() {
  local flag="$1"; shift
  local a found=0
  for a in "$@"; do
    [[ $found -eq 1 ]] && { printf '%s' "$a"; return; }
    [[ "$a" == "$flag" ]] && found=1
  done
}

has_flag() {
  local flag="$1"; shift
  local a
  for a in "$@"; do [[ "$a" == "$flag" ]] && return 0; done
  return 1
}

case "$cmd" in
  "--check-packaged-contracts") exit 0 ;;
  "version")
    echo "skill-bill version 0.0.0-smoke"
    ;;

  "install")
    if has_flag "--help" "${cmd_parts[@]}"; then
      echo "  replay-last-selection   replay the last successful install selection"
      echo "  reconcile               reconcile authored source"
      echo "  apply                   apply install"
      echo "  claude-roots            list claude config roots"
      echo "  codex-roots             list codex config roots"
      echo "  detect-agents           detect configured agents"
    fi
    ;;

  "install reconcile")
    if has_flag "--apply" "${cmd_parts[@]}"; then
      upstream_skills="$(flag_value --upstream-skills "${cmd_parts[@]}")"
      skills="$(flag_value --skills "${cmd_parts[@]}")"
      if [[ -n "${upstream_skills:-}" && -n "${skills:-}" && -d "$upstream_skills" ]]; then
        mkdir -p "$skills"
        cp -R "$upstream_skills/." "$skills/"
      fi
      echo "reconcile_summary: applied=true baseline_refreshed=true installed_count=1 pruned_count=0"
    else
      echo "reconcile_outcome: kind=adopt upstream_hash=aaa path=skills/skill-bill"
      echo "reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0"
    fi
    ;;

  "install apply")
    if [[ "$(flag_value --platform-mode "${cmd_parts[@]}")" != "all" ]] ||
      has_flag "--platform" "${cmd_parts[@]}"; then
      echo "installer must request every platform pack without a slug filter" >&2
      exit 1
    fi
    skills_src="$(flag_value --skills "${cmd_parts[@]}")"
    effective_home="${home_dir:-$HOME}"
    if has_flag "--agent-target" "${cmd_parts[@]}"; then
      agent_target_raw="$(flag_value --agent-target "${cmd_parts[@]}")"
      target_dir="${agent_target_raw#*=}"
    else
      target_dir="$effective_home/.claude"
    fi
    if [[ -n "${skills_src:-}" && -d "$skills_src" ]]; then
      rm -rf "$target_dir/skills"
      mkdir -p "$target_dir/skills"
      cp -R "$skills_src/." "$target_dir/skills/"
    fi
    ;;

  "install agent-path")
    agent="${cmd_parts[2]:-}"
    effective_home="${home_dir:-$HOME}"
    case "$agent" in
      claude)   echo "$effective_home/.claude/skills" ;;
      codex)
        if [[ -e "$effective_home/.codex" || -e "$effective_home/.codex/skills" ]]; then
          echo "$effective_home/.codex/skills"
        else
          echo "$effective_home/.agents/skills"
        fi
        ;;
      junie)    echo "$effective_home/.junie/skills" ;;
      cursor)   echo "$effective_home/.cursor/skills" ;;
      *) echo "unknown agent: $agent" >&2; exit 1 ;;
    esac
    ;;

  "install claude-roots"|"install codex-roots")
    echo "${home_dir:-$HOME}/.claude"
    ;;

  "install detect-agents")
    ;;

  *)
    ;;
esac
STUB
  chmod +x "$stub_path"
}

# Prefer Info-ZIP over python3: Apple's /usr/bin/python3 is an Xcode stub and
# exits 69 when the Command Line Tools license is not accepted.
write_runtime_zip() {
  local src_root="$1" top_dir="$2" out_path="$3"
  if command -v zip >/dev/null 2>&1; then
    (cd "$src_root" && zip -rq "$out_path" "$top_dir")
    return
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    fail "need zip or python3 to pack smoke-test runtime images"
  fi
  python3 - "$src_root" "$top_dir" "$out_path" <<'PY'
import sys, os, zipfile
src_root, top_dir, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for dirpath, _, files in os.walk(os.path.join(src_root, top_dir)):
        for fname in files:
            fpath = os.path.join(dirpath, fname)
            arcname = os.path.relpath(fpath, src_root)
            info = zipfile.ZipInfo(arcname)
            st = os.stat(fpath)
            info.external_attr = (st.st_mode & 0xFFFF) << 16
            with open(fpath, 'rb') as f:
                zf.writestr(info, f.read())
PY
}

# Every smoke runtime image and desktop bundle is the same clean build, so the
# installer's build identity comparison accepts the pair.
write_smoke_build_identity() {
  mkdir -p "$(dirname "$1")"
  printf 'commit=5m0ke\ndirty=false\nrelease_tag=0.0.0-smoke\ninstall_run_id=\n' >"$1"
}

make_runtime_zip() {
  local token="$1" name="$2" bin_name="$3" stub_src="$4" out_dir="$5"
  local work zip_name
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  mkdir -p "$work/$name-0.0.0-smoke/bin"
  cp "$stub_src" "$work/$name-0.0.0-smoke/bin/$bin_name"
  chmod +x "$work/$name-0.0.0-smoke/bin/$bin_name"
  write_smoke_build_identity "$work/$name-0.0.0-smoke/build-identity.properties"
  zip_name="${name}-0.0.0-smoke-${token}.zip"
  write_runtime_zip "$work" "$name-0.0.0-smoke" "$out_dir/$zip_name"
  rm -rf "$work"
  local sha
  sha="$(cd "$out_dir" && sha256sum "$zip_name" 2>/dev/null || shasum -a 256 "$zip_name")"
  printf '%s' "$sha" >"$out_dir/$zip_name.sha256"
}

# Build the skills-bundle asset a headless/piped install fetches when it cannot trust a
# CWD-relative skills/ dir. Mirrors the real release bundle (see .github/workflows/
# release.yml): tars the repo's top-level skills/, platform-packs/, orchestration/, and
# uninstall.sh so the extracted root satisfies both install.sh's
# `[[ -d "$extract_dir/skills" ]]` branch and the downstream orchestration/uninstall
# staging. Writes a sibling .sha256 in the same form as make_runtime_zip.
make_skills_bundle() {
  local out_dir="$1"
  local bundle_name="skill-bill-skills-0.0.0-smoke.tar.gz"
  (cd "$REPO_ROOT" && tar -czf "$out_dir/$bundle_name" skills platform-packs orchestration uninstall.sh)
  local sha
  sha="$(cd "$out_dir" && sha256sum "$bundle_name" 2>/dev/null || shasum -a 256 "$bundle_name")"
  printf '%s' "$sha" >"$out_dir/$bundle_name.sha256"
}

make_desktop_bundle() {
  local token="$1" out_dir="$2" work archive sha executable
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  case "$token" in
    macos-arm64|macos-x64)
      executable="$work/Skill Bill.app/Contents/MacOS/Skill Bill"
      write_smoke_build_identity "$work/Skill Bill.app/Contents/app/resources/build-identity.properties"
      ;;
    windows-x64)
      executable="$work/Skill Bill/bin/Skill Bill.bat"
      write_smoke_build_identity "$work/Skill Bill/app/resources/build-identity.properties"
      ;;
    *)
      executable="$work/Skill Bill/bin/Skill Bill"
      write_smoke_build_identity "$work/Skill Bill/lib/app/resources/build-identity.properties"
      ;;
  esac
  mkdir -p "$(dirname "$executable")"
  cp "$REPO_ROOT/LICENSE" "$work/LICENSE"
  cat > "$executable" <<'APP'
#!/usr/bin/env bash
exit 0
APP
  chmod +x "$executable"
  archive="skill-bill-desktop-0.0.0-smoke-${token}.tar.gz"
  tar -czf "$out_dir/$archive" -C "$work" .
  sha="$(cd "$out_dir" && sha256sum "$archive" 2>/dev/null || shasum -a 256 "$archive")"
  printf '%s' "$sha" >"$out_dir/$archive.sha256"
}

desktop_app_target() {
  local fake_home="$1"
  case "$TOKEN" in
    macos-arm64|macos-x64) printf '%s/Applications/Skill Bill.app' "$fake_home" ;;
    windows-x64) printf '%s/AppData/Local/SkillBill/Desktop/Skill Bill' "$fake_home" ;;
    *) printf '%s/.local/share/skillbill/desktop/Skill Bill' "$fake_home" ;;
  esac
}

desktop_app_executable() {
  local app_target="$1"
  case "$TOKEN" in
    macos-arm64|macos-x64) printf '%s/Contents/MacOS/Skill Bill' "$app_target" ;;
    windows-x64) printf '%s/bin/Skill Bill.bat' "$app_target" ;;
    *) printf '%s/bin/Skill Bill' "$app_target" ;;
  esac
}

setup_release_dir() {
  local token="$1" stub_src="$2"
  RELEASE_DIR="$(mktemp -d)"
  make_runtime_zip "$token" "runtime-cli" "runtime-cli" "$stub_src" "$RELEASE_DIR"
  make_runtime_zip "$token" "runtime-mcp" "runtime-mcp" "$stub_src" "$RELEASE_DIR"
  make_skills_bundle "$RELEASE_DIR"
  make_desktop_bundle "$token" "$RELEASE_DIR"
}

seed_selection_json() {
  local fake_home="$1"
  local platform_mode="${2:-none}"
  local selected_slugs="${3:-[]}"
  local mcp_bin="$fake_home/.skill-bill/runtime/runtime-mcp/bin/runtime-mcp"
  mkdir -p "$fake_home/.skill-bill"
  printf '%s\n' \
    '{"contract_version":"1.0","selected_agents":["claude"],"platform_pack_selection":{"mode":"'"$platform_mode"'","selected_slugs":'"$selected_slugs"'},"telemetry_level":"off","mcp_registration":{"register":false,"runtime_mcp_bin":"'"$mcp_bin"'"}}' \
    >"$fake_home/.skill-bill/install-selection.json"
}

run_install() {
  local fake_home="$1"; shift
  local extra_env=()
  while [[ $# -gt 0 && "$1" == *=* ]]; do
    extra_env+=("$1"); shift
  done
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$fake_home" \
    XDG_DATA_HOME="$fake_home/.local/share" \
    LOCALAPPDATA="$fake_home/AppData/Local" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
    "${extra_env[@]+"${extra_env[@]}"}" \
    bash "$INSTALL_SH" --reuse-last-selection "$@" \
    </dev/null
}

run_interactive_install_with_blank_defaults() {
  local fake_home="$1"
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$fake_home" \
    XDG_DATA_HOME="$fake_home/.local/share" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
    bash "$INSTALL_SH" \
    <<< $'\n\n\n\n\n\n\n\n'
}

run_piped_install_with_eof_defaults() {
  local fake_home="$1"
  (
    cd "$REPO_ROOT"
    env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
      HOME="$fake_home" \
      XDG_DATA_HOME="$fake_home/.local/share" \
      SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
      SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
      SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
      bash -c 'cat install.sh | bash' \
      </dev/null
  )
}

run_piped_bootstrap_latest_release() {
  local fake_home="$1"
  local work
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  cat >"$work/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
url="${@: -1}"
case "$url" in
  "https://api.github.com/repos/Sermilion/skill-bill/releases?per_page=100")
    cat <<'JSON'
[
  {
    "tag_name":"v9.9.9",
    "prerelease":false,
    "draft":false,
    "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v9.9.9",
    "assets":[{"name":"skill-bill-skills-9.9.9.tar.gz"}]
  }
]
JSON
    ;;
  https://api.github.com/repos/Sermilion/skill-bill/releases/tags/v9.9.9)
    cat <<'JSON'
{
  "tag_name":"v9.9.9",
  "prerelease":false,
  "draft":false,
  "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v9.9.9",
  "assets":[{"name":"skill-bill-skills-9.9.9.tar.gz"}]
}
JSON
    ;;
  https://raw.githubusercontent.com/Sermilion/skill-bill/v9.9.9/install.sh)
    cat <<'INSTALLER'
#!/usr/bin/env bash
set -euo pipefail
printf 'release_bootstrap=%s\n' "${SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED:-}"
printf 'release_args:'
for arg in "$@"; do
  printf ' [%s]' "$arg"
done
printf '\n'
INSTALLER
    ;;
  *)
    printf 'unexpected curl url: %s\n' "$url" >&2
    exit 1
    ;;
esac
CURL
  chmod +x "$work/curl"
  (
    cd "$REPO_ROOT"
    env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
      HOME="$fake_home" \
      PATH="$work:$PATH" \
      bash -c 'cat install.sh | bash -s --' \
      </dev/null
  )
}

# Fake curl for the plugin-tag regression: a retired plugin-v* release remains on
# GitHub and is the newest in the list, so any resolver that trusts list order
# picks the wrong tag.
# Every requested URL is appended to $SMOKE_CURL_LOG so the scenario can assert
# which release tag each consumer path resolved.
make_plugin_tag_curl_shim() {
  local work
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  cat >"$work/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
# The URL is not always the last argument (asset downloads end with `-o <dest>`),
# so pick the https:// argument instead of a positional guess.
url=""
for arg in "$@"; do
  case "$arg" in
    https://*) url="$arg" ;;
  esac
done
printf '%s\n' "$url" >>"$SMOKE_CURL_LOG"
case "$url" in
  "https://api.github.com/repos/Sermilion/skill-bill/releases?per_page=100")
    cat <<'JSON'
[
  {
    "tag_name":"plugin-v9.9.9",
    "prerelease":false,
    "draft":false,
    "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/plugin-v9.9.9",
    "assets":[{"name":"skill-bill-intellij-plugin-9.9.9.zip"}]
  },
  {
    "tag_name":"v0.5.0-rc.1",
    "prerelease":true,
    "draft":false,
    "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.5.0-rc.1",
    "assets":[{"name":"skill-bill-skills-0.5.0-rc.1.tar.gz"}]
  },
  {
    "tag_name":"v0.4.0",
    "prerelease":false,
    "draft":false,
    "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.4.0",
    "assets":[{"name":"skill-bill-skills-0.4.0.tar.gz"}]
  }
]
JSON
    ;;
  https://api.github.com/repos/Sermilion/skill-bill/releases/tags/v0.5.0-rc.1)
    cat <<'JSON'
{
  "tag_name":"v0.5.0-rc.1",
  "prerelease":true,
  "draft":false,
  "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.5.0-rc.1",
  "assets":[{"name":"skill-bill-skills-0.5.0-rc.1.tar.gz"}]
}
JSON
    ;;
  https://api.github.com/repos/Sermilion/skill-bill/releases/tags/v0.4.0)
    cat <<'JSON'
{
  "tag_name":"v0.4.0",
  "prerelease":false,
  "draft":false,
  "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.4.0",
  "assets":[{"name":"skill-bill-skills-0.4.0.tar.gz"}]
}
JSON
    ;;
  https://raw.githubusercontent.com/Sermilion/skill-bill/*/install.sh)
    cat <<'INSTALLER'
#!/usr/bin/env bash
set -euo pipefail
printf 'release_args:'
for arg in "$@"; do
  printf ' [%s]' "$arg"
done
printf '\n'
INSTALLER
    ;;
  *)
    printf 'unexpected curl url: %s\n' "$url" >&2
    exit 1
    ;;
esac
CURL
  chmod +x "$work/curl"
  printf '%s' "$work"
}

bash "$REPO_ROOT/scripts/desktop_source_install_smoke_test.sh"

echo "=== install smoke test ==="
echo ""

TOKEN="$(host_token)"
STUB_TMP="$(mktemp)"
make_stub_runtime_cli "$STUB_TMP"
setup_release_dir "$TOKEN" "$STUB_TMP"

echo "--- scenario 1: piped main installer bootstraps latest release installer ---"
FAKE_HOME="$(mktemp -d)"
BOOTSTRAP_OUTPUT="$(run_piped_bootstrap_latest_release "$FAKE_HOME")"
pass "piped main installer bootstrapped latest release installer"

if [[ "$BOOTSTRAP_OUTPUT" == *"Standalone installer: using release installer v9.9.9."* ]]; then
  pass "bootstrap reports latest release tag"
else
  fail "bootstrap did not report latest release tag"
fi

if [[ "$BOOTSTRAP_OUTPUT" == *"release_bootstrap=1"* &&
  "$BOOTSTRAP_OUTPUT" == *"release_args: [--release] [v9.9.9]"* ]]; then
  pass "release installer receives pinned latest release args"
else
  fail "release installer args unexpected: $BOOTSTRAP_OUTPUT"
fi

echo ""
echo "--- scenario 2: blank interactive defaults ---"
FAKE_HOME="$(mktemp -d)"
INTERACTIVE_OUTPUT="$(run_interactive_install_with_blank_defaults "$FAKE_HOME")"
pass "interactive install with blank defaults exited 0"

if [[ "$INTERACTIVE_OUTPUT" == *"Agents:         claude, codex, junie, cursor"* ]]; then
  pass "blank agent selection defaults to all when none detected"
else
  fail "blank agent selection summary unexpected"
fi

if [[ "$INTERACTIVE_OUTPUT" == *"Platforms:      all"* ]]; then
  pass "blank defaults install every platform pack"
else
  fail "blank defaults platform summary unexpected"
fi

if [[ "$INTERACTIVE_OUTPUT" != *"Enter platforms"* &&
  "$INTERACTIVE_OUTPUT" != *"Available optional platforms"* ]]; then
  pass "interactive install has no platform selection prompt"
else
  fail "interactive install still prompts for platforms"
fi

assert_file_exists "blank interactive defaults install the desktop app" "$(desktop_app_target "$FAKE_HOME")"
if [[ "$INTERACTIVE_OUTPUT" == *"Install the desktop tray app?"* ]]; then
  fail "interactive install still prompts for the desktop app"
fi
pass "interactive install has no desktop app prompt"

echo ""
echo "--- scenario 3: piped install EOF defaults ---"
FAKE_HOME="$(mktemp -d)"
PIPED_OUTPUT="$(run_piped_install_with_eof_defaults "$FAKE_HOME")"
pass "piped install with EOF defaults exited 0"

if [[ "$PIPED_OUTPUT" == *"Agents:         claude, codex, junie, cursor"* ]]; then
  pass "piped EOF agent selection defaults to all when none detected"
else
  fail "piped EOF agent selection summary unexpected"
fi

if [[ "$PIPED_OUTPUT" == *"Platforms:      all"* ]]; then
  pass "piped EOF installs every platform pack"
else
  fail "piped EOF platform summary unexpected"
fi

assert_file_exists "piped EOF defaults install the desktop app" "$(desktop_app_target "$FAKE_HOME")"

echo ""
echo "--- scenario 4: replay of a base-only install ---"
FAKE_HOME="$(mktemp -d)"
mkdir -p "$FAKE_HOME/.claude"
seed_selection_json "$FAKE_HOME"

REPLAY_OUTPUT="$(run_install "$FAKE_HOME")"
pass "install.sh exited 0"

# A machine with no retired IDE plugins gets exactly one "nothing found" line.
NO_PLUGIN_LINES="$(grep -c 'No retired Skill Bill IDE plugins found\.' <<<"$REPLAY_OUTPUT" || true)"
if [[ "$NO_PLUGIN_LINES" == "1" ]]; then
  pass "clean install prints one 'no retired IDE plugins' line"
else
  fail "clean install printed $NO_PLUGIN_LINES 'no retired IDE plugins' lines"
fi
if [[ "$REPLAY_OUTPUT" == *"removed IntelliJ plugin"* ||
  "$REPLAY_OUTPUT" == *"removed VS Code extension"* ||
  "$REPLAY_OUTPUT" == *"desktop app replaces"* ]]; then
  fail "clean install reported a retired IDE plugin removal"
fi
pass "clean install reports no retired IDE plugin removal"

LAUNCHER="$FAKE_HOME/.local/bin/skill-bill"
assert_file_exists "launcher exists" "$LAUNCHER"

LAUNCHER_VERSION="$(HOME="$FAKE_HOME" "$LAUNCHER" --home "$FAKE_HOME" version 2>/dev/null || true)"
if [[ "$LAUNCHER_VERSION" == *"skill-bill"* ]]; then
  pass "skill-bill version succeeds"
else
  fail "skill-bill version output unexpected: '$LAUNCHER_VERSION'"
fi

SKILL_FIRST="$(find "$FAKE_HOME/.claude/skills" -mindepth 1 -maxdepth 1 2>/dev/null | head -n1 || true)"
if [[ -n "${SKILL_FIRST:-}" ]]; then
  pass "at least one skill installed under agent directory"
else
  fail "no skills found under $FAKE_HOME/.claude/skills"
fi

echo ""
echo "--- scenario 5: --clean flag (AC#2) ---"

seed_selection_json "$FAKE_HOME" selected '["kotlin"]'

SENTINEL="$FAKE_HOME/.skill-bill/skills/__smoke_extra_file__"
mkdir -p "$(dirname "$SENTINEL")"
printf 'extra' >"$SENTINEL"
assert_file_exists "sentinel planted" "$SENTINEL"

FAKE_AGENT_SKILL="$FAKE_HOME/.claude/skills/__smoke_stale_agent_skill__"
mkdir -p "$FAKE_AGENT_SKILL"
printf 'stale' >"$FAKE_AGENT_SKILL/content.md"
assert_file_exists "stale agent-dir skill planted" "$FAKE_AGENT_SKILL/content.md"

# Retired IDE plugin seeds. The IntelliJ root layout depends on the host OS.
if [[ "$(uname -s)" == "Darwin" ]]; then
  INTELLIJ_PRODUCT_ROOT="$FAKE_HOME/Library/Application Support/JetBrains/IntelliJIdea2024.3/plugins"
  WEBSTORM_PLUGINS_ROOT="$FAKE_HOME/Library/Application Support/JetBrains/WebStorm2024.3/plugins"
else
  INTELLIJ_PRODUCT_ROOT="$FAKE_HOME/.local/share/JetBrains/IntelliJIdea2024.3"
  WEBSTORM_PLUGINS_ROOT="$FAKE_HOME/.local/share/JetBrains/WebStorm2024.3"
fi
make_plugin_jar "$INTELLIJ_PRODUCT_ROOT/renamed-root/lib/status.jar" "dev.skillbill.status"
make_plugin_jar "$INTELLIJ_PRODUCT_ROOT/skill-bill-status/lib/status.jar" "com.example.other"
make_plugin_jar "$INTELLIJ_PRODUCT_ROOT/unrelated/lib/u.jar" "org.example.unrelated"
if [[ "$(id -u)" != "0" ]]; then
  make_plugin_jar "$WEBSTORM_PLUGINS_ROOT/stuck-root/lib/status.jar" "dev.skillbill.status"
  chmod a-w "$WEBSTORM_PLUGINS_ROOT"
  READONLY_ROOT="$WEBSTORM_PLUGINS_ROOT"
fi

mkdir -p "$FAKE_HOME/.vscode/extensions/skill-bill.skill-bill-status-0.1.0" \
  "$FAKE_HOME/.vscode/extensions/ms-python.python-2024.1.0" \
  "$FAKE_HOME/.cursor/extensions/skill-bill.skill-bill-status-0.1.0"
# The shim dir is prepended to the host PATH, so shadow `cursor` too: an
# unshadowed real CLI would run on a developer machine. A failing CLI and an
# absent CLI take the same directory-deletion fallback.
IDE_SHIM_DIR="$(mktemp -d)"
WORK_TMPDIRS+=("$IDE_SHIM_DIR")
CODE_CALLS="$IDE_SHIM_DIR/code.calls"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>"%s"\n' "$CODE_CALLS" >"$IDE_SHIM_DIR/code"
printf '#!/usr/bin/env bash\nexit 1\n' >"$IDE_SHIM_DIR/cursor"
chmod +x "$IDE_SHIM_DIR/code" "$IDE_SHIM_DIR/cursor"

SEEDED_OUTPUT="$(run_install "$FAKE_HOME" "PATH=$IDE_SHIM_DIR:$PATH" --clean)"
pass "install.sh --clean exited 0"
if [[ -n "$READONLY_ROOT" ]]; then chmod u+w "$READONLY_ROOT"; READONLY_ROOT=""; fi

assert_file_absent "retired IntelliJ plugin removed by plugin id" "$INTELLIJ_PRODUCT_ROOT/renamed-root"
assert_file_exists "directory named like the plugin but with another id stays" "$INTELLIJ_PRODUCT_ROOT/skill-bill-status"
assert_file_exists "unrelated IntelliJ plugin stays" "$INTELLIJ_PRODUCT_ROOT/unrelated"
[[ "$SEEDED_OUTPUT" == *"removed IntelliJ plugin (IntelliJIdea2024.3):"* ]] ||
  fail "IntelliJ removal line did not name the product directory"
pass "IntelliJ removal reports the product directory"
if [[ "$(id -u)" != "0" ]]; then
  [[ "$SEEDED_OUTPUT" == *"Could not remove"* ]] || fail "failed removal did not warn"
  pass "failed removal warns and the install still exits 0"
fi

grep -qF -- "--uninstall-extension skill-bill.skill-bill-status" "$CODE_CALLS" ||
  fail "code CLI was not asked to uninstall the extension"
pass "VS Code CLI received the uninstall request"
assert_file_absent "VS Code extension directory removed" "$FAKE_HOME/.vscode/extensions/skill-bill.skill-bill-status-0.1.0"
assert_file_absent "Cursor extension directory removed after its CLI failed" "$FAKE_HOME/.cursor/extensions/skill-bill.skill-bill-status-0.1.0"
assert_file_exists "unrelated VS Code extension stays" "$FAKE_HOME/.vscode/extensions/ms-python.python-2024.1.0"
[[ "$SEEDED_OUTPUT" == *"removed VS Code extension (VS Code):"* &&
  "$SEEDED_OUTPUT" == *"removed VS Code extension (Cursor):"* ]] ||
  fail "VS Code extension removal lines missing"
pass "VS Code extension removals are reported"
NOTICE_LINES="$(grep -c 'desktop app replaces' <<<"$SEEDED_OUTPUT" || true)"
[[ "$NOTICE_LINES" == "1" ]] || fail "expected one 'desktop app replaces' line, got $NOTICE_LINES"
pass "replacement notice printed once"

# install.sh and uninstall.sh each carry the cleanup block (uninstall.sh cannot
# source a shared file, because only uninstall.sh ships beside the skills).
extract_retired_cleanup_block() {
  sed -n '/^# >>> retired IDE plugin cleanup/,/^# <<< retired IDE plugin cleanup/p' "$1"
}
INSTALL_CLEANUP_BLOCK="$(extract_retired_cleanup_block "$INSTALL_SH")"
UNINSTALL_CLEANUP_BLOCK="$(extract_retired_cleanup_block "$REPO_ROOT/uninstall.sh")"
[[ -n "$INSTALL_CLEANUP_BLOCK" ]] || fail "install.sh has no retired IDE plugin cleanup block"
[[ "$INSTALL_CLEANUP_BLOCK" == "$UNINSTALL_CLEANUP_BLOCK" ]] ||
  fail "retired IDE plugin cleanup block differs between install.sh and uninstall.sh"
pass "retired IDE plugin cleanup block is identical in install.sh and uninstall.sh"

assert_file_absent "sentinel removed after --clean" "$SENTINEL"

assert_file_absent "stale agent-dir skill removed after --clean" "$FAKE_AGENT_SKILL"

SKILL_FIRST_CLEAN="$(find "$FAKE_HOME/.claude/skills" -mindepth 1 -maxdepth 1 2>/dev/null | head -n1 || true)"
if [[ -n "${SKILL_FIRST_CLEAN:-}" ]]; then
  pass "skills still present after --clean"
else
  fail "no skills found after --clean under $FAKE_HOME/.claude/skills"
fi

echo ""
echo "--- scenario 6: a local edit is overwritten by upstream (AC#3) ---"

TARGET_SKILL="$FAKE_HOME/.skill-bill/skills/skill-bill"
if [[ ! -d "$TARGET_SKILL" ]]; then
  TARGET_SKILL="$(find "$FAKE_HOME/.skill-bill/skills" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -n1 || true)"
fi
if [[ -z "${TARGET_SKILL:-}" ]]; then
  fail "no skill directory found under $FAKE_HOME/.skill-bill/skills to seed a local edit"
fi

CONTENT_FILE="$(find "$TARGET_SKILL" -name 'content.md' 2>/dev/null | head -n1 || true)"
if [[ -z "${CONTENT_FILE:-}" ]]; then
  CONTENT_FILE="$TARGET_SKILL/content.md"
  printf '# upstream content\n' >"$CONTENT_FILE"
fi

ORIGINAL_CONTENT="$(cat "$CONTENT_FILE")"
printf '\n# smoke local edit\n' >>"$CONTENT_FILE"
pass "local edit seeded in $(basename "$CONTENT_FILE") under $(basename "$TARGET_SKILL")"

env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
  HOME="$FAKE_HOME" \
  SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
  SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
  SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
  bash "$INSTALL_SH" --reuse-last-selection \
  </dev/null
pass "install.sh exited 0 with a locally edited skill present"

AFTER_CONTENT="$(cat "$CONTENT_FILE")"
if [[ "$AFTER_CONTENT" == "$ORIGINAL_CONTENT" ]]; then
  pass "upstream version restored (local edit overwritten)"
else
  fail "content not restored; file still contains local edit"
fi

echo ""
echo "--- scenario 7: piped install from a foreign dir ignores stray skills/ (AC#2) ---"
FAKE_HOME="$(mktemp -d)"
FOREIGN_DIR="$(mktemp -d)"
WORK_TMPDIRS+=("$FOREIGN_DIR")
mkdir -p "$FOREIGN_DIR/skills/__stray__"
printf 'stray local skill\n' >"$FOREIGN_DIR/skills/__stray__/content.md"

FOREIGN_OUTPUT="$(
  cd "$FOREIGN_DIR"
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$FAKE_HOME" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
    bash -c 'cat "$1" | bash -s --' _ "$INSTALL_SH" \
    </dev/null
)"
pass "piped install from foreign dir exited 0"

if [[ "$FOREIGN_OUTPUT" == *"Skills bundle extracted; PLUGIN_DIR set to"* ]]; then
  pass "piped install fetched the skills bundle despite a stray skills/ in CWD"
else
  fail "piped install did not fetch the skills bundle: $FOREIGN_OUTPUT"
fi

BUNDLE_PLUGIN_DIR="$(printf '%s\n' "$FOREIGN_OUTPUT" | sed -n 's/.*Skills bundle extracted; PLUGIN_DIR set to: //p' | tail -n1)"
if [[ -n "$BUNDLE_PLUGIN_DIR" && "$BUNDLE_PLUGIN_DIR" != "$FOREIGN_DIR" ]]; then
  pass "PLUGIN_DIR re-pointed away from the foreign CWD"
else
  fail "PLUGIN_DIR not re-pointed away from foreign CWD: '$BUNDLE_PLUGIN_DIR'"
fi

echo ""
echo "--- scenario 8: fresh machine footprint gate skips pre-install cleanup (AC#3) ---"
FAKE_HOME="$(mktemp -d)"

GATE_OUTPUT="$(
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$FAKE_HOME" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
    bash "$INSTALL_SH" \
    <<< $'\n\n\n\n'
)"
pass "fresh-machine interactive install exited 0"

if [[ "$GATE_OUTPUT" == *"No prior Skill Bill install detected"* ]]; then
  pass "footprint gate skipped pre-install cleanup on a fresh machine"
else
  fail "footprint gate message not found: $GATE_OUTPUT"
fi

if [[ "$GATE_OUTPUT" == *"Agents:"* ]]; then
  pass "fresh-machine install completed end-to-end"
else
  fail "fresh-machine install did not complete: $GATE_OUTPUT"
fi
assert_file_exists "fresh-machine defaults install the desktop app" \
  "$(desktop_app_target "$FAKE_HOME")"

echo ""
echo "--- scenario 9: replay automatically installs the checksum-verified desktop app ---"
FAKE_HOME="$(mktemp -d)"
seed_selection_json "$FAKE_HOME"
STATE_FIXTURE="$(mktemp -d)"
WORK_TMPDIRS+=("$STATE_FIXTURE")
STATE_FILES=(
  "smoke-workflow.db"
  "smoke-review.db"
  ".custom.db"
  "smoke-workflow.db-wal"
  "smoke-workflow.db-shm"
  "smoke-workflow.db-journal"
  "backups/snapshot.db"
  "backups/maintenance-report.json"
)
mkdir -p "$STATE_FIXTURE/backups" "$FAKE_HOME/.skill-bill"
for state_file in "${STATE_FILES[@]}"; do
  printf 'retained data for %s\n' "$state_file" > "$STATE_FIXTURE/$state_file"
done
cp -R "$STATE_FIXTURE/." "$FAKE_HOME/.skill-bill/"
run_install "$FAKE_HOME"
for state_file in "${STATE_FILES[@]}"; do
  cmp "$STATE_FIXTURE/$state_file" "$FAKE_HOME/.skill-bill/$state_file" || fail "reinstall changed $state_file"
done
pass "reinstall preserves databases, SQLite journals, and backups"
DESKTOP_APP="$(desktop_app_target "$FAKE_HOME")"
DESKTOP_EXECUTABLE="$(desktop_app_executable "$DESKTOP_APP")"
assert_file_exists "desktop app launcher installed" "$DESKTOP_EXECUTABLE"
if [[ "$TOKEN" == "windows-x64" ]]; then
  [[ -f "$FAKE_HOME/.local/bin/skillbill-desktop.cmd" ]] && pass "desktop app launcher installed" || fail "desktop app launcher is missing"
else
  [[ -x "$DESKTOP_EXECUTABLE" ]] && pass "desktop app launcher is executable" || fail "desktop app launcher is not executable"
  [[ -L "$FAKE_HOME/.local/bin/skillbill-desktop" ]] && pass "desktop app launcher symlink installed" || fail "desktop app launcher symlink is missing"
fi
if [[ "$TOKEN" == "linux-x64" ]]; then
  [[ -f "$FAKE_HOME/.local/share/applications/skillbill.desktop" ]] && pass "Linux desktop entry installed" || fail "Linux desktop entry is missing"
fi

echo ""
echo "--- scenario 10: desktop skip is rejected before install mutation ---"
DESKTOP_APP="$(desktop_app_target "$FAKE_HOME")"
DESKTOP_EXECUTABLE="$(desktop_app_executable "$DESKTOP_APP")"
mkdir -p "$(dirname "$DESKTOP_EXECUTABLE")"
printf 'existing app\n' > "$DESKTOP_EXECUTABLE"
if run_install "$FAKE_HOME" SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=0 --no-desktop-app; then
  fail "installer accepted a desktop skip option"
fi
[[ "$(cat "$DESKTOP_EXECUTABLE")" == "existing app" ]] && \
  pass "rejecting desktop skip preserves the existing install" || \
  fail "rejecting desktop skip changed the existing desktop install"

echo ""
echo "--- scenario 10b: missing desktop archive fails the full install ---"
DESKTOP_ARCHIVE="$RELEASE_DIR/skill-bill-desktop-0.0.0-smoke-${TOKEN}.tar.gz"
mv "$DESKTOP_ARCHIVE" "$DESKTOP_ARCHIVE.unavailable"
DESKTOP_FAILURE_STATUS=0
DESKTOP_FAILURE_OUTPUT="$(run_install "$FAKE_HOME")" || DESKTOP_FAILURE_STATUS=$?
mv "$DESKTOP_ARCHIVE.unavailable" "$DESKTOP_ARCHIVE"
[[ "$DESKTOP_FAILURE_STATUS" -ne 0 ]] || fail "installer succeeded without its desktop archive"
[[ "$DESKTOP_FAILURE_OUTPUT" == *"the desktop app could not be staged"* ]] || fail "desktop failure was not reported"
[[ "$DESKTOP_FAILURE_OUTPUT" != *"Installation complete"* ]] || fail "failed desktop install reported completion"
[[ "$(cat "$DESKTOP_EXECUTABLE")" == "existing app" ]] || fail "missing desktop archive changed the existing app"
pass "missing desktop archive fails installation and preserves the existing app"

mkdir -p "$FAKE_HOME/.skill-bill/installed-skills" "$FAKE_HOME/.skill-bill/.stale-cache"
printf 'stale install state\n' > "$FAKE_HOME/.skill-bill/.stale-cache/entry"
env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
  HOME="$FAKE_HOME" \
  XDG_DATA_HOME="$FAKE_HOME/.local/share" \
  LOCALAPPDATA="$FAKE_HOME/AppData/Local" \
  SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
  SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1 \
  bash "$REPO_ROOT/uninstall.sh"
for state_file in "${STATE_FILES[@]}"; do
  cmp "$STATE_FIXTURE/$state_file" "$FAKE_HOME/.skill-bill/$state_file" || fail "uninstall changed $state_file"
done
pass "uninstall preserves databases, SQLite journals, and backups"
for install_entry in runtime installed-skills skills platform-packs orchestration agent-addons baseline-manifest.json .stale-cache; do
  assert_file_absent "uninstall removes $install_entry" "$FAKE_HOME/.skill-bill/$install_entry"
done
assert_file_absent "uninstall removes the desktop app" "$DESKTOP_APP"
if [[ "$TOKEN" == "windows-x64" ]]; then
  assert_file_absent "uninstall removes the desktop launcher" "$FAKE_HOME/.local/bin/skillbill-desktop.cmd"
else
  assert_file_absent "uninstall removes the desktop launcher" "$FAKE_HOME/.local/bin/skillbill-desktop"
  [[ ! -L "$FAKE_HOME/.local/bin/skillbill-desktop" ]] || fail "uninstall left a dangling desktop launcher"
fi
if [[ "$TOKEN" == "linux-x64" ]]; then
  assert_file_absent "uninstall removes the Linux desktop entry" "$FAKE_HOME/.local/share/applications/skillbill.desktop"
fi

echo ""
echo "--- scenario 11: newest runtime prerelease wins while plugin releases are skipped ---"
PLUGIN_SHIM_DIR="$(make_plugin_tag_curl_shim)"
PLUGIN_CURL_LOG="$PLUGIN_SHIM_DIR/curl.log"
: >"$PLUGIN_CURL_LOG"

FAKE_HOME="$(mktemp -d)"
PLUGIN_TAG_OUTPUT="$(
  cd "$REPO_ROOT"
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$FAKE_HOME" \
    PATH="$PLUGIN_SHIM_DIR:$PATH" \
    SMOKE_CURL_LOG="$PLUGIN_CURL_LOG" \
    bash -c 'cat install.sh | bash -s --' \
    </dev/null
)"

if [[ "$PLUGIN_TAG_OUTPUT" == *"release installer v0.5.0-rc.1."* &&
  "$PLUGIN_TAG_OUTPUT" == *"release_args: [--release] [v0.5.0-rc.1]"* ]]; then
  pass "installer-tag resolution picked the newest runtime prerelease over the plugin release"
else
  fail "installer-tag resolution did not pick v0.5.0-rc.1: $PLUGIN_TAG_OUTPUT"
fi

# The asset-listing path resolves the tag and then queries /releases/tags/<tag>;
# the shim has no asset download URL, so the run aborts after that request. The
# recorded URL is the assertion.
FAKE_HOME="$(mktemp -d)"
PLUGIN_ASSET_DIR="$(mktemp -d)"
WORK_TMPDIRS+=("$PLUGIN_ASSET_DIR")
: >"$PLUGIN_CURL_LOG"
(
  cd "$PLUGIN_ASSET_DIR"
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    HOME="$FAKE_HOME" \
    PATH="$PLUGIN_SHIM_DIR:$PATH" \
    SMOKE_CURL_LOG="$PLUGIN_CURL_LOG" \
    SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED=1 \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
    bash -c 'cat "$1" | bash -s --' _ "$INSTALL_SH" \
    </dev/null
) >/dev/null 2>&1 || true

PLUGIN_ASSET_URLS="$(cat "$PLUGIN_CURL_LOG")"
if [[ "$PLUGIN_ASSET_URLS" == *"/releases/tags/v0.5.0-rc.1"* ]]; then
  pass "release-asset listing queried the newest runtime prerelease"
else
  fail "release-asset listing did not query /releases/tags/v0.4.0: $PLUGIN_ASSET_URLS"
fi

if [[ "$PLUGIN_ASSET_URLS" != *"plugin-v9.9.9"* ]]; then
  pass "release-asset listing never requested the plugin-v* tag"
else
  fail "release-asset listing requested the plugin tag: $PLUGIN_ASSET_URLS"
fi

# The downloader must pin the same resolved tag rather than follow GitHub's
# latest-release redirect, which the plugin-v* release would win.
if [[ "$PLUGIN_ASSET_URLS" != *"/releases/latest/download/"* ]]; then
  pass "asset download never used the latest-release redirect"
else
  fail "asset download used the latest-release redirect: $PLUGIN_ASSET_URLS"
fi

if [[ "$PLUGIN_ASSET_URLS" == *"/releases/download/v0.5.0-rc.1/"* ]]; then
  pass "asset download pinned the resolved runtime prerelease"
else
  fail "asset download did not pin v0.5.0-rc.1: $PLUGIN_ASSET_URLS"
fi

echo ""
echo "=== all scenarios passed ==="
