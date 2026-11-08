#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  printf 'Usage: bash scripts/ci_gradle_bootstrap.sh <project-directory> [...]\n' >&2
  exit 2
fi

output_file="$(mktemp)"
trap 'rm -f "$output_file"' EXIT

for project_dir in "$@"; do
  for attempt in 1 2 3; do
    if (cd "$project_dir" && ./gradlew --version) >"$output_file" 2>&1; then
      cat "$output_file"
      break
    else
      status=$?
    fi
    cat "$output_file"
    if [[ "$attempt" -eq 3 ]] ||
      ! grep -qE '^Downloading https?://[^[:space:]]+\.zip([?][^[:space:]]*)?[[:space:]]*$' "$output_file" ||
      ! grep -qE 'at org\.gradle\.wrapper\.' "$output_file" ||
      ! grep -qE 'java\.io\.IOException: Server returned HTTP response code: (408|429|500|502|503|504) for URL:|java\.net\.SocketTimeoutException:' "$output_file"; then
      printf 'Gradle bootstrap failed for %s on attempt %s; no further retries.\n' "$project_dir" "$attempt" >&2
      exit "$status"
    fi
    printf 'Transient distribution download failure for %s on attempt %s; retrying.\n' "$project_dir" "$attempt" >&2
    sleep "$((attempt * 5))"
  done
done
