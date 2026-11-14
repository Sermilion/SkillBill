## [2026-08-11] Mode-default reconciliation and lane-failure split (review repairs)
Areas: orchestration/{review-delegation,review-orchestrator,telemetry-contract}, docs, README
- One resolution everywhere: an omitted mode and `auto` both resolve to `inline` for every pass and for a scope with no pass number, and only an explicit `delegated` reaches the fan-out. `review-orchestrator/PLAYBOOK.md`, `telemetry-contract/PLAYBOOK.md`, `docs/delegated-review/reliability-contract.md`, `README.md`, and both getting-started docs still claimed delegated-by-default or pass-number `auto`, which is the resolution installed skills would have followed.
- `inline` is defined as one worker — the declared `bill-code-review-inline` native agent — not "one agent in the current context". The old wording described the parent reviewing in its own context, which the governed content calls an inline review that must be reported as such.
- Cursor lane accounting splits the two dispositions that were collapsed: a lane that launches but returns no attributable structured report is a failed lane; a lane with no installed agent, or a session that cannot launch by name, stops the whole run.
Feature flag: N/A

## [2026-08-11] Cursor agent CLI vs IDE warning for delegated launch failure
Areas: orchestration/review-delegation
- Cursor unavailable path now names the entry-point split: installed specialists launch from the Cursor IDE agent UI; the Cursor `agent` CLI Task surface (built-ins only) cannot spawn those named lanes.
- Failure copy tells the operator to re-run `mode:delegated` in the IDE agent chat or use `mode:inline` on the CLI, and still forbids silent inline downgrade or `generalPurpose` substitution.
- Pattern: harness-specific refusal text that points at the working entry point rather than a generic "unavailable here". reusable
Feature flag: N/A

## [2026-08-11] SKILL-182 subtask 1 Cursor harness section in review-delegation
Areas: orchestration/review-delegation
- Added a `## Cursor` section to `PLAYBOOK.md` so Cursor no longer falls through the undocumented-runtime stop for delegated-required scopes.
- Cursor launches named installed subagents (project scope wins on name conflict), one lane per routed review skill or specialist pass, all selected lanes in one parallel instruction, with no model override and the embedded native-agent rubric authoritative.
- Reusable: when a harness returns no launch id, lane identity is the launch-plan pair (routed area + assignment digest); shared worker-tracking admits that identity without weakening the no-global-listing rule.
- Negative paths: no attributable structured report fails the lane; parent answering a lane inline must report inline coverage; unavailable subagents stop as delegated-unavailable with no silent inline downgrade.
- Shared id-tracking rule reworded to harness-available identity; Copilot, Claude, Codex, and the retired fourth agent sections left byte-identical.
Feature flag: N/A
Acceptance criteria: 9/9 implemented
