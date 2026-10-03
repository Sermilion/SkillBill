# Runtime command guidance

These instructions apply to runtime commands, planning, execution, and goal commits. Read this document with `AGENTS.md`.

## Product Intent

`/skill-bill` is the only listed skill. Its full-run form (`/skill-bill <intake>`) resolves the supplied requirements and launches `skill-bill <intake>` through the foreground goal runtime with durable state, telemetry, packs, add-ons, and native subagents. New work requires both a connected tracker link or issue key and requirements text after it; the key or path of an existing spec, or a persisted goal's key, resumes without either. The CLI also accepts the explicit `skill-bill goal <intake>` form. New requirements create a spec bundle and parent workflow before durable planning; existing specs resume without replacement. The runtime refuses new work missing the key or the requirements before it prints launch output or writes any state, and it does not assign a local workflow identity. The dispatcher asks the operator for whichever part is missing. For tracker references, the dispatcher first searches local specs including ignored and untracked files, then checks persisted workflows in the current repository through `skill-bill work status --repo-root <repo-root> --format json`. If needed, `skill-bill work list --format json` exposes other persisted work for correlation with local spec and manifest identities. Existing specs and matching goals resume without a tracker lookup. Database inspection failures block lookup rather than count as absent work. Only new tracker references resolve through the connected tracker before implementation; on lookup failure the dispatcher asks for the requirements and never infers them. Its `phase:<name>` forms run `skill-bill phase <name>`, and its `operation:<name>` forms run `skill-bill operation <name>`, relaying one operator confirmation. `skill-bill goal status` stays CLI-only; no skill wraps it.

## Phase and operation concepts

An existing `spec.md` without a manifest is preparation intake. The full run reads its requirements and writes the manifest and executable subtask specs in the same folder, preserving the parent file and design assets. File paths, folder paths, bundle keys, and issue keys resolve to that existing bundle. Preparation uses the current branch as its base. Unrelated manifest bundles do not participate in its nested-decomposition check.

Standalone phases and operations are operator tools. Agents may invoke
`skill-bill phase` or `skill-bill operation` only when the operator explicitly
requests the corresponding standalone task. A named `phase:` or `operation:`
form, or an unambiguous standalone validation request, supplies that intent.
A full feature request or issue URL does not.

Agents must not use these commands to assemble a workflow, prepare a missing
spec, recover a blocked goal, or add checks they chose themselves. A full run
stays on the goal route. The runtime prepares missing specs as part of starting
new work, then drives its durable planning and execution. The
runtime executes internal phases through its run loop; workers carry out their
supplied briefing without starting standalone phase or operation commands.

### Phases

`skill-bill phase <review|validation|plan|pr>` and `skill-bill code-review` run one in-memory phase through the same run loop, with no workflow row, new branch, or checkpoint commit. `skill-bill code-review` finds, verifies, and fixes findings in both modes. `phase plan <KEY> [description]` writes a governed spec bundle that `skill-bill goal` runs, and a direct plan blocks. Implementation and simplification run inside workflows and consume their plan output. `phase pr` composes `commit_push -> pr`. It refuses a detached, protected, or base branch before staging. The runtime commits all staged, unstaged, and untracked changes, excluding ignored and runtime-private files, then pushes before creating or updating the pull request. A clean retry pushes the existing commit without creating an empty one. `commit_push` and the durable definitions are not runnable on their own.

Standalone validation always selects the platform gate from the current branch's
tracked files and runs full branch validation, regardless of which files changed.
Completion still requires successful gate evidence. A failed file inventory,
ambiguous platform ownership, or missing gate declaration blocks validation.

### Operations

`skill-bill operation <update-check|release|unit-test-value-check|feature-guard|feature-guard-cleanup|pr-review-fix|verify>` runs one runtime operation with no feature-task workflow. `release` confirms in two invocations. The first stores the proposed version and changelog and exits `awaiting_confirmation` with a token. `confirm:<token>` then tags and pushes exactly the stored proposal, once. `unit-test-value-check` is a read-only report over the current changes or `scope:` and needs no confirmation. `feature-guard` and `feature-guard-cleanup` confirm the same way as `release`. Their first invocation changes no file and stores a plan anchored on HEAD and the current branch. Only `confirm:<token>` edits. After confirm, cleanup runs the `validation` definition. `pr-review-fix [<pr>]` proposes a per-thread matrix over the PR's unresolved GraphQL review threads. `confirm:<token> select:<...>` fixes only the selected threads, runs `validation`, then replies, and pushes only with `push:on`. `verify <intake> [target:<pr|branch|base..head>] [mode:inline|delegated]` is report-only. Its intake is free text (a Linear issue key or URL, or the requirements themselves) or `spec:<path>`, and an omitted target verifies HEAD against `origin/HEAD`. It parks a verify workflow (stored `workflow_name` `bill-feature-verify`) at the extracted criteria and exits `awaiting_confirmation`, with the workflow id as the token. `confirm:<token>` runs the audits, the review, and the verdict on that workflow.

The retired skills (`bill-feature`, `bill-feature-spec`, `bill-code-review`, `bill-code-check`, `bill-feature-verify`, `bill-monitor`, and the rest) are not installed; an install over an old home removes their links and copies. Telemetry `skill` values, the verify `workflow_name`, the workflow skill label, and the quality-check `routed_skill` (`bill-code-check`) keep their retired names because remote telemetry and stored rows key on them. Never tell an agent or operator to invoke a retired skill.

Bundled skills and packs are defaults, not the framework boundary. Teams may replace them while retaining governed source shape, generated-output boundaries, manifests, install staging, validators, dynamic discovery, and loud-fail.

## Runtime Agent Behavior

