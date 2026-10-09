---
name: review-orchestrator
description: Single source of truth for shared stack-specific code-review orchestration contracts, merge rules, and output structure. Installed skills link to this via generated support pointers.
---

# Shared Code Review Orchestrator Contract

## Modes this contract backs

`delegated` is the experimental full-depth review, reached only by explicit selection: the reviewing agent fans the routed areas out
to specialist subagents inside its own harness, and this specialist contract is
what each of those subagents is held to. `inline` is the single-prompt review —
one session that follows the runtime's inline review directive, with
no per-area fan-out — and it is held to
the same finding bar, severity vocabulary, and report structure stated below.
`auto` resolves to `inline` under each of its named rules: a first pass, any scope
with no pass number, and every follow-up or remediation pass. `inline` is also the
default when no mode is selected at all, so only an explicit `delegated` selection
reaches the fan-out. An oversized inline scope stays in that one session; the worker
pages evidence through the governed broker rather than launching sequential chunk workers.

## Lane accounting

Fan-out accounting is bounded and in-harness. The parent records, per launched
lane, the routed area, the assignment it was launched with, and its terminal
outcome; aggregation admits only the selected lane set with valid finding
envelopes and complete declared-area coverage. A missing or duplicate lane result
is a bounded aggregation failure, not an invitation to repair or rediscover scope.
Lane accounting never carries prompts, complete diffs, raw transcripts, or tool
logs.

If the harness cannot launch the required subagent lanes, stop and report that
delegated review is unavailable here. A delegated selection is never silently
substituted with an inline pass.

This is the canonical review-orchestration contract. Installed skills consume it through generated sibling support pointers (e.g. `review-orchestrator.md` inside each staged skill directory), so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

## Shared Contract For Every Specialist

- Review only changed code in the current PR or unit of work
- Surface only meaningful issues such as bugs, logic flaws, security risks, regression risks, or architectural breakage
- Flag newly introduced deprecated APIs or patterns when a supported alternative exists, or when deprecated usage is broad and unjustified
- Flag comments that only restate **what** the code does (paraphrasing adjacent code) as a maintainability finding — this is an explicit contract item, report it at `Minor`. Do not flag comments that explain **why**: a decision or non-obvious constraint the code cannot express is warranted and must be left alone
- Ignore style-only nits, formatting preferences, and naming bikeshedding — a comment that merely restates the code is a maintainability defect under the rule above, not a style nit
- Evidence is mandatory: include `file:line` and a short description
- Include the user-visible or externally observable consequence for each finding
- Report `Minor` findings, or `Medium`/`Low` confidence findings, only when they tie to an explicit contract violation, user-visible bug, regression risk, quality gate failure, or persisted learning
- Always report evidence-backed `Blocker` and `Major` findings. Do not suppress concrete correctness, security, persistence, lifecycle, testing, accessibility, or contract defects because they fall outside the low-value reporting threshold
- Severity is defined by observable consequence. Use `behavior_correctness` as the calibration reference: `Blocker` means the change breaks correctness or safety; `Major` means the change materially worsens behavior for a demonstrated scenario. Stylistic, speculative, or pre-existing observations are `Minor`, `Nit`, or suppressed by the SKILL-115 admission gate. The closed rating enum is `Blocker`, `Major`, `Minor`.
- Absent, thin, or incomplete test coverage is capped at `Minor`, never `Blocker` or `Major`, and is still subject to the reporting threshold above. A test that exists but asserts the wrong behavior, is tautological, or masks a production defect is a defect in the changed code and keeps normal severity calibration.
- Confidence: `High | Medium | Low`
- Keep each specialist review pass to at most 7 findings
- The code-quality lane is the only exception to the meaningful-issue, style-nit and Minor-tie rules; report its findings as Minor only, with at most 5 per review.
- Include a minimal concrete fix for each finding
- In standalone phase review, concrete fixes are recommendations. Do not apply them, stage, commit, amend, or reset files.

## Shared Scope Contract

