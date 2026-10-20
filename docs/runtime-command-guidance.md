# Runtime command guidance

These instructions apply to runtime commands, planning, execution, and goal commits. Read this document with `AGENTS.md`.

## Product Intent

`/skill-bill` is the only listed skill. Its full-run form resolves the supplied
requirements and launches `skill-bill <intake>` through the foreground goal
runtime with durable state, telemetry, packs, add-ons, and native subagents.
New requirements without a key use the next local key by default. The dispatcher
reads `.feature-specs/`, including ignored, untracked, completed, and archived
bundles, selects the project's prefix, and increments its highest integer issue
number. Generated `LOCAL-` hash keys do not select the project prefix. Decimal
suffixes occupy their integer number. An ambiguous or absent prefix requires
only a local prefix choice, never a tracker connection. An explicit "next
available key" request follows the same rule. Recheck the inventory before
launch, then prepend the key and a short title to the supplied requirements.
Design references such as HTML files supply requirements, not spec identities.

The key or path of an existing spec, or a persisted goal's key, resumes existing
work. The CLI also accepts `skill-bill goal <intake>`. The runtime prepares a
missing spec bundle and parent workflow before durable planning. Direct CLI
intake still requires a key and requirements; the dispatcher allocates the key
before calling it and does not reserve keys by writing placeholder specs or
workflow rows. Supplied requirements are authoritative and need no tracker.
For a tracker reference without requirements, the dispatcher first searches
local specs. A readable local spec launches directly, without `work status` or
`work list`. Without a local spec, intake may use `goal status <issue-key>` with
the current repository root to inspect that goal alone. Runtime discovery filters
by the requested issue and repository before decoding workflow records. An
unrelated workflow never participates in admission or blocks new work. Existing
work resumes without replacement or tracker lookup. A lookup failure for the
requested goal blocks resolution rather than counts as absent work. Only an unresolved
tracker reference without requirements uses its connected tracker. Lookup
failure asks for requirements, never infers them. Local key allocation does not
query the workflow database or a tracker.

The standalone feature-task skeleton is retired; a single feature is a
one-subtask bundle planned by the plan skeleton (intake, preplan, plan). A
workflow stored on the old skeleton stays readable and abandonable, but resuming
it fails with a message to abandon it and run `skill-bill <KEY>`. Nothing
rewrites the stored row.

A full-run request naming an existing spec file or bundle directory executes or
resumes that bundle directly. Planning-only instructions retained in the spec
describe the earlier invocation and do not select a standalone planning command.
An existing manifest requires no operator handoff to run the goal. If the
dispatcher mistakenly requested standalone planning and received the
existing-manifest refusal, it corrects the dispatch through the full runtime
using the original intake and flags. Explicit planning-only requests keep their
stop-before-implementation behavior.

Its `phase:<name>` forms run `skill-bill phase <name>`, and its `operation:<name>`
forms run `skill-bill operation <name>`, relaying one operator confirmation.
`skill-bill goal status` stays CLI-only; no skill wraps it.

## Blocked-phase operator decisions

To resolve a blocked child phase, use
`skill-bill feature-task retry-blocked <workflow-id> --phase <phase> --reason <reason>`.
The reason applies only to that reopened phase. When the operator also supplies a
decision that later phases must retain, pass `--mandate <decision>`. The runtime
appends it to the child workflow's existing `mandates_and_overrides` in the same
transaction that reopens the phase. Later phases and resumes receive the mandate
through their governed briefings. This does not change the spec, acceptance
criteria, comparison baseline, or other workflows. A retry without `--mandate`
does not promote its reason to a run mandate.

## Confirmed no-change claims

A validated implementation no-change claim still requires the independent source
audit. Once that audit confirms the claim, the child continues through review and
its selected quality gate without operator approval. The audit keeps its
`no_change_confirmed` output while routing it as satisfied for the existing review
entry gate. Malformed or rejected claims retain the audit repair path.

