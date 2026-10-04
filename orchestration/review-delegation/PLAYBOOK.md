 ---
name: review-delegation
description: Single source of truth for agent-specific delegated code-review execution. Installed skills link to this via generated support pointers.
---

# Shared Review Delegation Contract

## Mode vocabulary

`delegated` is the experimental full-depth review mode, reached only by an
explicit `mode:delegated` on `skill-bill phase review` (or `skill-bill code-review
--execution-mode delegated`). Goal and feature-task runs always review inline
and never select it. The invoking agent fans the review out to
specialist subagents inside its own harness and merges their findings. `inline`
is the single-prompt review: the runtime runs one inline review session
with no per-area fan-out. Large
evidence is broker-paged inside that session. `auto` and an omitted mode both resolve to
`inline` for every pass and for a scope with no pass number, so only an explicit
`delegated` selection reaches the fan-out.

Delegation never leaves the invoking agent's harness: every specialist lane is a
subagent of the reviewing agent, and there is no separate lane lifecycle store.
A lane is complete when it returns its structured findings; the parent aggregates
only after every selected lane has returned.

## Lane accounting

The parent tracks each launched lane by the identity the harness makes available —
the returned launch id where the harness provides one, otherwise the routed area
and assignment digest from the launch plan — and records, per lane, the routed
area, the assignment digest it was launched with, and its terminal outcome. A lane
that cannot be launched, or that returns no findings report, is a failed lane:
report it explicitly rather than treating the merged output as complete.

If the current harness cannot launch subagents at all, stop and report that
delegated review is required for this scope but unavailable here. Do not
silently downgrade a delegated selection to inline.

This is the canonical review-delegation contract. Installed skills consume it through generated sibling support pointers (e.g. `review-delegation.md` inside each staged skill directory), so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

The delegated worker rules themselves have one authoritative authored source:
`orchestration/review-orchestrator/specialist-contract.md`. Runtime and
harness-native launches project its launch contract, forbidden-rediscovery list,
evidence-surface rules, and report structure into the subagent assignment.
Workers do not reload those rules from disk, and this playbook does not restate
them. Maintainer parity tests pin the runtime constants to the authoritative
marked blocks and list in that source.

A delegated selection fans out to subagent lanes, and it is only ever explicit:
neither an omitted selection nor `auto` resolves to delegated. Harness-specific launch mechanics live in the
per-harness sections below; a mitigation for one harness must not change another
harness's launch behavior.

## Commit-focused sparse routing

- The parent owns discovery and relevance. It resolves the commit sequence, decides every commit/lane pair as focused or skipped before launch, and records a falsifiable reason for each skip. Workers never re-decide relevance.
- Commits irrelevant to every lane are excluded before launch with their reasons recorded; they are not handed to a worker to filter out.
- Each selected specialist receives one assembled bundle of its assigned hunks with commit identity and order as metadata, and reviews it in a single pass. Never instruct a worker to step through commits one at a time, to review every commit by default, or to restart from an aggregate diff.
- Specialist worker count equals selected lane count and never scales with commit count.
- A lane that could not fit its assigned bundle within budget ends incomplete. Report it as non-clean coverage naming the unreviewed units and the stopping budget dimension.
- After every selected lane reaches a terminal state, run exactly one bounded integration pass over the commit sequence. It receives final-state evidence and per-lane summaries only — no raw lane bundle, no sibling-lane hunk bodies, no parent transcript — and reports cross-commit behavior with commit-range evidence without re-running any specialist rubric.
- Specialist completion and integration completion are distinct durable boundaries. Retry is lane-granular: a resume re-runs only lanes without a durable result, and re-runs the integration pass only when it did not itself complete.
- The integration pass never compensates for an incomplete lane and must never be reported as closing that coverage gap.
- A scope with no commit sequence reports the integration pass as not applicable with its reason, using the existing `detected_scope` vocabulary.

## Shared Delegation Rules

