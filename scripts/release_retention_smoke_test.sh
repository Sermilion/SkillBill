#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

cat > "$work_dir/gh" <<'GH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == "release list" ]]; then
  printf '%s\n' v0.8.0-rc.1 v0.7.0 v0.6.0 plugin-v9.0.0
  exit 0
fi
if [[ "$1 $2" == "release view" ]]; then
  case "$3" in
    v0.6.0|v0.7.0)
      printf 'runtime-cli-%s.zip\nruntime-mcp-%s.zip\nskill-bill-desktop-%s.tar.gz\nskill-bill-skills-%s.tar.gz\n' "$3" "$3" "$3" "$3"
      ;;
    *)
      :
      ;;
  esac
  exit 0
fi
if [[ "$1 $2" == "release delete-asset" ]]; then
  printf '%s/%s\n' "$3" "$4" >> "$GH_DELETE_LOG"
  exit 0
fi
echo "Unexpected gh invocation: $*" >&2
exit 1
GH
chmod +x "$work_dir/gh"

export PATH="$work_dir:$PATH"
export GH_DELETE_LOG="$work_dir/deleted-assets.log"
: > "$GH_DELETE_LOG"

"$repo_root/scripts/prune_old_release_assets" Sermilion/SkillBill v0.8.0-rc.1

expected="$(printf '%s\n' \
  'v0.6.0/runtime-cli-v0.6.0.zip' \
  'v0.6.0/runtime-mcp-v0.6.0.zip' \
  'v0.6.0/skill-bill-desktop-v0.6.0.tar.gz' \
  'v0.6.0/skill-bill-skills-v0.6.0.tar.gz' \
  'v0.7.0/runtime-cli-v0.7.0.zip' \
  'v0.7.0/runtime-mcp-v0.7.0.zip' \
  'v0.7.0/skill-bill-desktop-v0.7.0.tar.gz' \
  'v0.7.0/skill-bill-skills-v0.7.0.tar.gz' | sort)"
actual="$(sort "$GH_DELETE_LOG")"
if [[ "$actual" != "$expected" ]]; then
  printf 'Unexpected old-release deletions.\nExpected:\n%s\nActual:\n%s\n' "$expected" "$actual" >&2
  exit 1
fi

printf '[PASS] older runtime release assets are deleted while the latest and plugin releases are kept\n'

: > "$GH_DELETE_LOG"
"$repo_root/scripts/prune_old_release_assets" Sermilion/SkillBill v0.7.0
if [[ -s "$GH_DELETE_LOG" ]]; then
  echo "A non-latest runtime tag deleted release assets." >&2
  exit 1
fi

printf '[PASS] an older release rerun cannot prune downloads for a newer stable release\n'