An undecided pause recorded by an older runtime resumes its child through audit
and the remaining phases. Its retained claim, audit evidence and pause artifact
stay readable. Previously recorded operator decisions retain their behavior.

## Model selection at launch

Resume uses the recorded per-step assignment. A later alias, remap, or default
does not reselect an accepted attempt. Alias `opus` qualifies only when the
inherited process environment pins `ANTHROPIC_DEFAULT_OPUS_MODEL` to
`claude-opus-5-5` or `anthropic.claude-opus-5-5`. `commit_push` stays the
runtime commit strategy and launches no agent. Details:
[Authoring model-specific phase strategies](model-specific-phase-strategies.md).


## Phase and operation concepts

Planning authors specs with one to five acceptance criteria each. A subtask needing more than
five criteria splits into the fewest dependency-ordered subtask specs needed to stay within that
limit. Distribute criteria evenly, with counts differing by at most one. Six criteria become two
specs with three each; eleven become three specs with four, four, and three.
Every requirement remains covered, and each criterion describes one focused production outcome.
The parent summarizes feature-level outcomes within the same limit. An existing operator-authored
parent remains unchanged, even when it has more than five criteria; its executable subtasks still
obey the limit. This is a planning instruction and sizing rule. It adds no runtime rejection or
block reason for oversized specs.

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

Workflow phases advance in order. Once execution leaves a phase, later edits,
validation repairs, checkpoint commits, and resume do not reopen it. Repair loops
stay inside their owning phase, including audit planning and implementation
repairs, review fixes, and monitor repairs. A request to return to a previous phase
leaves that phase complete and advances to the next phase. Monitor repair publishes
its changes inside the monitor phase before observing CI again, without rerunning
commit/push or PR.

