# Capability Deep-dive

Under one `curl` command is a full system. Each capability below is doing real work behind the one `/skill-bill` command.

<details>
<summary><b>1. One-shot multi-agent install via symlinks</b></summary>

`install.sh` symlinks every skill into each detected agent's directory (Claude Code, Codex, Cursor, Junie). A single source-of-truth `skills/` tree powers all of them, so an edit in one place reaches every agent immediately. The same mechanism handles uninstall and the runtime launcher binaries.

</details>

<details>
<summary><b>2. <code>/skill-bill</code> — the end-to-end feature factory</b></summary>

One slash command takes a spec or design doc through the runtime-owned
pipeline to a merged-ready PR, scaling ceremony to the size of the work. The
pipeline: assessment → branch → pre-planning digest → planning (or
decomposition) → implementation → code review → completeness audit → quality
check → history/decisions → commit/push → PR description.

Cross-cutting properties:

- Every heavy phase runs in its own subagent with a self-contained briefing — orchestrator stays small, specialists go deep.
- Durable workflow state at every phase boundary — crash anywhere and resume cleanly, even from a different agent: a runtime-mode run paused under Claude Code continues under Codex with the same `/skill-bill <KEY>`.
- Phase-to-artifact mapping is explicit (`assessment`, `preplan_digest`, `plan`, `implementation_summary`, `review_result`, `audit_report`, `validation_result`, `history_result`, `commit_push_result`, `pr_result`) — every step produces a named, persistable output.
- Telemetry is mandatory and transport-resilient.
- Stack-aware via platform packs.
- Project-tunable via `.agents/skill-overrides.md`.
- Decomposes itself when too big.

It is a tiny CI/CD for the feature itself, not just the code.

**The completeness audit is repair planning plus verification, not a pass/fail check.**

The audit step does not just tell you an acceptance criterion is unmet. When it reports gaps it has to hand back a structured repair plan, and the runtime then holds the rest of the run to that plan.

- **Every gap comes with a plan.** A gaps-found audit must produce one gap per unmet acceptance criterion, and each gap carries a stable id, the criterion it belongs to, concrete failure evidence, a diagnosis, the affected boundary, and one or more dependency-ordered repair items. Each repair item states its intended outcome, the implementation actions, the paths or symbols it touches, and the verification that proves it. A prose gap list with no complete plan fails loudly: it cannot trigger remediation and cannot advance the run.
- **Remediation is one pass, all of it.** The repair pass gets the original pre-planning digest and implementation plan plus the whole accepted repair plan, and it has to come back with one terminal result per carried repair item — either `fixed` or `already_satisfied` — each with changed paths or symbols and the verification actually executed. A missing item, an unattempted item, or wording that hands carried work to a later review, audit, or validation phase is rejected: the pass cannot report completed and cannot advance to review. Later phases may discover new defects, but they are never where the accepted plan gets finished. An item that genuinely cannot be fixed blocks with its gap and repair-item ids and leaves the run resumable.
- **Recurring vs new gaps.** A gap id is derived from its acceptance criterion plus a generation number, so it survives across iterations. The audit that follows a repair pass has to account for every gap it inherited: resolved, or recurring. A recurring gap keeps the same id and carries evidence for why the attempted repair did not satisfy it; only a genuinely different problem gets a new identity. The unresolved-gap ledger is cumulative — an audit cannot drop, rename, or quietly close an inherited gap, and it cannot report everything satisfied while an inherited gap is still recurring.
- **Non-progress blocks loudly instead of looping.** There is no arbitrary cap on audit/repair iterations, because capping a correctness gate just ships an unmet criterion. Instead the runtime watches for a repeat that went nowhere: the same unresolved gap identities as the previous iteration, and either no change in the repository or no repair item newly resolved. That combination stops the run — the audit phase is left blocked, needing a human, with a reason saying the gap identities are unchanged and nothing moved. When you see it, the loop is not one more attempt away from converging: read the blocked gap and its repair items, fix or re-scope the criterion by hand, or correct the plan's diagnosis, then resume. Repair that is genuinely making progress — repository changing, items closing, gap set shrinking — keeps running untouched.

Progress is visible without digging into the database:

```bash
skill-bill feature-task status <workflow_id>
```

prints the run's phase records and its blocked reason. Audit-loop accounting rides the finished telemetry event instead, at one grain: `audit_gap_iteration_count` (audit-gap rounds per run) and `audit_first_pass_convergence`, each paired with `audit_gap_availability` so an unmeasured run never reads as a converged one. Per-gap and per-repair-item counters are not produced; see `docs/review-telemetry.md`.

</details>

<details>
<summary><b>3. Native platform overrides via platform packs</b></summary>

