#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' '##app-run failure usage' >&2
  exit 64
}

failure() {
  printf '##app-run failure %s\n' "$1" >&2
}

sdk_root_from_checkout() {
  local checkout=${APP_RUN_CHECKOUT:-}
  [[ -n "$checkout" && -f "$checkout/local.properties" ]] || return 1
  local sdk_dir
  sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$checkout/local.properties" | head -n 1)
  [[ -n "$sdk_dir" ]] || return 1
  printf '%s\n' "$sdk_dir"
}

find_emulator() {
  local root
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sdk_root_from_checkout 2>/dev/null || true)"; do
    [[ -n "$root" && -x "$root/emulator/emulator" ]] && { printf '%s\n' "$root/emulator/emulator"; return 0; }
  done
  return 1
}

find_adb() {
  local root
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sdk_root_from_checkout 2>/dev/null || true)"; do
    [[ -n "$root" && -x "$root/platform-tools/adb" ]] && { printf '%s\n' "$root/platform-tools/adb"; return 0; }
  done
  command -v adb
}

boot() {
  local avd=${APP_RUN_AVD:-}
  [[ -n "$avd" ]] || usage
  local emulator adb port serial log started
  emulator=$(find_emulator) || { failure boot-failed; exit 5; }
  adb=$(find_adb) || { failure boot-failed; exit 5; }
  "$emulator" -list-avds | grep -Fxq "$avd" || { failure boot-failed; exit 5; }
  port=5554
  while (( port <= 5682 )); do
    if ! "$adb" devices | grep -Eq "^emulator-$port[[:space:]]"; then break; fi
    port=$((port + 2))
  done
  (( port <= 5682 )) || { failure boot-failed; exit 5; }
  serial="emulator-$port"
  log=$(mktemp)
  local -a args=(-avd "$avd" -port "$port" -no-snapshot-save -no-boot-anim)
  if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then args+=(-no-window); fi
  nohup "$emulator" "${args[@]}" >"$log" 2>&1 </dev/null &
  started=$SECONDS
  while (( SECONDS - started < 300 )); do
    if [[ "$("$adb" -s "$serial" get-state 2>/dev/null || true)" == device &&
      "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]]; then
      "$adb" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || { rm -f "$log"; failure boot-failed; exit 5; }
      rm -f "$log"
      printf '##app-run serial %s\n' "$serial"
      return 0
    fi
    sleep 1
  done
  rm -f "$log"
  failure boot-failed
  exit 5
}

find_app_modules() {
  python3 - <<'PY'
import os
import pathlib
import re

ignored = {".git", ".gradle", "build", "node_modules"}
plugin_id = re.compile(r"\bid\s*\(?\s*['\"]com\.android\.application['\"]")
plugin_alias = re.compile(r"\balias\s*\(\s*libs\.plugins\.[\w.-]*(?:android\.application|androidApplication)[\w.-]*\s*\)")
declaration = re.compile(r"^\s*(?:id\b|alias\s*\()")
not_applied = re.compile(r"\bapply\s*(?:\(\s*false\s*\)|\s+false\b)")
groovy_plugin = re.compile(r"(?m)^\s*apply\s+plugin\s*:\s*['\"]com\.android\.application['\"]")
kotlin_plugin = re.compile(r"(?m)^\s*apply\s*\(\s*plugin\s*=\s*['\"]com\.android\.application['\"]")
modules = set()

for directory, subdirectories, filenames in os.walk("."):
    subdirectories[:] = [name for name in subdirectories if name not in ignored]
    for filename in filenames:
        if filename not in {"build.gradle", "build.gradle.kts"}:
            continue
        path = pathlib.Path(directory, filename)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        text = re.sub(r"(?m)//.*$", "", text)
        applied = bool(groovy_plugin.search(text) or kotlin_plugin.search(text))
        for block in re.findall(r"\bplugins\s*\{([^}]*)\}", text, flags=re.S):
            lines = block.splitlines()
            index = 0
            while index < len(lines):
                if not declaration.match(lines[index]):
                    index += 1
                    continue
                statement = [lines[index]]
                index += 1
                while index < len(lines) and not declaration.match(lines[index]):
                    statement.append(lines[index])
                    index += 1
                statement = " ".join(statement)
                if not_applied.search(statement):
                    continue
                if plugin_id.search(statement) or plugin_alias.search(statement):
                    applied = True
        if applied:
            relative = path.parent.as_posix().removeprefix("./")
            if relative != ".":
                modules.add(relative.replace("/", ":"))

for module in sorted(modules):
    print(module)
PY
}

application_id_for_variant() {
  local apk_root=$1 variant=$2
  python3 - "$apk_root" "$variant" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
variant = sys.argv[2]
matches = []
for path in root.rglob("output-metadata.json"):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise SystemExit(1)
    if isinstance(data, dict) and data.get("variantName") == variant:
        matches.append(data)
if len(matches) == 1:
    app_id = matches[0].get("applicationId")
    if isinstance(app_id, str) and app_id:
        print(app_id)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

run_app() {
  local checkout=${APP_RUN_CHECKOUT:-} serial=${ANDROID_SERIAL:-} platform=${APP_RUN_PLATFORM:-}
  local module=${APP_RUN_MODULE:-} variant=${APP_RUN_VARIANT:-debug} adb
  [[ -n "$checkout" && -n "$serial" && -n "$platform" ]] || usage
  [[ "$platform" == android ]] || usage
  [[ "$variant" =~ ^[a-z][A-Za-z0-9]{0,63}$ ]] || usage
  adb=$(find_adb) || { failure no-launcher-activity; exit 4; }
  cd "$checkout" || usage
  if [[ -z "$module" ]]; then
    local -a modules=()
    while IFS= read -r candidate; do [[ -n "$candidate" ]] && modules+=("$candidate"); done < <(find_app_modules)
    if (( ${#modules[@]} == 0 )); then failure no-app-module; exit 2; fi
    if (( ${#modules[@]} > 1 )); then
      printf '##app-run candidates <%s>\n' "$(IFS=,; printf '%s' "${modules[*]/#/:}")"
      exit 2
    fi
    module=${modules[0]}
  fi
  module=${module#:}
  local capitalized task output status failed_task="unknown"
  capitalized=$(printf '%s' "${variant:0:1}" | tr '[:lower:]' '[:upper:]')${variant:1}
  task=":${module}:install${capitalized}"
  printf '##app-run step build %s\n' "$task"
  [[ -x ./gradlew ]] || { printf '##app-run failed-task unknown\n'; exit 3; }
  output=$(mktemp)
  set +e
  ./gradlew "$task" 2>&1 | tee "$output"
  status=${PIPESTATUS[0]}
  set -e
  if (( status != 0 )); then
    failed_task=$(sed -n 's/^> Task \([^ ]*\) FAILED.*/\1/p' "$output" | head -n 1)
    [[ -n "$failed_task" ]] || failed_task=unknown
    rm -f "$output"
    printf '##app-run failed-task %s\n' "$failed_task"
    exit 3
  fi
  rm -f "$output"
  printf '##app-run step launch\n'
  local app_id apk_root activity candidate component package_name
  local module_directory=${module//:/\/}
  apk_root="$checkout/$module_directory/build/outputs/apk"
  app_id=$(application_id_for_variant "$apk_root" "$variant") || { failure application-id-unreadable; exit 4; }
  activity=""
  while IFS= read -r candidate; do
    candidate=${candidate%%[[:space:]]*}
    [[ "$candidate" == "$app_id/"* ]] || continue
    package_name=${candidate%%/*}
    component=${candidate#*/}
    [[ "$component" == .* ]] && component="$package_name$component"
    [[ "$component" == leakcanary.* ]] && continue
    activity="$package_name/$component"
    break
  done < <("$adb" -s "$serial" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null || true)
  [[ -n "$activity" ]] || { failure no-launcher-activity; exit 4; }
  "$adb" -s "$serial" shell am start -n "$activity" >/dev/null || { failure not-resumed; exit 4; }
  local started=$SECONDS resumed=false
  while (( SECONDS - started < 20 )); do
    if "$adb" -s "$serial" shell dumpsys activity activities 2>/dev/null | grep -F "topResumedActivity" | grep -Fq "$package_name/"; then
      resumed=true
      break
    fi
    sleep 1
  done
  [[ "$resumed" == true ]] || { failure not-resumed; exit 4; }
  printf '##app-run launched %s\n' "$activity"
}

case "${1:-}" in
  boot) [[ $# == 1 ]] || usage; boot ;;
  run) [[ $# == 1 ]] || usage; run_app ;;
  *) usage ;;
esac