`skill-bill phase review` runs the in-memory, report-only `standalone_review` slot. It prints the findings register and exits 0 for either valid verdict. Invalid, incomplete, or failed reports print available findings and a block reason, then exit 1. It never edits or commits. The `code-review` CLI command remains registered; it is not the driver of `phase review`. Full feature runs keep the `code_review` slot, which verifies findings and can fix Blocker and Major findings before reporting the rest. Validation, `pr`, and `monitor` also run in-memory, with no workflow row, new branch, or checkpoint commit. `phase qa` also runs in-memory. The dispatcher form `/skill-bill [<instructions>] phase:qa` maps to `skill-bill phase qa [<instructions>] --agent <agent>`, where `<agent>` is the currently executing agent. It runs the report-only `qa` slot from the routed platform pack's `qa_capability` guidance, or completes as an `unsupported_target` skip without launching an agent when no installed pack declares one. The agent writes its receipt and evidence under `<state dir>/qa/<repo-key>/<invocation>/`, where the state dir is `SKILL_BILL_STATE_DIR` or `~/.skill-bill` and every worktree of a repository shares one repo key; the directory stays outside `.git/`, and the runtime passes it to the agent as a writable directory. The QA step may build and install the app artifact the pack guidance names and run device or instrumentation tests for the changed flows. The runtime validates the receipt against `orchestration/contracts/qa-receipt-schema.yaml` and the tested HEAD and tree and releases the invocation's device leases. QA never blocks: `phase qa` exits 0 in every case and prints each problem as a `Warning:` line with the verdict. A self-contradicting receipt is downgraded to its honest verdict and keeps its flows, assertions, and findings (`passed` with an untested flow or no passing exercised flow becomes `incomplete`, `passed` with findings becomes `findings_open`, `findings_open` without findings becomes `incomplete`). A missing or invalid receipt, an identity mismatch, foreign evidence, or a stopped QA agent settles `incomplete` with reason `other` or `interrupted`. A run that could not start settles `skipped`. A HEAD, tree, or worktree status change made during QA is kept and named in a warning. The QA agent runs on a test account and may take every in-app action; it picks a Local or staging environment and settles `skipped` with `production_only` when only production is available. The QA agent takes a device with `skill-bill qa lease acquire --invocation <id> --device <serial>` and gives it back with `release`. The command hashes the serial with sha256 before storing or printing it, keeps one lease file per device under `<state dir>/qa/<repo-key>/leases/`, and exits 0 for both `acquired` and `contended`. A lease held by a live invocation is never touched; one whose owner process is confirmed dead is reclaimed. `phase plan <KEY> [description]` is the one durable standalone phase. It seeds the parent `spec.md` from the intake (an existing spec stays as the operator wrote it, and the plan may not change it), opens a `standalone`/`plan` workflow, and persists its phase records, ledger, and invariants like any run. It stops after the verified implementation-ready spec bundle, retains the standalone preplan and per-subtask implementation plans as durable planning checkpoints, records a completed decompose terminal, and prints the bundle paths, the plan workflow id, and `skill-bill <KEY>`. It creates no branch, ref, commit, or goal. A manifest already present for the key refuses a new plan and names `skill-bill <KEY>`. A key-only retry of a completed standalone plan settles any missing implementation checkpoints without launching an agent or changing the spec bundle. An incomplete plan resumes at `plan` without relaunching `preplan`; a different intake for it is refused and names the plan workflow id. `skill-bill <KEY>` finishes an incomplete plan first and then runs the goal in the same invocation, or exits 1 with the block reason and plan workflow id. It never writes a single-spec manifest over an incomplete plan. A completed plan's manifest is imported as it is on disk, and the new goal parent records the plan workflow id on its `preplan` and `plan` steps. The same transaction transfers the saved planning checkpoints to the goal, so unchanged specs enter implementation without running preplan or plan agents again. Missing checkpoints from older completed plans block with a key-only standalone-plan settlement command; they never silently launch replacement planning. Spec drift and contract validation retain their existing recovery behavior. `skill-bill goal purge` removes the plan workflows and leaves `spec.md` untouched. Implementation and simplification run inside workflows and consume their plan output. `phase pr` composes `commit_push -> pr -> monitor`. It refuses a detached, protected, or base branch before staging. The runtime commits all staged, unstaged, and untracked changes, excluding ignored and runtime-private files, then pushes before creating or updating the pull request. A clean retry pushes the existing commit without creating an empty one. `phase monitor [<issue-key|pr-url>] [--recover-stale-owner <owner-token>]` watches one exact pull request: the URL given, the single pull request mentioning the issue key, or with no intake the pull request headed by the checked-out branch. Resolution is read-only and never checks the target out in the caller checkout. The runtime fetches the pinned head and base, creates an owned temporary worktree and branch under the caller's `.git/skill-bill/standalone-monitor`, and runs observation and repair there, so monitors for different pull requests that share a head branch name run concurrently and the caller's branch, index, and uncommitted work stay untouched. Only this public command selects the standalone monitor execution. Goal finalization and `phase pr` keep their branch-based `commit_push -> monitor` composition. The monitor watches first and does not commit or push caller changes. It publishes a completed repair only to the pull request's head repository and branch while the remote head is still the last commit the monitor published or adopted, never to the base repository. When someone else pushes to the head while no repair is pending, the monitor fetches that commit, moves its clean owned worktree to it, and judges that commit's checks; a failure there starts a repair without spending the failed repair budget. It blocks only when it cannot follow the new head, for example when the owned worktree has tracked changes or the head moved again during the fetch. One monitor per pull request is admitted: a live owner blocks the second run, and a stale owner blocks with its owner token. `--recover-stale-owner` is monitor-only, requires an explicit target, refuses a malformed token, a token that does not match the recorded owner, a live owner, and retained unfinished work. Status, events, and the printed report name the pull request URL, repository, head branch and repository, invocation, worktree, and the cleanup outcome (`released`, or `retained` with its reason and path). Retained dirty or unpublished work blocks a fresh monitor of that pull request. A hard kill is treated as stale recovery, not guaranteed cleanup. Desktop cancel and run again target the exact invocation, and run again replays `phase monitor <exact URL>` without checking the head branch out in the caller. The command completes when every check passes, is skipped, or is cancelled. Initial CI failures and merge conflicts start repair without spending the failed repair budget. A merge repair fetches and merges the base tip current at that moment. A base that moves during a repair does not block it; when the published merge still conflicts with the newer base, the next poll starts another merge repair. Each completed and published repair counts once only after CI fails on the associated commit. The runtime observes the third repair commit before blocking, and reports failed commit identities and check links from recorded evidence. The repair agent discovers and runs the project's local checks, as validation does, and settles completed only when they pass; the runtime commits and pushes only a completed repair. A selector with no open pull request completes with a report that names it instead of blocking, and a merged pull request completes without work. A closed, unmerged pull request blocks. `commit_push` and the other durable definitions are not runnable on their own.
Standalone and workflow validation use the same agent strategy. The agent discovers
required checks from repository instructions, build configuration, scripts, and CI,
runs them, and repairs failures in the same session. Validation covers the full
project regardless of which files changed and requires no platform pack or declared
validation command. If discovery finds no applicable commands, the agent prints a
warning and completes the step so the workflow can advance. Unreadable configuration
or failed discovery remains an obstacle and does not count as an empty check list.

