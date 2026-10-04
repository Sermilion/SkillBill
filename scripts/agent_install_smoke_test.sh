#!/usr/bin/env bash
# Per-agent install smoke test: runs `install apply` into a throwaway home for each
# agent, then asserts skills landed, native subagents linked in the right format, and
# MCP registration did not fail. Reuses the already-installed runtime (no download) and
# never touches the caller's real agent directories.
#
# Usage: scripts/agent_install_smoke_test.sh [agent ...]   (default: all seven)
#   SKILL_BILL_BIN, SKILL_BILL_RUNTIME_ROOT override discovery.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${SKILL_BILL_BIN:-$HOME/.local/bin/skill-bill}"
RUNTIME_ROOT="${SKILL_BILL_RUNTIME_ROOT:-$HOME/.skill-bill/runtime}"
MCP_BIN="$RUNTIME_ROOT/runtime-mcp/bin/runtime-mcp"
if [[ $# -gt 0 ]]; then AGENTS=("$@"); else AGENTS=(claude codex junie cursor); fi

[[ -x "$BIN" ]] || { echo "FATAL: skill-bill not executable at $BIN" >&2; exit 2; }
[[ -d "$RUNTIME_ROOT" ]] || { echo "FATAL: no installed runtime at $RUNTIME_ROOT (run ./install.sh first)" >&2; exit 2; }
[[ -x "$MCP_BIN" ]] || echo "WARN: mcp bin missing at $MCP_BIN — MCP checks will report failure" >&2

declare -a RESULTS
overall=0

MCP_SMOKE_ROOT="$(mktemp -d)"
python3 - "$MCP_BIN" "$MCP_SMOKE_ROOT" <<'PY'
import json
import os
import subprocess
import sys

mcp_bin, smoke_root = sys.argv[1:]
db_path = os.path.join(smoke_root, "metrics.db")
env = os.environ.copy()
env["SKILL_BILL_REVIEW_DB"] = db_path
requests = [
    {"jsonrpc": "2.0", "id": "initialize", "method": "initialize", "params": {}},
    {"jsonrpc": "2.0", "id": "list", "method": "tools/list", "params": {}},
    {
        "jsonrpc": "2.0",
        "id": "valid",
        "method": "tools/call",
        "params": {"name": "doctor", "arguments": {}},
    },
    {
        "jsonrpc": "2.0",
        "id": "invalid",
        "method": "tools/call",
        "params": {"name": "feature_verify_workflow_get", "arguments": {"unexpected": True}},
    },
    {
        "jsonrpc": "2.0",
        "id": "open",
        "method": "tools/call",
        "params": {
            "name": "feature_verify_workflow_open",
            "arguments": {
                "issue_key": "SMOKE-1",
                "repository_identity": "repo-root-realpath-v1:/install-smoke",
                "governed_spec_path": ".feature-specs/SMOKE-1/spec.md",
            },
        },
    },
    {
        "jsonrpc": "2.0",
        "id": "unknown",
        "method": "tools/call",
        "params": {"name": "feature_task_runtime_stats", "arguments": {}},
    },
]

def payload(response):
    result = response["result"]
    text = result["content"][0]["text"]
    return result, json.loads(text)

try:
    completed = subprocess.run(
        [mcp_bin],
        input="\n".join(json.dumps(request) for request in requests) + "\n",
        text=True,
        capture_output=True,
        env=env,
        timeout=30,
        check=False,
    )
    responses = {response["id"]: response for response in map(json.loads, completed.stdout.splitlines())}
    checks = []
    checks.append(("process_exit_0", completed.returncode == 0))
    checks.append(("initialize", responses["initialize"]["result"]["serverInfo"]["name"] == "skill-bill"))
    names = [tool["name"] for tool in responses["list"]["result"]["tools"]]
    prose_tools = [
        n for n in names
        if n.startswith("feature_task" + "_prose_")
        or n.startswith("goal" + "_prose_")
        or n.startswith("feature" + "_implement_")
    ]
    checks.append(("tools_list", "doctor" in names and "feature_task_runtime_stats" not in names))
    checks.append(("no_prose_mcp_tools", not prose_tools))
    valid_result, valid_payload = payload(responses["valid"])
    checks.append(
        (
            "valid_tool_call",
            valid_result["isError"] is False and valid_payload["version"] and valid_payload["db_path"] == db_path,
        )
    )
    invalid_result, invalid_payload = payload(responses["invalid"])
    checks.append(
        (
            "invalid_arguments",
            invalid_result["isError"] is True and "unexpected" in invalid_payload["error"],
        )
    )
    open_result, open_payload = payload(responses["open"])
    workflow_id = open_payload["workflow_id"]
    checks.append(("persisting_tool_call", open_result["isError"] is False and os.path.isfile(db_path)))

    get_request = {
        "jsonrpc": "2.0",
        "id": "get",
        "method": "tools/call",
        "params": {
            "name": "feature_verify_workflow_get",
            "arguments": {"workflow_id": workflow_id},
        },
    }
    persisted = subprocess.run(
        [mcp_bin],
        input=json.dumps(get_request) + "\n",
        text=True,
        capture_output=True,
        env=env,
        timeout=30,
        check=False,
    )
    get_result, get_payload = payload(json.loads(persisted.stdout.strip()))
    checks.append(
        (
            "persistence_round_trip",
            persisted.returncode == 0
            and get_result["isError"] is False
            and get_payload["workflow_id"] == workflow_id,
        )
    )
    unknown_result, unknown_payload = payload(responses["unknown"])
    checks.append(
        (
            "typed_unknown_tool",
            unknown_result["isError"] is True
            and "Unknown MCP tool 'feature_task_runtime_stats'" in unknown_payload["error"],
        )
    )
except Exception as error:
    checks = [("installed_runtime_mcp_json_rpc", False)]
    print(f"    diagnostic: {error}", file=sys.stderr)

all_ok = all(ok for _, ok in checks)
for name, ok in checks:
    print(f"    [{'PASS' if ok else 'FAIL'}] {name}")
print(f"  INSTALLED MCP RESULT: {'PASS' if all_ok else 'FAIL'}")
sys.exit(0 if all_ok else 1)
PY
mcp_smoke_rc=$?
rm -rf "$MCP_SMOKE_ROOT"
if [[ $mcp_smoke_rc -eq 0 ]]; then
  RESULTS+=("installed runtime-mcp JSON-RPC: PASS")
else
  RESULTS+=("installed runtime-mcp JSON-RPC: FAIL")
  overall=1
fi
echo

for agent in "${AGENTS[@]}"; do
  W="$(mktemp -d)"; FAKE="$W/home"; mkdir -p "$FAKE"
  # seed the agent's root so canonical (non-fallback) paths resolve, as if it were installed
  case "$agent" in
    claude)   mkdir -p "$FAKE/.claude" ;;
    codex)    mkdir -p "$FAKE/.codex" ;;
    junie)    mkdir -p "$FAKE/.junie" ;;
    cursor)   mkdir -p "$FAKE/.cursor" ;;
  esac

  echo "── $agent ──────────────────────────────────────────────"
  rc=0
  # Throwaway-home apply never touches the active goal workflow store; clear the
  # goal-continuation guard so this smoke can run inside a parent goal validate.
  env -u SKILL_BILL_GOAL_CONTINUATION -u CODEX_HOME -u CLAUDE_CONFIG_DIR \
    "$BIN" --home "$FAKE" install apply \
    --repo-root "$REPO_ROOT" \
    --agent-mode manual --agent "$agent" \
    --platform-mode all \
    --telemetry off \
    --mcp register \
    --runtime-install-root "$RUNTIME_ROOT" \
    --runtime-mcp-bin "$MCP_BIN" \
    --format json >"$W/out.json" 2>"$W/err.txt"
  rc=$?

  python3 - "$W/out.json" "$agent" "$rc" <<'PY'