- Resolve the exact review source before routing, classifying, or selecting specialist review passes
- Supported scope labels are `staged changes`, `unstaged changes`, `working tree`, `commit range`, `PR diff`, and `files`
- When the caller asks for staged changes, inspect only the staged/index diff and treat unstaged working tree edits as out of scope, except for repo markers needed for stack detection
- When the caller asks for unstaged changes, inspect only the unstaged working tree diff and do not fold in staged-only hunks unless the caller explicitly asks for all local changes
- Use `working tree` only when the caller explicitly wants both staged and unstaged local changes reviewed together
- State the resolved scope in Section 1 as `Detected review scope: ...`

## Shared Execution Mode Contract

- `skill-bill phase review` accepts exactly one canonical caller argument: `mode:auto`, `mode:inline`, or `mode:delegated`. Omission is `mode:inline`.
- It also accepts at most one governed caller context, `context:feature-remediation`. This context is valid only with `mode:inline` for a bounded feature-task re-review of the supplied remediation delta. Reject it with any other mode or scope.
- Reject malformed, unknown, repeated, or conflicting `mode:` arguments before scope resolution or review launch. The requested mode is review-run metadata and is forwarded unchanged to parallel lanes and review re-runs.
- `auto` resolves to `inline` everywhere: a first review pass, every follow-up or remediation pass, and a scope with no pass number. Preserve its named deciding rule in metadata; size, risk, and layering never change the resolution, and `auto` never reaches the delegated fan-out.
- `inline` is authoritative as the light judgment-depth tier: one inline review session, no per-area specialist workers, walking every manifest-declared area and required baseline area as an explicit checklist once each at reduced depth. Large evidence is broker-paged to completion inside that session — reduced depth does not authorize sampling or early stop. Diff signals focus an area's inspection but never drop that area. It is not equivalent depth to delegated, and the inline result says so. Do not spawn specialists, invent lane totals, refuse the request, or silently change it to another mode.
- Inline and delegated workers use only `read_evidence` and `request_expansion`. Discover assigned evidence with `read_evidence` `operation: discover` and continue with `next_cursor`. Read the returned exact `path` and `selector` using `operation: read` and `requests`. Whole-file reads require a discovered or newly issued `expansion_id`. Rubrics and required companion guidance arrive in the launch. Discovery and authorization do not count as delivered evidence. A recoverable refusal can be corrected; any remaining required evidence blocks approval. Never use backing-store paths or shell reads.
- `context:feature-remediation` bounds pass two to the supplied remediation delta — all findings addressed in that round union the pre-fix-to-post-fix diff — rather than the full base-to-current delta. The immutable `review_base_sha` and baseline untracked inventory remain the authority for pass one only. The pass emits an evidenced `resolved`, `unresolved`, or `superseded` disposition for every prior Blocker under the durable `blocker_dispositions` key. This context lowers depth and scope only; it does not weaken finding severity, evidence, admission, or approval rules.
- Only an explicit `delegated` selection performs normal specialist selection and launch. Launch the required delegated workers using `review-delegation.md`; if a worker cannot start, stop loudly. Never fall back to inline.
- A delegated launch preflights the complete flattened worker set against the current installed native-agent inventory before either lane starts. Missing, dangling, stale-digest, unreadable, or undeclared workers stop with the governed reinstall command; never substitute a generic or baseline worker.

## Delegated review context architecture

The parent prepares one authoritative packet after repository, scope, stack, guidance, learnings, and add-on discovery. Composition is flattened before launch: required baseline specialists and signal-selected platform specialists become direct lanes, and no lane invokes another review orchestrator. Workers must not rerun status, base-revision discovery, broad diff, AGENTS traversal, stack routing, add-on discovery, learnings resolution, or unselected MCP discovery.

Each lane receives only its assignment, bounded rubric, immutable identifiers, and named evidence targets. Additional evidence goes through the broker in bounded batches. Authorized expansions are appended to the lane ledger without changing packet or assignment digests. Native-agent inventory and content digests are preflighted for the entire flattened set before the first worker launches; the reported repair command is the only recovery for missing, dangling, stale, unreadable, or undeclared workers.

## Commit-focused sparse specialist sequencing