A monitor retry rechecks an abandoned owner's retained `publication_unknown` state
under the admission lock after confirming that the previous owner process is dead
on the same host.
It uses the resolved PR head remote to verify publication before removing owned
resources. Dirty, untracked, unpublished, or unverifiable work remains retained.
Explicit stale recovery uses the same resolved remote. Retention after an
undrained repair child continues to block retry. A resolved monitor target
that fails admission or setup records a blocked attempt with its exact PR and
head branch. Its worktree path stays absent until a worktree exists, so desktop
launch following can select the failure and display its reason.

Standalone phases print step transitions while running. Their final output retains
every completed step's result in execution order, so `phase pr` reports the created
pull request as well as the CI outcome. If a later step blocks, the report keeps
the earlier completed results before the block reason.

### Operations

`skill-bill operation <update-check|release|unit-test-value-check|feature-guard|feature-guard-cleanup|pr-review-fix|verify>` runs one runtime operation with no feature-task workflow. `release` confirms in two invocations. The first stores the proposed version and changelog and exits `awaiting_confirmation` with a token. `confirm:<token>` then tags and pushes exactly the stored proposal, once. `unit-test-value-check` is a read-only report over the current changes or `scope:` and needs no confirmation. `feature-guard` and `feature-guard-cleanup` confirm the same way as `release`. Their first invocation changes no file and stores a plan anchored on HEAD and the current branch. Only `confirm:<token>` edits. After confirm, cleanup runs the `validation` definition. `pr-review-fix [<pr>]` proposes a per-thread matrix over the PR's unresolved GraphQL review threads. `confirm:<token> select:<...>` fixes only the selected threads, runs `validation`, then replies, and pushes only with `push:on`. `verify <intake> [target:<pr|branch|base..head>] [mode:inline|delegated] [qa:on|off]` is report-only. Its intake is free text (a Linear issue key or URL, or the requirements themselves) or `spec:<path>`, and an omitted target verifies HEAD against `origin/HEAD`. It parks a verify workflow (stored `workflow_name` `bill-feature-verify`) at the extracted criteria and exits `awaiting_confirmation`, with the workflow id as the token. `confirm:<token>` runs the audits, the review, and the verdict on that workflow. `qa:` defaults to `off` and only `operation:verify` accepts it; a duplicate, conflicting, or unknown value is a usage error. The proposal names the selection, the workflow stores it as `input_context.qa_selection`, and a confirm that asks for a different value is refused. `off` writes a `disabled_policy` QA skip receipt without probing a device or launching an agent. `on` runs report-only QA after the completeness audit in a temporary detached worktree at the target head, records a receipt bound to another tree as `incomplete` with a warning, removes the worktree on every exit (a failed removal is recorded with manual cleanup guidance), and never changes the operator's checkout, HEAD, or target. QA problems never fail verify: a worktree that cannot be created settles `skipped` with `isolation_unavailable`, and an unusable receipt or a stopped QA agent settles `incomplete`. An unqualified `APPROVE` against open QA findings is recorded as `APPROVE WITH FIXES` with a warning, and a skipped or incomplete QA run, and every receipt warning, is reported as untested coverage. The report and `feature_verify_finished` telemetry carry `qa_outcome` (`not_selected`, `attempted`, `passed`, `findings`, `skipped`, or `incomplete`) and `qa_reason`. The MCP `feature_verify_workflow_*` tools read and update the same `input_context.qa_selection`.