The review phase (`/skill-bill phase:review`) routes to specialist guidance in `platform-packs/<lang>/` (today: `go`, `ios`, `kotlin`, `kmp`, `php`, `python`, `rust`, `typescript`). Go, iOS, Kotlin, PHP, Python, Rust, and TypeScript directly declare all ten approved specialist areas. KMP covers Android and Kotlin Multiplatform: it declares seven areas of its own — `architecture`, `platform-correctness`, `security`, `persistence`, `reliability`, `ui`, and `ux-accessibility` — and composes the remaining three (`performance`, `testing`, `api-contracts`) from its required Kotlin baseline. Each dominant pack declares quality commands in its `validation_gate`; KMP uses its own gate without a Kotlin fallback. Standalone validation (`/skill-bill phase:validation`) uses the same agent strategy as goal validate to discover, run, and repair the full project checks. At runtime the generic entry point reads `routing_signals` from every discovered `platform.yaml` and hands off to the matching review pack. Adding a new language is purely additive—drop in a conforming `platform-packs/<lang>/`; no generic-shell platform enumeration is needed.

The shipped `generic` pack is the manifest-declared code-review fallback for
unsupported, documentation-only, and unresolved paths. Its slug is a distribution
default, not a framework constant. Positive concrete or composed path ownership wins
without appending fallback workers; content signals only break ties between equal
positive path matches and never create ownership.

A team may replace the shipped fallback by moving `fallback_capabilities:
[code-review]` to one conforming custom pack. Removing every fallback declaration
preserves the horizontal base review installed with `skill-bill`, while multiple
declarations fail validation. Delegated preflight and launch use the installed
provider-native worker inventory and recorded digests, so neither the reviewed
repository nor a surviving Skill Bill source checkout needs `skills/` or
`platform-packs/` directories.

`/skill-bill phase:review` accepts an optional `target:` (`HEAD`, `uncommitted`, `pr`, `staged`, `unstaged`, or a commit sha) and `mode:auto|inline|delegated`: a commit target reviews that commit against its first parent; omission and `mode:auto` both resolve to `inline` for every pass and for a scope with no pass number; `mode:inline` runs the light judgment-depth tier in one review subagent covering the routed areas at reduced depth with full broker evidence delivery, with no specialist fan-out and not equivalent coverage to delegated; and `mode:delegated` is the experimental full-depth tier reached only by explicit selection on this phase, launching one specialist subagent per routed area. Feature and goal workflows review inline (`code-review:auto|inline`); they do not launch delegated review.

The shipped `rust` pack follows that same manifest-driven path: Cargo and first-party `.rs` signals route to `bill-rust-code-review`, with governed native agents for the baseline and all ten specialist lanes. Its `validation_gate` covers workspaces, features, targets, rustfmt, Clippy, nextest, cargo-deny, and cargo-audit; Rust-specific routing is not hard-coded into either generic shell.

The shipped `typescript` pack uses the same manifest-driven path: tsconfig and first-party TypeScript signals route to `bill-typescript-code-review`, with governed native agents for the baseline and all ten specialist lanes. Its `validation_gate` covers package-manager scripts, `tsc`, ESLint, Biome, Prettier, Vitest/Jest, workspaces, turbo, and nx; TypeScript-specific routing is not hard-coded into either generic shell.

</details>

<details>
<summary><b>4. Manifest-driven task decomposition, auto-resume by issue key</b></summary>

When planning detects work is too big (rules of thumb: more than 15 atomic tasks, more than 6 boundaries, multiple independently resumable milestones, or sequencing with verify-able foundations), the feature factory switches into `mode: "decompose"` instead of implementing.

