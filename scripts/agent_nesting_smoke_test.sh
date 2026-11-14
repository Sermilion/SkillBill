#!/usr/bin/env bash
# 3-level Agent tool nesting smoke test for the runtime goal path (SKILL-83 nesting gate).
# Verifies: Level-0 (claude -p session) can spawn Level-1 (Agent tool) which can spawn
# Level-2 (Agent tool), that the Level-2 sentinel propagates back to Level-0, and that
# Level-1 has access to the sona MCP tools used by the runtime feature/goal family.
#
# Subject: runtime goal / feature-task path (not a deleted prose orchestrator). This remains
# a nesting capability probe for Agent-tool depth plus MCP reachability. It must not require
# the retired prose mode selector, the retired prose feature skill, or any retired prose MCP tool.
#
# Usage: scripts/agent_nesting_smoke_test.sh
# Env overrides: CLAUDE_BIN, NESTING_TEST_TIMEOUT (default 240s)
#
# Requires:
#   - claude CLI on PATH (or CLAUDE_BIN env var pointing to it)
#   - ./install.sh must have run so MCP tools are registered for sona
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
TIMEOUT_SECS="${NESTING_TEST_TIMEOUT:-240}"

command -v "$CLAUDE_BIN" >/dev/null 2>&1 || {
  echo "FATAL: claude CLI not found — install Claude Code and ensure it is on PATH" >&2
  exit 2
}

SENTINEL="SKILL83_L2_$(date +%s)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "── agent nesting smoke test (runtime goal path) ──────────────────"
printf "  claude:   %s\n" "$("$CLAUDE_BIN" --version 2>/dev/null)"
printf "  sentinel: %s\n" "$SENTINEL"
printf "  timeout:  %ss\n" "$TIMEOUT_SECS"
echo

L2_SENTINEL="$SENTINEL"

cat >"$TMP/l2_prompt.txt" <<EOF
Output exactly the following string and nothing else, with no surrounding text, quotes, or explanation:
${L2_SENTINEL}
EOF

cat >"$TMP/l1_prompt.txt" <<EOF
You are a Level-1 nesting smoke test agent for the runtime goal path. Complete these two steps and then output the result line.

STEP 1 — spawn Level-2 agent:
Use the Agent tool. Pass this exact text as the prompt (copy it verbatim, no changes):
$(cat "$TMP/l2_prompt.txt")

Record the full text returned by Level-2.

STEP 2 — verify MCP access:
Call the feature_verify_workflow_list MCP tool. If the tool does not exist or is not available, try calling goal_stats (it may return an error response — that is fine as long as the tool call itself was accepted). Record whether the MCP call was accepted (even returning an error result is "ok") or whether the tool itself was not available.

After both steps, output EXACTLY this single line as your FINAL output and nothing else before or after it:
L1_RESULT|<level2_text>|<mcp_ok_or_mcp_err>

Where:
  <level2_text>     = the exact text Level-2 returned (no quotes around it)
  <mcp_ok_or_mcp_err> = "mcp_ok" if any MCP tool call was accepted, "mcp_err" if no sona MCP tool was available
EOF

cat >"$TMP/l0_prompt.txt" <<EOF
You are a Level-0 nesting smoke test agent for the runtime goal path. Complete this one step.

STEP 1 — spawn Level-1 agent:
Use the Agent tool with the following prompt (copy it verbatim, preserving all lines):
$(cat "$TMP/l1_prompt.txt")

After Level-1 returns, find the line in its output that starts with "L1_RESULT|".
Output EXACTLY this single line as your FINAL output and nothing else:
SMOKE_RESULT|<copy the full L1_RESULT|... line here>

Replace <copy the full L1_RESULT|... line here> with the entire "L1_RESULT|..." line from Level-1's output.
EOF

echo "  Invoking Level-0 claude session (may take up to ${TIMEOUT_SECS}s) ..."
claude_out=""
claude_rc=0
claude_out="$(cd "$REPO_ROOT" && timeout "$TIMEOUT_SECS" "$CLAUDE_BIN" -p "$(cat "$TMP/l0_prompt.txt")" 2>"$TMP/err.txt")" || claude_rc=$?

echo "  claude exit: $claude_rc"
if [[ -s "$TMP/err.txt" ]]; then
  echo "  stderr (head 5):"
  head -5 "$TMP/err.txt" | sed 's/^/    /'
fi
echo

declare -a RESULTS
overall=0

chk() {
  local name="$1" ok="$2" detail="${3:-}"
  RESULTS+=("$name|$ok|$detail")
  [[ "$ok" -eq 1 ]] || overall=1
}

chk "claude_cli_succeeded" "$([[ $claude_rc -eq 0 ]] && echo 1 || echo 0)" "exit=$claude_rc"

smoke_line="$(printf '%s\n' "$claude_out" | grep -o 'SMOKE_RESULT|.*' | head -1 || true)"
chk "level0_emitted_smoke_result" "$([[ -n "$smoke_line" ]] && echo 1 || echo 0)" "got=${smoke_line:-<none>}"

l1_line="$(printf '%s\n' "$smoke_line" | sed 's/^SMOKE_RESULT|//')"
l2_got="$(printf '%s\n' "$l1_line" | cut -d'|' -f2)"
mcp_got="$(printf '%s\n' "$l1_line" | cut -d'|' -f3)"

chk "level1_l1_result_present" "$([[ -n "$l1_line" && "$l1_line" == L1_RESULT* ]] && echo 1 || echo 0)" "l1_line=${l1_line:-<none>}"
chk "level2_sentinel_propagated" "$([[ "$l2_got" == "$L2_SENTINEL" ]] && echo 1 || echo 0)" "expected=$L2_SENTINEL got=${l2_got:-<none>}"
chk "level1_mcp_accessible" "$([[ "$mcp_got" == "mcp_ok" ]] && echo 1 || echo 0)" "mcp_status=${mcp_got:-<none>}"

echo "  ── check results ──"
for entry in "${RESULTS[@]}"; do
  IFS='|' read -r name ok detail <<< "$entry"
  tag="PASS"
  [[ "$ok" -eq 1 ]] || tag="FAIL"
  suf=""
  { [[ "$ok" -eq 1 ]] || [[ -z "$detail" ]]; } || suf=" — $detail"
  printf "    [%s] %s%s\n" "$tag" "$name" "$suf"
done

echo
echo "════ nesting smoke test summary ════"
if [[ $overall -eq 0 ]]; then
  echo "  PASS — 3-level Agent nesting verified on the runtime goal path."
  echo "  Level-1 MCP tools accessible. Nesting capability probe passed."
else
  echo "  FAIL — 3-level Agent nesting could NOT be verified."
  echo
  echo "  Nesting capability probe failed. Investigate Agent-tool depth limits"
  echo "  or MCP registration before relying on multi-level Agent spawn."
  echo
  if [[ -n "$claude_out" ]]; then
    echo "  Level-0 output (head 20):"
    printf '%s\n' "$claude_out" | head -20 | sed 's/^/    /'
  fi
fi
exit $overall