The parent owns discovery and relevance. It resolves the commit sequence once, decides for every commit and lane pair whether that lane is focused on that commit or skips it, and records a falsifiable reason for each skip naming the commit's changed paths and the lane signals that failed to match them. Irrelevant commits are excluded before any worker launches. No worker re-decides relevance downstream, and there is no deferred candidate state: every pair carries one final disposition.

Each selected specialist receives one assembled bundle of exactly its assigned hunks, carrying commit identity and order as readable metadata, and reviews that bundle in a single pass. Commit order lets a specialist relate earlier and later commits inside that one pass; it is not an iteration order. Specialists never step commit-by-commit, never re-run broad diff discovery, and never receive the aggregate or complete PR diff. Worker count equals selected lane count and does not grow with commit count.

A lane that could not fit its whole assigned bundle within budget ends **incomplete**. That is non-clean coverage: the report names the units it left unreviewed and the budget dimension that stopped it, and no later pass converts it into clean coverage.

After every selected lane reaches a terminal state, the parent runs exactly one bounded integration pass over the commit sequence. It receives final-state evidence and per-lane summaries — never a lane's raw bundle, a sibling lane's hunk bodies, or a parent transcript — and reports only cross-commit behavior with commit-range evidence. It re-runs no specialist rubric and its cost does not scale with commit count. Specialist completion and integration completion are distinct durable boundaries: a resume re-runs only lanes without a durable result, and re-runs the integration pass only when the integration pass itself did not complete. The integration pass never compensates for a lane that ended incomplete and must never be reported as closing that gap.

A scope with no commit sequence — staged, unstaged, working tree, an exact supplied diff, or a single commit — has no cross-commit behavior to integrate. Report the integration pass as not applicable with its reason, using the existing `detected_scope` vocabulary, rather than inventing commit history for it.

Accounting preserves direct and inclusive ownership. Direct usage belongs to one process. Inclusive provider usage already contains descendants and is never summed with them again. Parent and lane summaries carry byte counts, expansion/tool/turn counts, terminal outcomes, and input, cached-input, output, reasoning, total, and fresh-token-approximation values. The approximation is useful for regression detection, not billing reconciliation.
- Review skills must choose an execution mode of `inline` or `delegated` before running routed review layers or specialist review passes
- `auto` resolves through exactly one named rule, reported in review metadata alongside the resolved mode. `auto` never resolves silently.
- `auto_mode_by_pass_number` is authoritative wherever a review pass number exists and resolves every pass, first included, to `inline`.
- `auto_mode_default` is the named standalone fallback rule and resolves every scope with no pass number to `inline`.
- Inline mode must walk every area declared by the routed manifest and required baseline composition deliberately, using each area's governed rubric as a checklist in the current context; do not collapse the review into a generic skim or omit an area because its specialist would not have been selected.
- In standalone phase review, concrete fixes are recommendations. Specialists and the parent must not apply fixes, stage, commit, amend, reset, or launch another review command.

## Shared Learnings Context

- The review driver resolves learnings itself, inside the review run. No agent — parent, layer, or specialist — calls a learnings tool.
- The driver derives the repo scope key from the reviewed repository's `origin` remote, normalized to its full repository path (nested groups keep every segment), and the skill scope key from the routed review skill. With no `origin` remote it resolves global and skill learnings only.
- The driver resolves only active learnings, in scope order `skill`, `repo`, `global`; disabled learnings and learnings scoped to another repo or skill never reach a worker.
- The driver persists the resolved set as the `session_learnings` row for the run's review session id, including an empty set, and delivers each learning's title and rule text inside every worker launch envelope.
- The driver prints `Review session ID: <id>` and `Applied learnings: none | <learning references>`. Copy both lines verbatim into the summary and the review import; do not invent either value.
- Pass `--review-session-id` when the parent already holds a review session id; otherwise the driver mints one.
- Learnings are explicit context, never hidden suppression rules. A learning never suppresses an evidence-based correctness, security, or contract finding.

## Shared Delegation Contract