import json, sys, os, glob
out_path, agent, rc = sys.argv[1], sys.argv[2], int(sys.argv[3])
checks = []
def chk(name, ok, detail=""): checks.append((name, bool(ok), detail))

chk("apply_exit_0", rc == 0, f"rc={rc}")
data = None
try:
    data = json.load(open(out_path))
    chk("json_parse", True)
except Exception as e:
    chk("json_parse", False, str(e))

if data is not None:
    chk("no_top_level_failures", not data.get("failures"), str(data.get("failures")))

    mine = [a for a in data.get("agents", []) if a.get("agent") == agent]
    chk("agent_applied", bool(mine))
    skills_path = mine[0].get("path") if mine else None
    if skills_path:
        skill_names = [os.path.basename(p) for p in glob.glob(os.path.join(skills_path, "*"))]
        n = len(skill_names)
        chk("skills_installed", n > 0, f"{n} skills in {skills_path}")
        prose_skills = [
            s for s in skill_names
            if s.endswith("-prose") or s.endswith("-prose.md") or
            s.endswith("-subtask-runner") or s.endswith("-subtask-runner.md")
        ]
        chk("no_prose_skills", not prose_skills, ",".join(prose_skills))

    nas = [x for x in data.get("native_agents", []) if x.get("agent") == agent]
    if nas:
        bad = [x for x in nas if x.get("status") != "linked" or x.get("issue")]
        chk("native_agents_linked", not bad, f"{len(nas)} total, {len(bad)} not-linked")
        ext = ".toml" if agent == "codex" else ".md"
        missing = [x["path"] for x in nas if not os.path.exists(x["path"])]
        wrong = [x["path"] for x in nas if not x["path"].endswith(ext)]
        chk("native_files_on_disk", not missing, f"{len(missing)} missing")
        chk(f"native_files_are_{ext.strip('.')}", not wrong, f"{len(wrong)} wrong ext")
        sample = nas[0]["path"]
        if os.path.exists(sample):
            txt = open(sample).read()
            if ext == ".toml":
                try:
                    import tomllib; tomllib.loads(txt); chk("native_sample_parses", True)
                except Exception as e:
                    chk("native_sample_parses", False, str(e))
            else:
                chk("native_sample_has_frontmatter", txt.lstrip().startswith("---"))

    mcp = data.get("mcp_registration", {})
    outcomes = [o for o in mcp.get("outcomes", []) if o.get("agent") == agent]
    failed = [o for o in outcomes if o.get("status") == "failed"]
    chk("mcp_no_failure", not failed,
        "; ".join((o.get("issue") or {}).get("message", o.get("message", "")) for o in failed))
    for o in outcomes:
        cp = o.get("config_path")
        if cp:
            chk("mcp_config_on_disk", os.path.exists(cp), cp)
            if os.path.exists(cp) and cp.endswith(".json"):
                try:
                    json.load(open(cp)); chk("mcp_config_valid_json", True)
                except Exception as e:
                    chk("mcp_config_valid_json", False, str(e))

allok = all(ok for _, ok, _ in checks)
for name, ok, detail in checks:
    tag = "PASS" if ok else "FAIL"
    suffix = f" — {detail}" if (detail and not ok) else ""
    print(f"    [{tag}] {name}{suffix}")
print(f"  RESULT: {'PASS' if allok else 'FAIL'}")
sys.exit(0 if allok else 1)
PY
  agent_rc=$?
  if [[ $agent_rc -eq 0 ]]; then RESULTS+=("$agent: PASS"); else RESULTS+=("$agent: FAIL"); overall=1; fi
  [[ $rc -ne 0 ]] && { echo "  --- apply stderr (tail) ---"; tail -5 "$W/err.txt" | sed 's/^/    /'; }
  rm -rf "$W"
  echo
done

echo "════ summary ════"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo
[[ $overall -eq 0 ]] && echo "All agents passed the install smoke test." || echo "Some agents FAILED — see per-agent checks above."
exit $overall