Agent-specific behavior uses injectable strategies on `AgentRunProcessRequest`, not identity branching in the process runner: `progressProbe`, `declaredProgressProbe`, `activityProbe`, `progressEmitter`, `idlePolicy` (`HEARTBEAT_EXTENDED` | `DB_PROGRESS_ONLY`). `ProcessWaitLoop` calls strategies only; new agents add a strategy constant. Crash reconciliation: `FeatureTaskRuntimeWorkerSupervisor` self-heals expired-lease rows to resumable at startup.

When goal routing selects the build quality gate and the dominant platform pack declares `validation_gate.build_command`, build is one agent session that runs only that pack's `build_command` (Kotlin: `./gradlew compileKotlin`), reads that output, fixes every finding in that session, then runs `cache_bypassing_build_command` once to confirm. Build is compile/buildability proof only: no suite tests, no full check, no substitute agent-run gate. Do not run `collect_all_full_gate_command`, `./gradlew check`, `check --continue`, `skill-bill validate`, `skill-bill phase validation`, or any other repo-root checklist. The runtime does not start another agent for repair turns. It may still run one cache-bypassing verify after the agent signals complete; remaining findings persist `findings_open` and block, and an operator resume starts one new build session. Do not rerun the pack build command after each individual finding, and do not launch delegated subagents. Targeted compile tasks are allowed while repairing when they are part of that same pack gate. Default standalone runs skip build (`review -> validate`); only goal children stamped for build use `review -> build -> write_history`.

## Commit Structure (feature-task / goal subtasks)

Decomposed goal runs use `same_branch_commit_per_subtask`: each completed subtask leaves exactly one commit on the feature branch, not a chain of checkpoint commits in branch history.

- Before review, the runtime creates the active subtask commit or amends its proven owned HEAD, records the exact reviewed target/tree identities, and launches review only after durable identity persistence. A message-only amend with the same tree may carry approval forward. `write_history` and `commit_push` never reopen earlier phases. After the bounded `review_fix` round, `commit_push` does not launch an agent. The runtime stages every non-runtime-private dirty path as this subtask's work, including files written after implement or by another process, commits with a subject from the issue key and subtask name, pushes, and records `commit_sha` into the decomposition manifest. Extra dirty content does not block and does not re-enter audit or review.
- Checkpoint history lives under `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`. Those refs preserve pre-amend commits the branch no longer names; they are not reachable through `git log` on the branch without an explicit ref argument.
- Pruning deletes a subtask's checkpoint refs only after that subtask's commit is pushed and its manifest entry records a non-blank `commit_sha`. Pruning is idempotent; a hard manifest reset prunes the refs of the subtasks it reset. Blocked or abandoned subtasks keep their refs for recovery.

## Edited subtask specs on resume

A full goal launch checks unfinished subtask specs against their saved planning hashes
before admitting an existing child. When a readable spec has changed and its saved
plan still satisfies the durable contract, the runtime applies scoped replan with
shared preplan refresh and continues through planning. It records the repair with
the subtask ID and both hashes. Completed and skipped subtasks retain their planning
records and commits. The existing scoped-replan liveness and digest checks still
refuse unsafe changes. Missing specs and invalid planning records stay with their
existing recovery paths.

## Acceptance-audit repair planning

The first acceptance audit inspects all production criteria in `audit`. Later
rounds inspect only the unresolved criteria in the last accepted report. Satisfied
criteria stay closed across repairs and resumes. The runtime rejects reports that
reopen them. Open findings
enter `audit_plan_fix`, a read-only reasoning step that plans each independent
gap before repair. Each plan item names the criterion, gap, production path,
ordered changes and dependencies, and evidence needed to close it. The agent
writes the plan as prose. The runtime persists it as an ordinary phase output
without parsing headings, field labels, or criterion coverage.

`audit_implement_fix` consumes that saved plan and the latest audit findings,
reconciles completed edits, and executes the remaining planned changes. Repair
continuations reuse the saved plan. Each new audit round with open findings
produces a new repair plan. A satisfied audit skips both repair steps. Audit,
repair planning, and repair do not run builds or tests. Existing audit retry and
non-shrinking limits still apply. If the comparison baseline is missing after
repair, the completed audit becomes a fresh baseline and restarts repair planning.
The second missing-baseline event in the workflow blocks. The phase ledger retains
this count across process restarts and operator resumes.

Existing workflows with the exact acceptance-audit revision 1 or 2 composition
can resume through a checked mapping to revision 3. The runtime retains their
original execution descriptor, phase records, ledger entries, and checkpoint
evidence. It verifies the old composition and retry/resume policy digests before
mapping to prose repair planning. Other strategy, traversal, or effective-policy changes
still refuse admission. A resumed repair without a completed repair plan starts
at `audit_plan_fix`. A diagnostic records the mapping at execution admission.

## Durable planning migration

Readiness and resume admit the declared preparation 0.2, planning 0.2, and
phase-output 0.6 tuple before spec-drift recovery or child execution. The runtime
validates the historical records, converts phase outputs to 0.7, and validates
the resulting preparation and import records. One immediate transaction updates
payload bytes, hashes, version provenance, and coupled child imports. Completed
work, skipped subtasks, commits, descriptors, and ledger entries retain their
meaning. Repeated admission changes no current record.

Unsupported, corrupt, or unsafe sources block with a typed refusal and preserve
the original records. The 0.6-to-0.7 conversion reuses planning and requires no
refresh. Existing spec-drift recovery remains responsible for changed unfinished
specs. Migration diagnostics contain version and result fields, without payloads.

Both packaged runtime entry points accept `--check-packaged-contracts`. This
database-free check compares producer pins with resources inside that candidate
image. Installation stages CLI and MCP candidates and checks both before
promoting either image. A parity failure leaves both installed runtimes intact.