- Runtime-facing review skills must read `review-delegation.md` before delegating routed review layers or specialist review passes
- When execution mode is `delegated`, routed review layers and specialist review passes run as separate subagents on supported runtimes
- If delegated review is required for the current scope and a supported runtime cannot start the required workers, stop and report that delegated review is required for this scope but unavailable on the current runtime
- If a specialist review pass fails or returns no output, note it in the summary and continue with available results when the parent skill contract permits it
- When multiple review passes produce overlapping findings, deduplicate by root cause and keep the highest severity/confidence version
- Prioritize final findings as `Blocker > Major > Minor`, then by blast radius

## Shared Caller Integration Notes

- If a review is invoked from a feature workflow, `operation:verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- A standalone `skill-bill phase review` returns findings without applying fixes or launching another command. A valid register with either verdict exits 0; invalid, incomplete, or failed output exits 1 with available findings.

## Shared Report Structure

Section 1 summary must include `Review session ID: <review-session-id>`.
Section 1 summary must include `Review run ID: <review-run-id>`.
Section 1 summary must include `Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>`.
Section 1 summary must include `Execution mode: inline | delegated`.
Section 1 summary must include `Applied learnings: none | <learning references>`.

The review session id is driver-owned and uses the format `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`). If a parent reviewer already holds a `review_session_id`, forward it with `--review-session-id`; otherwise the driver mints one. Take the value from the driver's `Review session ID:` line and reuse that same id across the summary, the parent-review handoff, and the review import for the current review lifecycle.

Generate one review run id per concrete review output using the format `rvw-YYYYMMDD-HHMMSS-XXXX` where `XXXX` is a random 4-character alphanumeric suffix for uniqueness (e.g. `rvw-20260405-143022-b2e1`). If a parent reviewer already passed a `review_run_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same run id across the summary, the risk register, and any parent-review handoff or follow-up feedback workflow for the current review output.

After Section 1 in a stack-specific review skill, use:

- `### 2. Risk Register`
- `### 3. Action Items (Max 10, prioritized)`
- `### 4. Verdict`

Every finding in `### 2. Risk Register` must use this authoritative machine-readable bullet format:

```report-structure
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
Findings naming commits use: - [F-001] <Severity> | <Confidence> | commits=<sha>[,<sha>] | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings. The bullet format above is required for downstream tooling (triage, telemetry, stats) to parse findings correctly.

- Severity must be one of: `Blocker`, `Major`, `Minor`
- Confidence must be one of: `High`, `Medium`, `Low`
- Finding ids must be unique within the current review run and stable enough for follow-up feedback or fix requests in the same workflow
- Assign finding ids sequentially in risk-register order using `F-001`, `F-002`, `F-003`, and so on
- Put code-quality findings after all failure findings in a `#### Code Quality (non-blocking)` sub-section at the end of `### 2. Risk Register`. Continue the F-id sequence, retain each finding's `specialist=` attribution, and mark each finding Minor. Code-quality findings never change the Verdict. Put any action items for them after failure action items.
- A worker with no findings must return exactly `NO_FINDINGS`; an empty response is an incomplete result.

## Governed Add-Ons

Stack-specific review skills that own governed add-ons may add `Selected add-ons: none | <add-on slugs>` to Section 1 after stack classification is complete.

Governed add-ons supplement a routed stack review after stack classification is complete. They do not create new review entry points or specialist names on their own.

## Telemetry Ownership

See [orchestration/telemetry-contract/PLAYBOOK.md](../telemetry-contract/PLAYBOOK.md) for the full telemetry and triage ownership contract.

## Triage Ownership

See [orchestration/telemetry-contract/PLAYBOOK.md](../telemetry-contract/PLAYBOOK.md) for the full telemetry and triage ownership contract.

## Specialist Contract Subset

Delegated specialist subagents receive `specialist-contract.md` instead of this full file. That file contains only "Shared Contract For Every Specialist" and "Shared Report Structure" — the two sections specialists need. Orchestrator-only sections (Scope, Execution Mode, Learnings, Delegation) are omitted to reduce per-subagent token cost. Maintainer validation extracts those two canonical sections and enforces exact byte parity after line-ending normalization.