- Standalone phase review is report-only. Return recommendations without applying fixes, staging, committing, amending, resetting, or launching another review command. Project these restrictions into every assigned specialist lane, including provider-native lanes, using the authoritative specialist contract.
- Every delegated specialist starts in a fresh conversation. Native Codex launches MUST set `fork_turns: "none"`. Other harnesses retain their existing launch behavior, and no harness may hand a specialist the parent conversation.
- Project exactly one compact specialist contract, one applicable rubric, immutable review identifiers and revisions, assigned paths and hunks, relevant criteria references, matched rules, named evidence targets, broker identifiers, and a budget summary into each launch. The parent transcript, full phase briefing, unrelated criteria or rubrics, and unrelated diff are forbidden.
- Specialists use the bounded evidence surface and do not execute status, scope, stack, routing, or broad-diff discovery. Out-of-assignment access requires a nonblank reachability reason and consumes a bounded expansion.
- Payload, evidence, result, and expansion excess terminates the affected lane as `review_context_budget_exceeded`. Never truncate required evidence, skip a required lane, widen repository access, replace a reviewer, or substitute execution mode.
- Use this delegation contract only after the shared execution-mode contract selects `delegated` review.
- Before launching any routed layer or specialist, the parent prepares one compact, in-memory review-context packet. The packet is authoritative for the whole review run and contains the resolved scope and diff source, routing decision, applicable project guidance, relevant build/test facts, changed-file and hunk map, selected add-ons, ordered selected lanes with inclusion or exclusion reasons, immutable session/run identifiers, and one assignment per worker.
- Each worker assignment names its applicable embedded rubric, owns specific changed files and hunks, identifies only the direct dependencies that may be read, and states the evidence to verify. The validated assignment is the launch authority; do not give a worker the shared packet.
- Workers discover exact evidence selectors through `read_evidence` with `operation: discover`. Follow `next_cursor` to the end, then read the selectors with `operation: read`. Whole-file reads require a discovered or newly issued `expansion_id`. Discovery and authorization do not count as delivered evidence. A recoverable refusal can be corrected; any remaining required evidence blocks approval.
- Workers must not repeat repository, scope, stack, routing, or guidance discovery. They may read their assigned changed code and direct dependencies only when needed to establish a reachable finding.
- Keep the packet factual and compact. Do not copy repository dumps, full project documentation, unrelated diffs, or unrelated specialist rubrics into it.
- Build one deterministic launch plan before starting workers. Recursively flatten required baseline layers into direct specialist lanes, apply the nearest pack's area override, retain signal-relevant lanes and add-ons, and drop empty or duplicate assignments.
- Launch only the specialists in that flattened plan. Never launch a routed baseline orchestrator as a nested worker.
- Launch one delegated worker per routed stack-specific review skill or selected specialist review pass unless the current agent-specific section explicitly says otherwise.
- The parent review owns every worker in the flattened launch plan and preserves each lane's composition-chain attribution through merge and deduplication.
- The parent review that owns the final merged review output also owns `import_review` and `triage_findings`. Delegated workers must not call those telemetry tools themselves.
- When the harness supports delegated-specialist model inheritance, delegated specialists should use the same model as the parent thread by default. Do not override the delegated-specialist model unless the current harness-specific section explicitly requires it.
- Every delegated worker receives only the broker projection from its validated assignment. Scope, raw diff, guidance bodies, learnings, add-ons, runtime ceremony, and telemetry ownership stay in the authoritative parent packet and are not projected.
- Wait for all delegated workers to finish, then merge and deduplicate findings by root cause, severity, and confidence.
- Track delegated workers by the identity the harness makes available — the returned launch id where the harness provides one, otherwise the routed area and assignment digest from the launch plan. Do not discover or poll delegated workers through broad global listing in the normal review path.
- If delegated review is required for the current scope and a supported runtime refuses or cannot start delegated workers, stop and report that delegated review is required for this scope but unavailable on the current runtime.
- If the current runtime is not documented below, stop and say delegated review is unsupported for delegated-required scopes.

Governed add-ons may narrow or enrich delegated review instructions only after the parent review has already resolved the dominant stack and selected the applicable add-ons.