The retired skills (`bill-feature`, `bill-feature-spec`, `bill-code-review`, `bill-code-check`, `bill-feature-verify`, `bill-monitor`, and the rest) are not installed; an install over an old home removes their links and copies. Telemetry `skill` values, the verify `workflow_name`, the workflow skill label, and the quality-check `routed_skill` (`bill-code-check`) keep their retired names because remote telemetry and stored rows key on them. Never tell an agent or operator to invoke a retired skill.

Bundled skills and packs are defaults, not the framework boundary. Teams may replace them while retaining governed source shape, generated-output boundaries, manifests, install staging, validators, dynamic discovery, and loud-fail.

## Runtime Agent Behavior

Agent-specific behavior uses injectable strategies on `AgentRunProcessRequest`, not identity branching in the process runner: `progressProbe`, `declaredProgressProbe`, `activityProbe`, `progressEmitter`, `idlePolicy` (`HEARTBEAT_EXTENDED` | `DB_PROGRESS_ONLY`). `ProcessWaitLoop` calls strategies only; new agents add a strategy constant. Crash reconciliation: `FeatureTaskRuntimeWorkerSupervisor` self-heals expired-lease rows to resumable at startup.

Status heartbeats report that a child process remains active. Workflow progress
labels and observed file activity report separate facts; process activity alone
does not establish source progress. A passing local check reports only a local
result. Remote CI remains pending until the monitor observes its result. While
checks remain pending, the existing monitor progress event can refresh status
every 30 seconds. That update reports waiting activity; it does not change the
CI verdict, deadline, or repair attempt count.