- **Subtask specs are real artifacts**: planning writes `.feature-specs/{ISSUE_KEY}-{feature-name}/spec_subtask_1_foundation.md`, `_2_runtime-wiring.md`, etc. — each with its own acceptance criteria, non-goals, dependency notes, validation strategy, and the exact `/skill-bill` prompt to run for it later.
- **Schema-validated prepared state**: every prepared feature has a `decomposition-manifest.yaml` with one or more executable subtasks. A bare `spec.md` is intake, so the manifest is the sole prepared-feature authority marker.
- **You only need the issue key**: when you come back and say "continue SKILL-51", the runtime resolves the parent manifest, finds the in-progress subtask at its last durable workflow step, and picks up there. If none is in-progress, it starts the first pending subtask whose dependencies are complete. You never have to remember "was I on subtask 2 step 4 or subtask 3 step 1."
- **Fresh context per subtask, no context rot**: every subtask starts in a fresh session briefed from curated durable artifacts (the subtask spec, boundary `history.md`, recorded decisions) instead of inheriting a long-lived transcript. Long goals do not degrade as hours accumulate, because no context lives long enough to rot — continuity travels through durable state, not through an ever-growing conversation.
- **Fresh-conversation handoff stays tiny**: the canonical repository realpath and issue key are sufficient to inspect or resume durable goal state. Do not copy transcripts or transfer planning, implementation, audit, review, diagnostic, or raw child payloads between conversations.
- **Blocked-aware**: if the current path is blocked it stops and tells you why, instead of silently skipping to a later dependent subtask.
- **Branch strategy is declared, not improvised**: defaults to `same_branch_commit_per_subtask` (one commit per subtask on the parent feature branch); `stacked_branches` is an explicit opt-in where the runtime refuses to advance if the current branch/base does not match the manifest. The runtime creates or amends the active subtask commit before review, binds approval to its target and tree SHA, and invalidates approval after code changes. Finalization permits only the narrow boundary-history output exemption, preserves unrelated edits, then pushes the reviewed final SHA before opening or reusing the PR.
- **Decomposition is a successful outcome, not a failure**: the workflow closes as `abandoned_at_planning` with `plan_deviation_notes: decomposed into N subtasks` — logged as scope governance, not as a crash.

</details>

<details>
<summary><b>5. Stateful, resumable workflows with native subagents</b></summary>

`/skill-bill` is not a monolithic prompt; it is the entry point for durable feature execution and a fleet of purpose-built subagents.

- **Durable state**: the Kotlin feature-task runtime owns the single feature engine. `skill-bill feature-task` / `skill-bill goal` mint and advance durable workflow rows; every phase boundary persists through the runtime, and resume continues from that state. If a session dies mid-run, continuation re-opens the exact phase from durable records — full durable state is available on demand through read-only workflow status surfaces. The run survives crashes, compaction, even a host reboot. The same durable shape exists for `operation:verify` (`feature_verify_workflow_*`).
- **Native subagents per review layer**: every shipped platform-pack bundle registers its baseline reviewer and each specialist reviewer as native subagents.
- **Why this matters for tokens**: each review subagent gets a self-contained briefing scoped to its area instead of inheriting the full orchestrator transcript. The orchestrator stays small; specialists go deep on their narrow slice. Better focus and lower cost — the opposite of the usual "more steps = more context bloat" trap.
- **Transport-resilient telemetry**: a packaged Kotlin `runtime-mcp` stdio fallback ensures a dropped MCP transport does not leave a workflow stuck in `running`.

For every prepared goal, including a one-subtask goal, the foreground `skill-bill goal` runtime owns a flat worker model: it selects one runnable subtask, opens or resumes that child workflow, launches one fresh child process, and advances only from durable workflow state. Nested/native subagents inside the child session are useful for focus and debugging, but the reliability contract is the runtime-owned workflow row plus the decomposition projection. Because continuity lives in runtime-owned state rather than in any agent's context, goal execution and resume are agent-independent — the agent that continues a goal does not have to be the agent that started it. Resume also treats the parent spec's top-level `status` frontmatter as mutable projection metadata: changing or removing only that field reuses saved planning, while any substantive spec or immutable decomposition change still blocks recovery.

</details>

<details>
<summary><b>6. <code>content.md</code> is the default authored surface; runtime files are generated</b></summary>

A skill author usually touches exactly one file. Free-form markdown, frontmatter on top, prose body underneath, write it however you want. Documented governed sidecar contracts are the narrow exception. No JSON, no schema, no boilerplate.

Generated from it (and you never hand-edit):

- Per-agent skill files in each agent's native format (Claude, Codex, Cursor, Junie), installed as symlinks back to the one source `content.md` so any edit lands everywhere instantly.
- Native subagent files in each agent's required format, registered by name and briefed by the orchestrator at runtime.
- Pointer files inside platform packs — single-line markdown files regenerated from `platform.yaml` by the renderer (you are literally not supposed to commit them).
- Slash-command registration in each agent.
- Skill discovery descriptions derived from the frontmatter `description`.
- MCP tool exposure for workflow and telemetry, without the author wiring anything.

The author contract is: write the body, declare the description, use documented governed sidecars only where the contract allows them, and leave the rest to the renderer. The validator keeps the generated artifacts from drifting from the manifest. Soft inside, hard shell.

</details>

<details>
<summary><b>7. Per-project skill fine-tuning via <code>.agents/skill-overrides.md</code></b></summary>

Every skill reads the project's override file as part of its shared ceremony, so you can change skill behavior for a specific repo without forking or editing the skill source. The file lives in the repo, is versioned with the code, and applies to whichever agent is running.