## Claude Code

- Use the `Task` tool / subagent mechanism.
- Launch one subagent per delegated review skill or specialist review pass.
- The installed native agent's embedded governed rubric is authoritative. Do not tell the worker to read a sibling rubric sidecar.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry and metadata are not part of the worker projection.
- Run eligible delegated passes in parallel and merge the results in the parent review. On Claude Code, "in parallel" means every lane's `Task` call goes out in a **single assistant message**. Issuing one `Task` call, waiting for its result, then issuing the next satisfies the words but runs the fan-out serially and multiplies review wall-clock by the lane count. Launch them as one batch, then merge once all have returned.
- Report per-lane timing in the parent review output so serial execution is detectable after the fact: one `Lane timing:` line per lane carrying the lane id, its launch timestamp, its return timestamp, and its model-turn count. Overlapping launch timestamps are the evidence that the batch was concurrent. The turn count is the token-cost signal: the per-turn context floor is re-sent every turn, so a lane that converges in three turns costs a fraction of one that wanders toward the 24-turn ceiling on the same assignment.
- Do not inline delegated review logic on Claude when Task/subagents are available.

## OpenAI Codex

- Explicitly request subagents.
- Spawn one subagent per delegated review skill or specialist review pass.
- Use the same model as the parent thread by default.
- The installed native agent's embedded governed rubric is authoritative. Do not tell the worker to read a sibling rubric sidecar.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry and metadata are not part of the worker projection.
- Wait for all subagents and merge their results in the parent review.
- Do not run delegated review passes inline.

## Cursor

- Launch each lane by naming its installed Cursor subagent (user-scope or project-scope agents install; project scope wins on a name conflict), via `/name` or an explicit "use the `<name>` subagent" instruction. Do not compose a rubric inline.
- Launch one subagent per routed stack-specific review skill or selected specialist review pass.
- Request all selected lanes in a single instruction that names every selected lane so they launch in parallel, not one-at-a-time.
- When `skill-bill` runtime-launches a delegated Cursor parent, the runner appends one `/name` line per selected specialist in a single parallel-launch instruction and copies each installed specialist agent file into the review launch workspace's Cursor agents subdirectory before `agent --print` starts. `reviewLaunchDirectory` is the isolated evidence-endpoint directory already used as `--workspace` (the parent of that workspace's MCP config).
- Do not override the delegated-specialist model; Cursor subagent frontmatter defaults to `model: inherit`.
- The installed native agent's embedded governed rubric is authoritative. Do not tell the worker to read a sibling rubric sidecar.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry (`import_review` and `triage_findings`) and metadata are not part of the worker projection.
- Cursor lane identity is the routed area plus the assignment digest from the launch plan. No Cursor rule depends on a harness-returned launch id.
- A lane that launches but returns without a structured findings report attributable to that lane's identity is a failed lane: report it explicitly. Never absorb it into the merged output as covered. A lane that cannot be launched at all is not a lane-level failure — it stops the run under the two conditions below.
- If the parent answers a lane's rubric in its own context, that is an inline review and must be reported as such — not as delegated coverage.
- Distinguish Cursor entry points when launch fails. The Cursor IDE agent UI can spawn installed user-scope or project-scope Cursor agent specialists by name. The Cursor `agent` CLI — and any Cursor session whose `Task` tool only exposes built-in types such as `generalPurpose` — cannot. When those specialist files are installed but this session cannot launch them by name, stop and report that delegated specialist lanes cannot run on the Cursor `agent` CLI harness: re-run `mode:delegated` from the Cursor IDE agent chat, or use `mode:inline` here. Do not silently downgrade to inline, substitute a built-in worker, or claim delegated coverage from the parent context.
- If the Cursor harness cannot launch subagents for another reason (subagents unavailable in the IDE surface, or no installed agent matching a selected lane), stop and report that delegated review is required for this scope but unavailable here. Do not silently downgrade to inline.

## Junie

Junie delegated review is intentionally unsupported.