Every non-skipped goal child runs `audit -> review -> validate -> write_history`, so each subtask runs the full project validation gate and records its own validation result. Goal children no longer run `qa` or `qa_fix`. QA runs once per goal, in goal finalization, after every child completes: `qa -> commit_push -> pr -> monitor`. It tests the goal branch HEAD (the final stack branch for a stacked goal) against the parent spec acceptance criteria. QA findings enter `qa_fix -> build -> qa`, with three repair attempts counted across goal relaunches. `build` is the runtime-owned compile-only gate with its own repair turns, so QA re-tests only a fix that compiles; `qa_fix` itself never builds, tests, or validates. When build still fails after its repair turns, QA does not re-test: the goal records `qa_fix left the app without a passing build, so QA did not re-test the fix` with the build gate's reason in the QA outcome and the PR body, then continues to validation and `commit_push`. `qa_fix` gets each finding's steps, expected and observed behavior, and evidence, edits the goal branch working tree, and goal finalization commits its edits with the goal. `qa_fix` must fix every finding in source and report each as `<finding-id>: fixed: <files and how>`; a `qa_fix` that changes no file, or marks a finding `not changed`, is sent back for those findings. When the QA loop changed code, including build repairs, the full project validation gate runs once after QA settles. Nothing after QA re-runs it: a validation repair that changes the tested tree adds a warning to the PR body. QA never blocks the goal. Findings left after the third attempt are recorded as `findings` with `findings left open: <ids>` and the run continues to `commit_push`. The goal QA outcome keeps every finding QA reported during the loop, marked `fixed` when a later QA run no longer saw it and `not_fixed` otherwise; the desktop goal activity panel lists both, `skill-bill goal status` prints `qa_findings: fixed=<n> not_fixed=<m>` with the open ids, and the PR body names the not-fixed findings for the operator to review, and a goal QA run that cannot settle prints a `Warning:` line and reaches the PR body as a warning. QA warnings recorded with the receipt, including an unavailable QA capability and a HEAD, tree, or worktree status change made during QA, are listed in the QA output's `qa_warnings` and appended to the QA outcome in the PR body. The full run selects QA with the dispatcher token `qa:auto|off`, which the runtime receives as `--qa <auto|off>` or the `SKILL_BILL_QA_SELECTION` environment variable and persists in the goal continuation artifact. Omission resolves to `auto`. The goal records the selection on first run, and a resume that requests a different one is refused. `off` settles goal QA as skipped with `disabled_policy` and never probes a device or launches a QA agent. When the routed platform pack declares no `qa_capability`, goal finalization omits the `goal-qa` skeleton entirely. It records no QA phase or receipt, and the desktop progress pipeline omits the `qa` and `qa_fix` pills. An explicit standalone `phase qa` still reports an `unsupported_target` skip. An unknown, duplicate, or conflicting value (flag and environment disagreeing) is a usage error. Goals already in flight keep each recorded child's step list, so a child recorded on goal-child revision 3 still runs child-level `qa` and `qa_fix`. On such a child a tree change after QA sends `write_history` back to audit and QA up to three times; after that the child continues to history and commit with the warning `QA results are stale: code changed after the last QA pass`, which reaches the goal PR body. An in-flight goal with no recorded selection takes the first child's. Goal QA is skipped when every child ran and recorded child-level QA, or when a goal-level QA outcome already passed or skipped on the current tree. Otherwise it runs once. Build is the compile-only quality gate. It runs only when a launch explicitly selects it with `--quality-gate-selection build` (or the matching environment variable), or when a resumed child already recorded build; the runtime never switches a recorded child to a different gate. When build is selected and the dominant platform pack declares `validation_gate.build_command`, build is one agent session that runs only that pack's `build_command` (Kotlin: `./gradlew compileKotlin`), reads that output, fixes every finding in that session, then runs `cache_bypassing_build_command` once to confirm. Build is compile/buildability proof only: no suite tests, no full check, no substitute agent-run gate. Do not run `collect_all_full_gate_command`, `./gradlew check`, `check --continue`, `skill-bill validate`, `skill-bill phase validation`, or any other repo-root checklist. The runtime does not start another agent for repair turns. It may still run one cache-bypassing verify after the agent signals complete; remaining findings persist `findings_open` and block, and an operator resume starts one new build session. Do not rerun the pack build command after each individual finding, and do not launch delegated subagents. Targeted compile tasks are allowed while repairing when they are part of that same pack gate. Default standalone runs skip build (`review -> validate`); only a child recorded with build uses `review -> build -> write_history`. `implement` and `implement_fix` never run `./gradlew check` or any other full check; validate owns the full gate on every subtask.

## Commit Structure (feature-task / goal subtasks)

Decomposed goal runs use `same_branch_commit_per_subtask`: each completed subtask leaves exactly one commit on the feature branch, not a chain of checkpoint commits in branch history.

