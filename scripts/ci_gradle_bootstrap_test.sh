#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_dir="$(mktemp -d)"
trap 'rm -rf "$task_dir"' EXIT
mkdir -p "$task_dir/project with spaces" "$task_dir/bin"
export FAKE_CALLS_FILE="$task_dir/calls" FAKE_WAITS_FILE="$task_dir/waits"
export PATH="$task_dir/bin:$PATH"

cat > "$task_dir/bin/sleep" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_WAITS_FILE"
STUB
cat > "$task_dir/project with spaces/gradlew" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 1 && "$1" == --version ]]
printf 'bootstrap\n' >> "$FAKE_CALLS_FILE"
if [[ "$FAKE_CASE" == success ]] ||
  [[ "$FAKE_CASE" == recover && "$(wc -l < "$FAKE_CALLS_FILE")" -gt 1 ]]; then
  printf 'Gradle 9.3.0\n'
  exit 0
fi
printf 'Downloading https://downloads.gradle.org/distributions/gradle-9.3.0-bin.zip\r\n'
case "$FAKE_CASE" in
  recover|exhaust)
    printf 'Exception in thread "main" java.io.IOException: Server returned HTTP response code: %s for URL: https://github.com/gradle/gradle-distributions/releases/download/v9.3.0/gradle-9.3.0-bin.zip\n' "$FAKE_HTTP_STATUS"
    printf '\tat org.gradle.wrapper.Install.forceFetch(SourceFile:2)\n'
    ;;
  permanent)
    printf 'Exception in thread "main" java.io.IOException: Server returned HTTP response code: 404 for URL: https://downloads.gradle.org/distributions/gradle-9.3.0-bin.zip\n'
    printf '\tat org.gradle.wrapper.Install.forceFetch(SourceFile:2)\n'
    ;;
  checksum)
    printf 'Verification of Gradle distribution failed!\n'
    printf '\tat org.gradle.wrapper.Install.verifyDownloadChecksum(SourceFile:2)\n'
    ;;
  dependency)
    printf 'Execution failed for task compileKotlin.\n'
    printf 'java.io.IOException: Server returned HTTP response code: 504 for URL: https://repo.maven.apache.org/compiler.jar\n'
    ;;
esac
exit 7
STUB
chmod +x "$task_dir/bin/sleep" "$task_dir/project with spaces/gradlew"

check_case() {
  export FAKE_CASE="$1"
  export FAKE_HTTP_STATUS="${5:-504}"
  local expected_status="$2" expected_calls="$3" expected_waits="$4" actual_status=0
  : > "$FAKE_CALLS_FILE"
  : > "$FAKE_WAITS_FILE"
  bash "$repo_root/scripts/ci_gradle_bootstrap.sh" "$task_dir/project with spaces" > "$task_dir/output" 2>&1 || actual_status=$?
  if [[ "$actual_status" -ne "$expected_status" ||
    "$(wc -l < "$FAKE_CALLS_FILE")" -ne "$expected_calls" ||
    "$(wc -l < "$FAKE_WAITS_FILE")" -ne "$expected_waits" ]]; then
    cat "$task_dir/output" >&2
    printf 'Unexpected bootstrap result for %s.\n' "$FAKE_CASE" >&2
    exit 1
  fi
}

check_case success 0 1 0
check_case recover 0 2 1 502
check_case exhaust 7 3 2
for failure in permanent checksum dependency; do
  check_case "$failure" 7 1 0
done
printf 'Gradle bootstrap retry policy passed.\n'