- **Orchestrator-owned read**: the override file is read by the orchestrator, not delegated to a subagent. (When delegated, action mandates used to get paraphrased into free-form notes and silently dropped — see `agent/decisions.md` for the incident that hardened this.)
- **Action mandates at named lifecycle positions**: overrides can declare mandates that fire at specific orchestrator lifecycle points (e.g. before applying the skill body, at end-of-run for state writes). Skills cannot quietly skip them.
- **Composable with `AGENTS.md`**: the shared ceremony loads both general project conventions and per-skill targeted tweaks.

Net effect: you fine-tune review with an extra checklist item, or force the `/skill-bill` full run to call a project-specific telemetry tool, by editing one markdown file in the repo. No skill fork, no agent reinstall.

</details>

<details>
<summary><b>8. Per-module memory</b></summary>

Every module/package has its own `agent/decisions.md` and `agent/history.md`. The `write_history` phase's `boundary-history` strategy knows how to write high-signal entries with hygiene rules that keep history from rotting. Result: cross-session institutional knowledge attached to the code itself, not to your head or a wiki. You can see it in this very repo — `agent/decisions.md` records the exact incident that hardened the override read in #8. That is how the system stays self-aware across sessions and contributors.

</details>

<details>
<summary><b>9. First-class, transport-resilient structured telemetry</b></summary>

Every skill that matters emits typed telemetry, not just log lines.

Feature-task runs also use validated, immutable execution identity for
database-first continuation. Identity binds the normalized issue key to the
canonical repository, persisted mode, governed spec path, and route scope, so
equal issue keys in separate clones cannot collide. Lookup distinguishes no
match, resumable, already running, ambiguous, and terminal-only results without
mutating state or treating `spec.md` as a duplicate planning ledger.

Runtime process ownership is a separate fenced lease, never part of the immutable identity or governed spec. Exact host, boot, PID, and process-birth evidence protects live ownership from PID reuse and cross-host mistakes. A previous boot on the same host is definitively not running, and an expired unverifiable lease may be atomically reclaimed; owner tokens and generations prevent concurrent reclaimers or displaced workers from writing progress.

- **Per-skill start/finish pairs** with stable session ids: `feature_verify_started/_finished`, `quality_check_started/_finished`, `review_stats`, `pr_description_generated`, `import_review`, `triage_findings`, `resolve_learnings`, plus aggregate views (`feature_verify_stats`, `goal_stats`, `telemetry_remote_stats`, `telemetry_proxy_capabilities`).
- **Orchestrator/child relationship is modeled**: orchestrated subagents call their own `*_finished` with `orchestrated=true` and return a `telemetry_payload`; the orchestrator assembles a parent/child tree. You can see the whole run as a tree, not a flat stream.
- **Separated from workflow state, on purpose**: workflow state persists even when telemetry returns `status: skipped`. Telemetry is observability; workflow state is correctness. They never get confused.
- **Health-checked and transport-resilient**: before terminal writes the orchestrator pings the MCP transport; if closed, it switches to the packaged `runtime-mcp` stdio fallback for the remaining telemetry and workflow calls. Runs do not get left in a half-reported state because a transport died.
- **Aggregate stats tools** give you queryable rollups, so you can actually see how your feature-task pipeline is behaving instead of grepping logs.
- **Pluggable proxy target**: events flow through a telemetry proxy, with a hosted relay as the default. Point it at your own service by setting `proxy_url` in the telemetry config (or `TELEMETRY_PROXY_URL` in the environment) and `skill-bill telemetry sync` / `capabilities` / `stats` will operate against it. Self-host, anonymize, or fork the proxy itself — Skill Bill's telemetry pipeline doesn't lock you to anyone's backend.

</details>

<details>
<summary><b>10. Strict, declarative skill-set contract with drift protection</b></summary>

Every platform pack is anchored by a `platform.yaml` that declares: contract version, routing signals, the full set of declared code-review areas and their content-file paths, optional `validation_gate` for quality-check argv when the pack can dominate routing, and pointer files (auto-generated so no one hand-edits them). Backed by `scripts/validate_agent_configs`, which fails the build if the on-disk layout does not match the manifest (missing files, stray skills, broken pointers, agent-install inconsistencies). You cannot accidentally rename a skill, half-delete an area, or let one agent's copy diverge from another. Render/install regenerates pointers from the manifest, and validation refuses to let drift land.

The normal repository validator also runs the exemption-free platform substance gate. Every effective specialist must retain three platform-specific failure-mode clusters, ten evidence-bearing rules, and zero forbidden placeholders; cross-pack shared five-word sequences must stay at or below 35%, and corresponding rubrics at or below 65% similarity.

This is the governance layer that keeps the other ten features from rotting — once you have seen what they enable, you also see why this one exists.

</details>