- Before review, the runtime creates the active subtask commit or amends its proven owned HEAD, records the exact reviewed target/tree identities, and launches review only after durable identity persistence. A message-only amend with the same tree may carry approval forward. `write_history` and `commit_push` never reopen earlier phases. After the bounded `review_fix` round, `commit_push` does not launch an agent. Commit/push runs no validation, CI discovery, readiness check, or agent. The preceding quality-gate phase owns local checks. PR publication requires no additional validation-evidence gate. Before that commit, the runtime marks the decomposition manifest complete so the committed file carries the completed status. The runtime then stages every non-runtime-private dirty path as this subtask's work, including files written after implement or by another process. Feature-spec files are included only when they are not gitignored. It commits with a subject from the issue key and subtask name, pushes, and records `commit_sha` into workflow state. The git-tracked manifest keeps `commit_sha` null. Extra dirty content does not block and does not re-enter audit or review.
- Checkpoint history lives under `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`. Those refs preserve pre-amend commits the branch no longer names; they are not reachable through `git log` on the branch without an explicit ref argument.
- Pruning deletes a subtask's checkpoint refs only after that subtask's commit is pushed and its manifest entry records a non-blank `commit_sha`. Pruning is idempotent; a hard manifest reset prunes the refs of the subtasks it reset. Blocked or abandoned subtasks keep their refs for recovery.

## Goal CI monitoring

Monitoring counts distinct published repair commits only after CI fails on those
commits. Resume reconstructs qualified failures from retained runtime producer,
completion, publication, and Git evidence. Repeated observations of a commit keep
one failure. A later repair replacing the latest phase record does not erase a
previously qualified failure. Missing or contradictory history stays unknown, does not add a failure, and does not exhaust the cap. Missing monitor output blocks cap-sensitive resume. Initial CI failure, pending checks, unassociated observations, and merge conflicts do not consume this budget. Green checks complete monitoring only when the observed PR head matches the latest completed pushed publication.

Before choosing a host or container for CI repair, read the applicable `AGENTS.md`
files, `CONTRIBUTING.md`, the failing workflow, and any setup scripts it calls.
Check the selected environment before an expensive command: required toolchain and
tools, writable `HOME`, Gradle cache and temporary paths, and display support when
the checks need it. A past repair found containers without Git or Python. Check
those tools when the selected scripts need them. The Java checks also need a
writable `user.home`; desktop libraries may create `.skiko` there. On Linux, the
desktop workflow runs under `xvfb-run` and provisions `xvfb`, `xauth`, and native
build dependencies. Its Gradle commands include `clean check --no-build-cache`.
The current `validate-agent-configs.yml` workflow checks out full Git history,
sets up Node 20 and Temurin Java 21, then runs `./gradlew check --no-build-cache`,
`npx --yes agnix --strict .`, and `scripts/validate_agent_configs`. Read the
current workflows and scripts for exact requirements and commands. Do not install
packages automatically or stop a shared daemon to prepare an environment.

Keep a working setup and useful discovery context within the repair invocation.
Reuse only caches and mutable state that invocation may own, never another
worktree's mutable state. Recheck the relevant instructions and scripts after a
change to their setup, configuration, toolchain, or workspace requirements. A
previously working setup does not validate a change you have not inspected.
Setup reuse does not skip checks: run all required local checks for every repair
candidate before the runtime publishes it. Preserve clean and cache-bypass
requirements, and do not carry a prior candidate's local pass forward as evidence.
Explain setup retries and any missing tool, permission, or display problem in the
phase's ordinary prose, with the concrete obstacle and any needed operator action.
Use the existing failure and recovery behavior.

CI repair starts with each failing job's logs and the exact failed command,
assertion, or error. The repair agent traces that evidence through the repository
and fixes the cause. A passing local rerun does not resolve a CI failure.
Intermittent failures require investigation of timing, concurrency, isolation,
or environment differences as the evidence warrants. Repair completion requires
a repository fix and passing required local checks. If investigation cannot
establish a fix, the agent blocks with the failed check URL, inspected evidence,
and unresolved cause or missing evidence. An unchanged tree and green local
checks cannot complete repair. The runtime owns commit, push, and subsequent CI
confirmation. This applies to goal and standalone monitoring.

After all subtasks finish and the goal opens or finds its pull request, parent
finalization runs the existing monitor and bounded CI repair loop before reporting
completion. Monitoring belongs to that goal, so it publishes parent progress and
does not register a standalone phase execution. Both goal and standalone
monitoring start repair on the first poll reporting a failed check,
even while other checks are pending, and on the first poll reporting a merge
conflict, even when no checks are listed. The repair agent runs the project's local checks, as validation does, and settles completed only when they pass; the runtime commits and pushes only a completed repair. Success still requires every check to pass,
be skipped, or be cancelled with none pending. A missing pull request, wrong
branch, unavailable checks, or exhausted repair loop stops the goal at the failing
finalization step. A later goal run reuses completed subtasks and the existing PR
and monitors again. Accepted no-change goals create no PR and skip monitoring.

Finishing the subtasks does not complete the goal. PR publication and monitoring
must finish before the runtime records goal completion or the IDE reports "done".
Status also honors persisted completed goal rows from older runtimes that did not
write the newer completion control marker. Completed subtasks alone do not prove
that monitoring is running. Running status requires a verified live process owner.
A merged PR completes monitoring regardless of failing or pending checks. The
monitor checks the PR's state on every poll so a merge during CI ends the wait.
When every subtask is terminal, resume skips the planning sweep and continues
finalization without reading archived planning specs.
Goal and IDE status read workflow state from the database without consulting spec
projections. Issue-key spec lookup checks `.feature-specs/done` when no active
spec matches. When a bundle moves to `done`, subsequent manifest projection
writes follow the archived bundle instead of recreating its active directory.
A resumed goal recognizes its merged pull request before committing, pushing, or
trying to publish another pull request, including after deletion of the remote
feature branch. Before returning a failed check or conflict, monitoring refreshes
the pull request state so an observed merge completes the run. Repair-limit
reports label the retained failure as the last observation.
An empty check report keeps goal monitoring active until checks arrive and pass
or the monitor reaches its timeout. It never counts as CI success. A timeout or
exhausted repair loop reports a blocked goal rather than completion.

A long-running child's phase ledger retains its latest bounded window. Resume
checks planning-import entries that remain in that window alongside the durable
planning provenance and completed phase records. Normal removal of older ledger
entries does not require replan or discard the child's execution progress.

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

The initial `audit` reads every authored acceptance criterion. It records findings
as ordinary prose and later rounds audit the criterion selected by committed
accounting. The runtime keeps authored text and aliases with that accounting. It
does not require headings, field labels, IDs written by the agent, coverage
markers, or a strict adoption receipt in an audit or repair plan.

`audit_plan_fix` is a read-only reasoning step. Its prose plan covers the selected
criterion's remaining defect class: the latest gap plus sibling production paths of
the same forbidden pattern. A repair may include shared
prerequisite edits, but those edits do not accept another criterion. Each reserved
repair cycle records at most one unsuccessful outcome. The third unsuccessful
outcome blocks that criterion. Explicit operator resume of the blocked goal resets
the active failure counts to zero and grants three new outcomes per criterion,
while retaining prior outcome identities and source evidence. An ordinary state
reload does not reset the budget. A successful outcome closes source work for it
without erasing earlier failures.

Only the initial discovery audits the whole authored set. After it, the runtime
settles one criterion at a time and trusts each settled result: an accepted
criterion is never re-checked, and no full audit runs again. When the focused
queue is empty, every criterion is accepted and the run moves on to review. Source
acceptance does not say that tests, build, or other project-quality work ran.
Those obligations stay in their existing review, build, and validation phases.
Status and detail readers report committed facts only. Older aggregate attempts
without criterion attribution remain unavailable, not zero. Starting prospective
per-criterion accounting for such a workflow requires a separate explicit
operator decision. Audit, repair planning, and source repair do not run builds or
tests.

When a completed phase cannot be admitted, the block report retains the rejected
completion's reason and attempt. A changed or unreadable bound spec names its
path and preserves the producer output for recovery. It does not accept the
audit against a different spec or erase earlier criterion outcomes.

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
