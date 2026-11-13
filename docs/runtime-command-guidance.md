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
by the requested issue and primary repository before decoding workflow records.
Goal lookups match the primary repository across its checkouts. Standalone rows
match the exact checkout. An
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

The shipped Kotlin and KMP goal gates honor the project's Gradle cache settings,
including verification after a repair. Their legacy `cache_bypassing_*` manifest
keys select the verification commands but do not force cache bypass. The keys
and persisted run format stay unchanged. Repository CI commands may still disable
caching to avoid shared-cache contention.

## New-task chat preparation and launch

New-task chat gathers repository context, clarifies requirements, checks infrastructure
and access, and resolves blockers before it offers a goal start card. It infers what
it can from available evidence and batches questions that affect the outcome. This
preparation stays in chat. A standalone planning run is a separate explicit request.

Only the operator clicking the inline start card launches a goal from new-task chat.
Chat replies, issue keys and requests to implement do not authorize a CLI or MCP
launch. The app owns the start action and issue-key entry. An explicit request for
direct implementation stays in chat without creating a goal.

A new-task goal or plan requires an explicitly accepted complete issue key. Chat
proposes the next available key from the selected project's local inventory, or
extracts a proposal from an operator-supplied Linear issue URL. Proposals never
reserve keys or authorize a launch. Sentence mentions, including sentence-leading
keys, and URLs do not count as acceptance. A message containing only the key or
a clicked "Use <key>" action accepts it. The latest explicit selection wins.
The app shows the accepted key on the start action. Without one, Start opens a
complete-key picker with proposals and a separate start button. Changing the
repository clears the selection. Admission rechecks ownership before launch and
refuses a taken key rather than silently choosing another one.

Cancellation retains the task's history for bookkeeping. Once cancellation settles,
the operator can assign its key to another task without purging history or requesting
reuse separately. The runtime creates an independent parent and preserves earlier
records. A running or resumable task still owns its key. Start admission rechecks
ownership after preparation so cancelled history cannot override a newer live task.

## New phase and operation iteration priority

An explicit new phase or operation iteration takes priority over an earlier paused,
cancelled, idle or blocked iteration for the same target. Historical results and
unfinished work do not reserve admission for an inactive iteration. A new iteration
does not preempt a running execution or steal a held resource lock. Preserve earlier
run identities, results and completed work; a fresh request does not erase history
or answer a parked operation's confirmation proposal.

Standalone monitor admission applies this rule under its target lock. A recorded
settlement identifies an inactive invocation even when the hosting process remains
alive. An unsettled owner requires same-host positive process-death evidence.
Archive the prior ownership record before advancing its generation. Discard verified
owned cancelled work; preserve other unfinished resources with their archived
ownership record and report their location. They no longer block a fresh monitor.
An undrained repair child remains protected until its shutdown is established.
Shared phase and operation launch completion includes ownership cleanup, so a new
iteration cannot race a completion notification that still holds the launch lock.

## Standalone review finding actions

The desktop review detail has an Actions section with "Fix findings" and "Verify
findings". Both open a scoped agent chat turn for the selected invocation. Fix
findings repairs open findings. Verify findings checks the current code without
editing source files. Both record confirmed fixes against the original review.

`skill-bill prepare-review --invocation-id <id> --format json` registers findings
from that finished review's saved report for feedback. It returns the existing
import-review payload, including `review_run_id`. It never creates a new review
run ID. Repeated preparation preserves existing findings and feedback. Missing
review identities, unfinished executions, truncated legacy reports, or conflicting
stored finding IDs refuse preparation with exit code 1.

Use that run ID with `triage --run-id <id> --list`, then record each confirmed fix
with `record-feedback --run-id <id> --event fix_applied --finding <finding-id>
--note <evidence>`. Use the selected database with `--db` on every command. Findings
that remain unresolved keep their existing status. These actions do not launch the
goal's `verify_findings` or `implement_fix` phases, commit, push, or rerun the review.

Before checking or repairing findings, the scoped agent calls
`start-review-action --run-id <id> --action <fix|verify> --format json` and retains
the returned `action_id`. At completion it calls `finish-review-action --run-id
<id> --action-id <action-id> --format json`, adding `--failed` when work could not
finish. These commands persist activity on the original review. Completion counts
come from stored finding statuses and survive reopening the detail. An unfinished
action records its start without claiming completion. Both detail buttons disable
when every finding is fixed.

Standalone plan details hide branch changes and show "Implement" beside the run controls. It stays
disabled until that plan has finished with a saved report. It starts the goal for
the plan's repository and issue key using the existing goal launch controls.
Goals continue to show changes.

## Blocked-phase operator decisions

Resuming planning reopens a settled feasibility block with a fresh repository
baseline. This includes safety blocks caused by unrelated edits in the same
checkout. The runtime retains the failed cycle, accepted unit plans and commits;
committing those edits does not require resetting the goal or reverting them.

To resolve a blocked child phase, use
`skill-bill feature-task retry-blocked <workflow-id> --phase <phase> --reason <reason>`.
The reason applies only to that reopened phase. When the operator also supplies a
decision that later phases must retain, pass `--mandate <decision>`. The runtime
appends it to the child workflow's existing `mandates_and_overrides` in the same
transaction that reopens the phase. Later phases and resumes receive the mandate
through their governed briefings. This does not change the spec, acceptance
criteria, comparison baseline, or other workflows. A retry without `--mandate`
does not promote its reason to a run mandate.

For an exhausted acceptance-criterion budget, retry the child's `audit` phase.
This explicit retry grants a fresh budget and preserves prior outcomes. It also
reopens the audit when its phase record completed before the workflow blocked.
The retry schedules a read-only source audit before further repairs. A stopped
repair planner can also return to audit after scoped crash reconciliation confirms
its worker is dead and releases ownership. A live or unverifiable worker blocks retry.

## No-change pause decisions

When a goal subtask finishes with no code changes, its audit pauses and the goal
waits for an operator decision. The desktop app only shows the pause. Record the
decision with
`skill-bill goal operator-decision <issue-key> --subtask <n> --decision <decision>`,
then resume the goal. The decision takes effect on that resume.

- `retry_fix` settles the paused audit as a rejected no-change claim. The criteria
  the claim covered stay open, and the instructions go to the next repair. It
  requires `--instructions "<text>"`.
- `accept_and_advance` closes the subtask as no-change and moves to the next one.
- `abandon_subtask` stops the subtask as abandoned.

Resuming without a decision reruns the paused audit and applies none of these.
The command rejects subtasks without an undecided no-change pause.

## Ambiguous historical quality state

A goal whose parent workflow was imported without proof that final quality never
ran blocks before final-quality work with "ambiguous historical quality state". When the original
quality evidence is gone, record the operator's choice with
`skill-bill goal fresh-quality <issue-key> --reason <why>`. The parent's quality
record becomes clean and unstarted, and the next goal run admits a fresh quality
execution with new cycles.

A goal relaunched from a project-store manifest needs no operator choice when it
plans before any subtask starts. Once a completed plan governs its parent spec,
the runner clears the ambiguity itself.

The same command handles a goal that blocks with "The outcome of goal-quality
step '<step>#<attempt>' is unknown". That happens when the runner died during an
agent-backed QA or QA fix step, before the step recorded its outcome. The
command retires the unsettled quality execution, and the next goal run admits a
fresh one.

A goal run never needs this command for an interrupted validation step.
Validate only has to make the build green on the current tree, so the next run
starts it again on whatever the tree holds. Other interrupted mutating steps
keep the block.

Validate blocks for one reason: three attempts in a row failed to make the
build green. Any failed attempt, whether the agent settled blocked, its process
died or its output was unusable, starts a fresh attempt. A provider-limit pause
still pauses at once.

The command refuses while a runner holds the goal, and when the parent has
neither ambiguous nor unsettled quality state. It does not change subtasks,
child workflows, specs or commits.

## Completing a goal outside the runtime

When a goal's work has landed by other means, run
`skill-bill goal complete <KEY> --commit <sha> --reason <reason> --confirm-issue-key <KEY>`
(`--force` skips the confirmation). The runtime resolves the commit, records an
out-of-band acceptance against it for every subtask that is not complete, skipped,
or completed without changes, marks those subtasks complete, retires any active
final-quality execution, and records the goal as complete, all in one transaction.
Subtasks the runtime already finished keep their own commits and history. Later goal
runs report the goal as already complete; an accepted subtask needs no child history
draft. The command refuses while a runner holds a live execution lease (stop it with
`skill-bill goal stop <KEY>` first) and when the goal is already complete. It runs no
quality gates and opens no pull request.

## Block incident recovery

Work detail includes the runtime-built `incident` projection and `incident_history`. Status and run output use that same projection for the diagnosis, next action, report, and telemetry lines. The detail JSON adds `recover_command` only when recovery is available or prerequisites need confirmation; it includes the incident revision and one `--confirm-prerequisite` flag for each unmet prerequisite.

`skill-bill goal status` and `skill-bill goal watch` accept `--verbose` to include saved incident
evidence references. The flag affects terminal rendering only; it does not change incident data or
recovery policy. It also works with the one-snapshot `--monitor` status mode.

When a block incident has a settled diagnosis with a supported action, preview it with
`skill-bill work recover <work-id> --incident <token> --preview`. The JSON names the
action, its effects, the exact target workflow or invocation, the phase, and the
incident revision. If the diagnosis lists prerequisites, the result is
`needs_prerequisites`. It lists each one and shows the planned preview the recovery
would use once every user-confirmable prerequisite is confirmed.

To apply, pass the revision from the preview:
`skill-bill work recover <work-id> --incident <token> --revision <revision>`.
Repeat `--confirm-prerequisite <kind>` once for each prerequisite you have confirmed,
and pass `--agent <agent>` to choose who runs the resumed work. The runtime rejects a
revision that no longer matches (`stale_incident`), an attempt that is still in
flight (`recovery_in_progress`), and a work id that is not the incident's workflow,
parent goal, or invocation (`unknown_target`). Each rejection exits 1. Repeating the
same request replays the recorded attempt and does not apply the action twice. A
recovered result reports the attempt status, any typed failure, and the launch result
of the resumed run. A launch that fails settles the attempt as `failed` with
`resume_failed`, and the command exits 1.

Resume launches the target recorded on the attempt, either the goal workflow or the
standalone phase invocation. It does not launch the work id you passed. Only one
caller can claim an `applied` attempt for resume. A concurrent caller gets
`recovery_in_progress` or the settled attempt.

**Results.** Every response is JSON with a `result` field.

- `available`: preview only. `preview` holds `action`, `label`, `effects`,
  `target_workflow_id`, `target_invocation_id`, `phase_id`, `incident_token` and
  `incident_revision`.
- `needs_prerequisites`: preview only. `prerequisites` lists each unconfirmed
  prerequisite as `kind` and `instruction`, and `planned_preview` shows the preview the
  recovery would use.
- `existing_attempt`: preview only. An attempt for this incident, action and revision
  already exists. The payload carries `attempt_id`, `attempt_status` and `failure`.
- `recovered`: apply only. The payload carries `attempt_id`, `attempt_status`,
  `failure` and `launch`. `launch` is a nested result: `accepted`, `launch_failed`
  with a `reason`, or `rejected` with a `reason`. It is `null` when no resume ran,
  for example when a replayed attempt had already settled.
- `rejected`: the payload carries `reason` and, for some reasons, `detail`.

Preview never writes anything and exits 0 unless the result is `rejected`. Apply exits
1 when the result is `rejected` or the attempt status is `failed`.

**Rejection reasons.** A rejection writes nothing and exits 1.

- `stale_incident`: `--revision` does not match the stored incident revision. Preview
  again and pass the new revision.
- `recovery_in_progress`: another attempt for the incident is `requested`, `applying`
  or `resuming`, or a replayed attempt has not settled yet.
- `unknown_target`: the work id is not the incident's workflow, parent goal or
  invocation, or the work item cannot be resolved. A saved resume target that is
  missing or belongs to another repository also returns this reason.
- `not_found`: no incident exists for the token.
- `ownership_mismatch`: a live goal-runner execution lease or worker lease, or a
  process whose state cannot be verified, still holds the target.
- `changed_target`: the database file changed during the request or between apply and
  resume.
- `prerequisite_unmet`: apply ran with unconfirmed prerequisites. `detail` is a
  comma-separated list of their kinds. Confirm each with
  `--confirm-prerequisite <kind>`.
- `recovery_unavailable`: `detail` names the typed reason. Eligibility reasons are
  `no_supported_action`, `investigation_not_settled`, `not_blocked`,
  `generation_changed`, `attempt_limit_reached`, `operator_decision` and
  `quality_gate`. Service refusals are `diagnosis_unavailable`, `action_changed` and
  `policy_facts_unavailable`. Target-resolution failures use names such as
  `workflow_not_blocked`, `occurrence_changed`, `worker_not_active`,
  `missing_workflow` and `missing_invocation`. Blocks caused by
  `attempt_limit_reached`, `operator_decision` or `quality_gate` are never
  recoverable through this command. Use the normal operator path for them.

**Attempt statuses.** `attempt_status` uses these values:

- `requested`: the attempt is recorded and nothing has changed yet.
- `applying`: the runtime holds the attempt under an owner lease and is applying the
  action.
- `applied`: the block cleared and resume is pending. Repeating the same request
  resumes it once.
- `resuming`: the runtime is launching the saved target.
- `resumed`: the saved goal workflow or standalone invocation launched.
- `failed`: the attempt ended. `failure` says why.

**Failure values.** `failure` uses these values:

- `stale_incident` and `prerequisite_unmet`: the request basis changed before the
  action ran.
- `ownership_mismatch`: another live owner held the work when the runtime tried to
  take it.
- `action_failed`: the reopen or retry step threw. The block occurrence and its
  diagnosis are unchanged.
- `block_not_cleared`: the action ran but the workflow is still blocked.
- `resume_failed`: the block is cleared but the launch failed. Start the same work
  with the normal launch (`skill-bill work` launch or the desktop run action).
- `interrupted`: the owner lease of an `applying` or `resuming` attempt expired.
  Entry reconciliation marked it failed without reapplying the action or relaunching.
  Preview again and recover at the new revision.

**Incident recovery summary.** The incident's recovery summary is `unavailable` when
no supported action exists, `not_started` when recovery is offered but not tried,
`complete` when an attempt reaches `resumed`, and `failed` when an attempt fails.
Each change bumps the incident revision, so a new request after `failed` needs a fresh
preview.

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
does not reselect an accepted attempt. Final quality resumes with its admitted
inputs and plans, including when the caller changes the launch agent or uses a
different intake reference for the same goal. Child selection checks still apply.
Model and effort values pass through to the selected harness. Model names never
select prompts or execution behavior. `commit_push` stays the runtime commit
strategy and launches no agent. Details:
[Model-independent phase execution](model-specific-phase-strategies.md).


## Desktop new-task start

New-task chats make preparation a high priority for implementation work. The
agent inspects the repository and connected context, discusses the outcome,
scope, acceptance criteria, constraints and edge cases, and checks required
infrastructure, tooling and access. It infers answers from evidence and batches
material questions without repeating answered questions. Discussion and context
gathering do not start a standalone planning run. Plan runs require an explicit
operator request.

Readiness advice is advisory. When an operator requests a goal or plan with
unresolved material questions or blockers, the agent names them, explains their
consequences, recommends resolving them first, and asks once whether to proceed.
An explicit choice to proceed gets the requested start card without another
readiness confirmation. Its instruction preserves unresolved questions, blockers
and accepted assumptions alongside the requirements and resolved decisions. The
agent never claims unchecked prerequisites are ready or invents unknowns to delay
a ready task. Ready tasks need no extra readiness confirmation. This advice adds
no app readiness state or launch gate, and standalone phases and operations keep
their existing routes.

The operator still accepts a complete issue key before a goal or plan card is
offered and launches through the app's start card. A readiness override does not
waive either requirement or authorize a goal launch through chat tools. Explicit
requests to implement directly in chat use the agent's available tools without
starting a goal. Execution chats keep their execution context and action rules.

All provider launches default to native permission bypass, including execution
and new-task chats, workflow phases, review workers and background summaries.
Claude uses `--dangerously-skip-permissions`. Codex app-server explicitly sends
`approvalPolicy: never` with `danger-full-access` on thread start and resume,
and `dangerFullAccess` on each turn; one-shot Codex launches use
`--dangerously-bypass-approvals-and-sandbox`. Cursor uses `--force`, `--trust`,
`--approve-mcps` and `--sandbox disabled`, including ACP launches. Junie ACP
sets `brave_mode` to enabled and requires acknowledgement before prompting;
its existing headless review path remains autonomous. This policy supersedes
the earlier default of routing every eligible permission prompt to the operator.
Read-only phase instructions still constrain the requested work; they do not
select a restrictive provider sandbox by default.

Runtime launches outside chat approvals use the native headless CLIs. Skill-bill
does not host their tool requests and must not add a deny, cancel, skip or
unsupported-method response. The native harness owns tool execution and any
provider refusal. Codex uses `exec` with native bypass, Cursor uses its print
transport with force and trust flags, and Junie uses its headless text transport.
Claude already uses its native print transport outside chat approvals.

Approval cards apply only to chats. The chat broker remains connected for genuine
permission requests a harness still emits. Runs launch through the runtime with
native permission bypass and do not require an operator approval channel. A run
resume or rerun never starts a chat harness as an intermediary. Ordinary chat
questions use the question UI or prose and never manufacture a permission request.
Bypass does not confirm parked `verify` or
`pr-review-fix` proposals; their operator confirmation cards retain their own
contracts. Provider-managed restrictions remain authoritative, and an action
rejected by the provider is never replayed automatically.

Issue-key allocation has two owners. The dispatcher prose in
`skills/skill-bill/content.md` handles terminal intake, and
`NewTaskIssueKeyAllocator` handles desktop intake. Both inspect local spec
bundles, ignore `LOCAL-` keys when choosing a project prefix, and increment the
highest occupied issue number.

The desktop first admits the chat text. If it has no issue key, the operator can
choose a complete key or a prefix, or the runtime can allocate the next key. The
runtime composes a keyed intake, admits it again, checks that an allocated key
has not appeared in the inventory, runs preflight, then launches a goal or plan.
Standalone review, validation, QA and PR starts run their phase directly. A
refusal launches nothing. Intake is limited to 30,000 UTF-8 bytes.

## Phase and operation concepts

Planning authors specs with one to five acceptance criteria each. A subtask needing more than
five criteria splits into the fewest dependency-ordered subtask specs needed to stay within that
limit. Distribute criteria evenly, with counts differing by at most one. Six criteria become two
specs with three each; eleven become three specs with four, four, and three.
Every requirement remains covered, and each criterion describes one focused production outcome.
Planning sizes each criterion by estimated production lines, files, and areas (modules or owning
packages). A criterion above about 300 lines, 8 files, or 3 areas becomes its own subtask with its
own criteria, and each subtask spec records the estimates in a `## Criterion Sizing` section.
A criterion is never one sentence: it names the files and symbols it changes, the exact values and
edge cases, what it leaves to other criteria, and how audit confirms it.
The parent summarizes feature-level outcomes within the same limit. An existing operator-authored
parent remains unchanged, even when it has more than five criteria; its executable subtasks still
obey the limit. This is a planning instruction and sizing rule. It adds no runtime rejection or
block reason for oversized specs.

Planning traces required capabilities through their real producers, caller/wrapper
transport and lifetime owners before treating a criterion as executable. It checks
proposed APIs against configured project rules and covers calls without optional
setup. Shared prerequisites must be reconciled across the affected criteria before
subtask boundaries are fixed. Exact signatures and caller integration are separate
compatibility obligations. If either needs an operator amendment, planning reports
the complete known affected scope and a supported alternative together.

Audit repair uses the same capability check before requesting an amendment. Its
read-only impact inspection can identify the shared conflict in sibling criteria,
but execution and acceptance stay scoped to the runtime-selected criterion. An
operator retry does not itself amend a contract. Existing decisions apply only to
the scope they authorize.

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

`skill-bill phase qa` and `skill-bill goal` accept `--qa-device serial:<serial>`
or `--qa-device avd:<name>`. The option is refused on other phases and never
becomes intake text or an acceptance criterion. A goal uses the device only from
its current launch. Resuming it without the option lets QA choose a device.
`skill-bill operation verify` accepts the same option only with `qa:on`; the
proposal stores it as `input_context.qa_device`, its QA step uses it after
confirm, and a confirm naming another device is refused.

Validation runs the project's required checks and fixes every failure in the same
session, including build failures. It may run repository build and packaging commands,
including the pack build command, to prepare missing or stale validation artifacts.
After source repairs it rebuilds the affected artifacts and reruns all runnable required checks
against the current candidate. The separate compile-only build gate does not reserve
these commands away from validation. Fresh feature and goal-quality admission
freezes the project's saved build commands into the execution descriptor;
restart, build repair, and receipts reuse that admitted list verbatim (including
per-command working directories) instead of pack routing or rediscovery. Legacy
descriptors without a snapshot still use pack-declared build argv until a new
admission is created. A rebuild does not complete validation, and
artifact identity and integrity checks remain mandatory. Monitor repair uses the same
validation responsibility before publishing its fix, and the runtime then verifies it
itself: see the monitor repair publication gate below.

Workflow phases advance in order. Once execution leaves a phase, later edits,
validation repairs, checkpoint commits, and resume do not reopen it. Repair loops
stay inside their owning phase, including audit planning and implementation
repairs, review fixes, and monitor repairs. A request to return to a previous phase
leaves that phase complete and advances to the next phase. Monitor repair publishes
its changes inside the monitor phase before observing CI again, without rerunning
commit/push or PR.

`skill-bill phase review` runs the in-memory `standalone_review` slot with one `present_findings` step. After review admission, a fresh read-only verifier checks each finding against current source and returns exactly one `confirmed`, `rejected`, or `unresolved` disposition. It also classifies retained warning and coverage blocks as complete or incomplete. The runtime compares source observations before review, after review, and after verification. Changed or unreadable source, incomplete verification, or an inadmissible report blocks the phase; the runtime retains available review and verifier output as unaccepted evidence. An admitted incomplete-review warning stays in the verified register and makes the verdict `changes_requested`. Unresolved findings and confirmed findings that standalone severity and code-quality policy says need changes also make that verdict; rejected findings alone do not. The runtime presents the verified register and derived verdict. Standalone review never edits, stages, commits, or repairs. The `code-review` CLI command remains registered; it is not the driver of `phase review`. Full feature runs keep the separate `code_review` slot. Its inline review fixes every finding it can in session, including Minor and Nit, and lists each one as `status=fixed`, `open` or `refuted`. Fixed and refuted findings are stored with that outcome. The slot verifies open findings and repairs every verified one. Verification dispositions control repair routing even when the summary verdict disagrees. An omitted or unsettled finding stays verified and must enter repair. Rejected findings are excluded; a repair still open after the existing retry budget blocks for an operator. Validation, `pr`, and `monitor` also run in-memory, with no workflow row, new branch, or checkpoint commit. `phase qa` also runs in-memory. The dispatcher form `/skill-bill [<instructions>] phase:qa` maps to `skill-bill phase qa [<instructions>] --agent <agent>`, where `<agent>` is the currently executing agent. It runs the report-only `qa` slot from the `qa_capability` guidance of every routed pack that declares one, so one `phase:qa` serves every declared QA target (backend endpoints, web journeys, a desktop app, or a device or simulator), or completes as an `unsupported_target` skip without launching an agent when no routed pack declares one. The agent writes one prose `report.md` and its evidence under `<state dir>/qa/<repo-key>/<invocation>/`, where the state dir is `SKILL_BILL_STATE_DIR` or `~/.skill-bill` and every worktree of a repository shares one repo key; the directory stays outside `.git/`, and the runtime passes it to the agent as a writable directory. The intake is the QA requirements: a key or spec path that resolves to a local spec supplies that spec's acceptance criteria, and any other intake, such as a tracker URL or free text, becomes the single criterion, which the QA agent reads through a connected tracker when it is only a reference. With no intake, the criterion is the behavior the current branch changes against its base. QA runs every flow the criteria describe on the tree under test, whatever branch is checked out. It never inspects history, diffs, or source to decide whether a change is present; missing behavior is a finding. The QA agent maps the criteria to declared target kinds before probing tools. A target with no applicable criterion is out of scope; an in-scope flow is untested when its required tools are unavailable. The QA step may build and install the artifact the pack guidance names and run the tests for the flows the criteria cover. The runtime reads the report without a schema check: the first verdict word it finds (`passed`, `findings_open`, `incomplete`, or `skipped`) is the verdict, and a report naming none settles `incomplete`. Each `### <id> <title>` section is a finding, and its evidence paths sit under the invocation directory. The runtime releases the invocation's device leases. QA never blocks: `phase qa` exits 0 in every case and prints each problem as a `Warning:` line with the verdict. The runtime stores the same report in `standalone_phase_status.qa_report`, and the desktop phase activity panel shows it under the QA run. The runtime never rewrites the stated verdict; a `passed` report that lists findings keeps `passed`, records a warning, and opens no repair. A missing or unreadable `report.md` or a stopped QA agent settles `incomplete`. A run that could not start settles `skipped`. A HEAD, tree, or worktree status change made during QA is kept and named in a warning. The QA agent runs on a test account and may take every in-app action; it picks a Local or staging environment and settles `skipped` with `production_only` when only production is available. The QA agent takes a device with `skill-bill qa lease acquire --invocation <id> --device <serial>` and gives it back with `release`. The command hashes the serial with sha256 before storing or printing it, keeps one lease file per device under `<state dir>/qa/<repo-key>/leases/`, and exits 0 for both `acquired` and `contended`. A lease held by a live invocation is never touched; one whose owner process is confirmed dead is reclaimed. The QA agent registers each environment resource it creates with `skill-bill qa resource register --invocation <id> --kind <kind> --identity <identity>` and gives it back with `release`. The kinds are `compose_project`, `container`, `volume`, `network`, `process`, and `directory`. The invocation environment name is `sbqa-` followed by the lowercased invocation id with every character outside `[a-z0-9]` replaced by `-`, cut to 63 characters; a Docker identity must equal it or start with it followed by `-`, `_`, or `.`. A process must be running and is recorded with its start time; a directory must sit inside the invocation directory or carry the environment name, and may never be a root, the home, or the repository root. `register` prints `registered`, `conflict` (the identity belongs to another invocation, live or dead, and the output names its owner), or `rejected`, and exits 0 for `registered` and `conflict` and 1 for `rejected`. `release` tears the resource down and prints `released`, `not_held`, or `retained`, and exits 1 only for `retained`; a malformed record or an I/O failure prints `failed` and exits 1. The command keeps one record per resource under `<state dir>/qa/<repo-key>/resources/`. When QA ends, the runtime tears down every resource the invocation still holds, in the fixed order process, Compose project, container, network, volume, directory, and deletes a record only after its teardown succeeded or found the resource gone. Teardown runs only fixed commands for recorded identities: it never prunes, never runs `docker compose down` without `-p`, and never touches a resource without a record. At the next QA start the runtime tears down the records of invocations whose owner record is missing or whose owner process is confirmed dead; a live or unverifiable owner is left alone. Evidence files in the invocation directory are not resources and are never deleted by teardown. The standalone QA agent reports live progress with `skill-bill qa plan --invocation <id> --file <path>`, `skill-bill qa step --invocation <id> --case <id> --step <n> --status running|passed|failed`, and `skill-bill qa target --invocation <id> --name <name> --state running|ready|skipped`. The runtime seeds the checklist before launch and keeps it in `standalone_phase_status.qa_checklist`. The agent reports each step as it happens, one literal command per step with no shell variables or loops, because the runtime rejects progress once the run has finished and steps never reported stay pending. Each command prints `recorded`, `rejected`, or `failed` and exits 0 for all three, unlike `qa resource`, which exits 1 for `rejected`. A usage error exits non-zero before the command runs. Progress commands never decide the verdict, and goal QA and `operation:verify` do not use them because they have no status row. The launch sets `SKILL_BILL_REVIEW_DB` for an agent that inherits the environment, so these commands reach the database of a run started with `--db`. `phase plan <KEY> [description]` is the one durable standalone phase. It seeds the parent `spec.md` from the intake (an existing spec stays as the operator wrote it, and the plan may not change it), opens a `standalone`/`plan` workflow, and persists its phase records, ledger, and invariants like any run. It stops after the verified implementation-ready spec bundle, retains the standalone preplan and per-subtask implementation plans as durable planning checkpoints, records a completed decompose terminal, and prints the bundle paths, the plan workflow id, and `skill-bill <KEY>`. It creates no branch, ref, commit, or goal. A manifest already present for the key refuses a new plan and names `skill-bill <KEY>`. A key-only retry of a completed standalone plan settles any missing implementation checkpoints without launching an agent or changing the spec bundle. An incomplete plan resumes at `plan` without relaunching `preplan`; a different intake for it is refused and names the plan workflow id. `skill-bill <KEY>` finishes an incomplete plan first and then runs the goal in the same invocation, or exits 1 with the block reason and plan workflow id. It never writes a single-spec manifest over an incomplete plan. A completed plan's manifest is imported as it is on disk, and the new goal parent records the plan workflow id on its `preplan` and `plan` steps. The same transaction transfers the saved planning checkpoints to the goal, so unchanged specs enter implementation without running preplan or plan agents again. Missing checkpoints from older completed plans block with a key-only standalone-plan settlement command; they never silently launch replacement planning. Spec drift and contract validation retain their existing recovery behavior. `skill-bill goal purge` removes the plan workflows and leaves `spec.md` untouched. Implementation and simplification run inside workflows and consume their plan output. `phase pr` composes `commit_push -> pr -> monitor`. It refuses a detached, protected, or base branch before staging. The runtime commits all staged, unstaged, and untracked changes, excluding ignored and runtime-private files, then pushes before creating or updating the pull request. A clean retry pushes the existing commit without creating an empty one. `phase monitor [<issue-key|pr-url|branch:<name>] [--recover-stale-owner <owner-token>]` watches one exact pull request or one named branch: the URL given; `branch:<name>` with exactly one open pull request on that branch monitoring that pull request, several open pull requests refusing, no open pull request watching the branch when the remote branch exists (including merged-only history), and a missing remote branch refusing; the single pull request mentioning the issue key; or no intake with exactly one open pull request headed by the checked-out branch monitoring that pull request, several such pull requests refusing, the checkout watched when the all-state listing has never had a pull request from that branch and the branch exists, and nothing to monitor when merged or closed history still lists that branch with no open pull request or the checked-out branch is missing. A branch watch follows its tip and names each commit. The default branch, a protected branch, and a branch the caller cannot push are report-only. Branch targets never receive merge-conflict repair. A branch with no reported CI blocks after one hour. Workflows that trigger only on `pull_request` report no CI for a branch with no open pull request. A tip that moves during a repair blocks with the work preserved. Resolution is read-only and never checks the target out in the caller checkout. The runtime fetches the pinned head and base, creates an owned temporary worktree and branch under the `<state dir>/standalone-monitor/<repo-key>/`, outside `.git/`, and runs observation and repair there, so monitors for different pull requests that share a head branch name run concurrently and the caller's branch, index, and uncommitted work stay untouched. Only this public command selects the standalone monitor execution. Goal finalization and `phase pr` keep their branch-based `commit_push -> monitor` composition. The monitor watches first and does not commit or push caller changes. It publishes a completed repair only to the pull request's head repository and branch while the remote head is still the last commit the monitor published or adopted, never to the base repository. When someone else pushes to the head while no repair is pending, the monitor fetches that commit, moves its clean owned worktree to it, and judges that commit's checks; a failure there starts a repair without spending the failed repair budget. It blocks only when it cannot follow the new head, for example when the owned worktree has tracked changes or the head moved again during the fetch. One monitor per pull request is admitted: a live owner blocks the second run, and a stale owner blocks with its owner token. `--recover-stale-owner` is monitor-only, requires an explicit target, refuses a malformed token, a token that does not match the recorded owner, a live owner, and retained unfinished work. Status, events, and the printed report name the pull request URL, repository, head branch and repository, invocation, worktree, and the cleanup outcome (`released`, or `retained` with its reason and path). Retained dirty or unpublished work blocks a fresh monitor of that pull request. A hard kill is treated as stale recovery, not guaranteed cleanup. Desktop cancel and run again target the exact invocation, and run again replays `phase monitor <exact URL or branch:<name>>` without checking the head branch out in the caller. The command completes when every check passes, is skipped, or is cancelled. Initial CI failures start repair without spending the failed repair budget. When the target is a pull request, a merge conflict starts repair without spending the failed repair budget, a merge repair fetches and merges the base tip current at that moment, and a base that moves during a repair does not block it; when the published merge still conflicts with the newer base, the next poll starts another merge repair. Each completed and published repair counts once only after CI fails on the associated commit. The runtime observes the third repair commit before blocking, and reports failed commit identities and check links from recorded evidence. The repair agent discovers and runs the project's local checks, as validation does, and settles completed only when they pass; the runtime commits and pushes only a completed repair. Before it publishes a completed repair, goal or standalone, the runtime runs its own publication gate in the repair checkout. First it scans the files the branch changed since its merge base with the base branch, plus uncommitted and untracked files, for a complete `<<<<<<<` / `=======` / `>>>>>>>` conflict-marker block. A file whose base version already holds such a block, such as a test fixture, is not reported. Then it runs the repair cycle's admitted saved validation commands through the project-config runner (the same list bound before repair launch), without pack argv substitution. A remaining marker or a failing saved command rejects the repair output and relaunches the repair agent with the marker paths or the command findings and output tail, inside the phase's retryable-failure budget; exhausting that budget blocks the step. A runner that cannot launch, a missing accepted snapshot, or HEAD or file listings the runtime cannot read, blocks the step. None of these outcomes commits or pushes anything. Explicit known-empty validation follows the existing no-check publication policy; the conflict scan still runs first. A merged pull request URL completes without work. A closed, unmerged pull request blocks. `commit_push` and the other durable definitions are not runnable on their own.

A push timeout or an uncertain transport result first triggers remote verification.
The Git adapter checks the actual origin push destination for the exact commit it
attempted to publish. A match restores upstream tracking and completes publication.
Otherwise it retries that pinned commit once with a normal push and a 45-second
deadline, then verifies the remote again if the response is uncertain. Recovery
never repeats a force push. A moved local branch or changed push destination
refuses recovery, and an unresolved failure retains the original error, retry and
remote evidence, and local commit identity.

Standalone and workflow validation use the same agent strategy. The runtime resolves
missing validation categories through the shared project-check discovery path on first
use, persists the accepted command list, and reuses that saved snapshot on later
validation and monitor-repair launches. The agent runs only the admitted saved
commands at their recorded project-relative directories, repairs failures in session,
and does not rediscover checks from scripts, CI, or pack argv. Validation still
covers the full project regardless of which files changed. An explicitly empty saved
category prints an honest no-check warning and completes without counting skipped work
as passed. Unreadable configuration or failed discovery remains an obstacle and does
not count as an empty check list.

Confirmed unavailable tools, SDKs, or host platforms skip only the checks that
depend on them. Validation continues all independent checks and completes when
every runnable required check passes. Each skip produces a warning naming the
command or target, missing prerequisite, and confirming preflight or error evidence.
Skipped coverage never counts as passed. If an aggregate command stops solely at
unavailable tooling, run the remaining independent tasks separately. An artifact
check may skip when it depends on an artifact that the unavailable tooling must
produce, with that dependency reported. If no checks can run, complete with warnings
and state that no checks ran. Missing tooling alone does not block validation or
authorize package installation, project configuration changes, disabled targets, or
test skips. Source, dependency, test, analysis, formatting, fixture, and configuration
failures still require repair. Failed discovery and unexplained command failures
retain the existing failure behavior. This applies to standalone and workflow
validation and the local validation performed during monitor repair; remote CI
results keep their existing completion rules.

Monitor retries follow the new-iteration priority rule above. Explicit stale recovery
uses the resolved remote when verifying publication. A resolved monitor target that
fails admission or setup records a blocked attempt with its exact PR and head branch.
Its worktree path stays absent until a worktree exists, so desktop launch following
can select the failure and display its reason.

Standalone phases print step transitions while running. Their final output retains
every completed step's result in execution order, so `phase pr` reports the created
pull request as well as the CI outcome. If a later step blocks, the report keeps
the earlier completed results before the block reason.

### Operations

Release admission requires a clean checkout at the remote default branch tip.
Before proposing and again before confirmation, the runtime reads GitHub Actions
push workflow results for that exact commit. Every reported workflow's latest run
must have completed successfully. Missing, pending, cancelled, skipped, failed, or
unreadable results block before tagging, and the confirmation token stays available
while admission is blocked. Release builds for another commit do not satisfy this gate.

`skill-bill operation <update-check|release|unit-test-value-check|feature-guard|feature-guard-cleanup|pr-review-fix|verify>` runs one runtime operation with no feature-task workflow. `release` confirms in two invocations. The first stores the proposed version and changelog and exits `awaiting_confirmation` with a token. `confirm:<token>` then tags and pushes exactly the stored proposal, once. `unit-test-value-check` is a read-only report over the current changes or `scope:` and needs no confirmation. `feature-guard` and `feature-guard-cleanup` confirm the same way as `release`. Their first invocation changes no file and stores a plan anchored on HEAD and the current branch. Only `confirm:<token>` edits. After confirm, cleanup runs the `validation` definition. `pr-review-fix [mode:local|remote] [<pr>]` defaults to local mode, which proposes a per-thread matrix over the current branch's open local diff comment threads. Confirmation fixes selected threads, runs `validation`, replies with outcome-driven state, and commits without pushing. It refuses protected branches and dirty worktrees. Remote mode retains today's GitHub flow over PR review threads and pushes only with `push:on`; `push:` and `replies:` apply only to remote mode, and a PR reference requires `mode:remote`. `verify <intake> [target:<pr|branch|base..head>] [mode:inline|delegated] [qa:on|off]` is report-only. Its intake is free text (a Linear issue key or URL, or the requirements themselves) or `spec:<path>`, and an omitted target verifies HEAD against `origin/HEAD`. It parks a verify workflow (stored `workflow_name` `bill-feature-verify`) at the extracted criteria and exits `awaiting_confirmation`, with the workflow id as the token. `confirm:<token>` runs the audits, the review, and the verdict on that workflow. `qa:` defaults to `off` and only `operation:verify` accepts it; a duplicate, conflicting, or unknown value is a usage error. The proposal names the selection, the workflow stores it as `input_context.qa_selection`, and a confirm that asks for a different value is refused. `off` writes a `disabled_policy` QA skip report without probing a device or launching an agent. `on` runs report-only QA after the completeness audit in the temporary QA checkout at the target head, removes the QA checkout on every exit (a failed removal is recorded with manual cleanup guidance), and never changes the operator's checkout, HEAD, or target. QA problems never fail verify: a QA checkout that cannot be created settles `skipped` with `isolation_unavailable`, and an unreadable report or a stopped QA agent settles `incomplete`. An unqualified `APPROVE` against open QA findings is recorded as `APPROVE WITH FIXES` with a warning, and a skipped or incomplete QA run, and every QA warning, is reported as untested coverage. The report and `feature_verify_finished` telemetry carry `qa_outcome` (`not_selected`, `attempted`, `passed`, `findings`, `skipped`, or `incomplete`) and `qa_reason`. The MCP `feature_verify_workflow_*` tools read and update the same `input_context.qa_selection`. A desktop verify start passes its QA and review-mode choices as separate `qa:` and `mode:` arguments, never inside the intake, and adds the settings QA device when QA is on. Changing either choice on the proposal card starts a replacement proposal, because confirm cannot change them.

The retired skills (`bill-feature`, `bill-feature-spec`, `bill-code-review`, `bill-code-check`, `bill-feature-verify`, `bill-monitor`, and the rest) are not installed; an install over an old home removes their links and copies. Telemetry `skill` values, the verify `workflow_name`, the workflow skill label, and the quality-check `routed_skill` (`bill-code-check`) keep their retired names because remote telemetry and stored rows key on them. Never tell an agent or operator to invoke a retired skill.

Bundled skills and packs are defaults, not the framework boundary. Teams may replace them while retaining governed source shape, generated-output boundaries, manifests, install staging, validators, dynamic discovery, and loud-fail.

## QA reason tokens

A skipped or incomplete QA report carries one token from this vocabulary in `qa_reason`. The QA agent names it on a `Reason: <token>` line directly after the verdict line, and the runtime keeps only recognized tokens. Verify adds `partial_coverage` for a `passed` or `findings_open` run that left an in-scope target skipped or incomplete, or that drew a coverage, assertion or isolation warning.

| Token | Meaning |
| --- | --- |
| `capability_absent` | The repository declares no QA capability. |
| `unsupported_target` | No pack QA target matches this repository. |
| `disabled_policy` | QA is switched off for this run. |
| `isolation_unavailable` | The runtime could not create the isolated QA checkout. |
| `unauthorized_target` | The target device or environment is not authorized for QA. |
| `offline_target` | The target device or environment is unreachable. |
| `device_contended` | Every authorized device is leased by another run. |
| `empty_scope` | No criterion applies to a target, no declared target has an applicable criterion, or the tracker issue could not be read because no tracker tool was available. QA never skips because a change looks absent or the tree matches the base branch. |
| `production_only` | Only a production environment exists, so no flow ran. |
| `docker_unavailable` | The only viable environment needed Docker, and Docker or its daemon was unavailable. |
| `environment_unavailable` | No usable isolated environment existed at all. |
| `isolation_rejected` | The effective configuration pointed at a production or shared resource, so nothing was mutated. |
| `setup_budget_exceeded` | Environment setup overran its 15-minute wall-time cap. |
| `browser_unavailable` | The web target needed a browser driver and none was usable. |
| `partial_coverage` | Verify only: the verdict stands, but an in-scope target was skipped or incomplete, or the report lacked coverage, assertion or isolation evidence. |

## Runtime Agent Behavior

When a Codex worker ends a response with `max_output_tokens`, the launcher resumes
that exact session and continues the same phase. There is no continuation-count
cap. The original phase deadline, cancellation, launch authorization and review
evidence lifetime still apply across those responses. Partial output does not
complete a phase or consume a repair attempt. A missing or changed session identity
and other provider failures retain the normal blocked-run behavior.

Agent-specific behavior uses injectable strategies on `AgentRunProcessRequest`, not identity branching in the process runner: `progressProbe`, `declaredProgressProbe`, `activityProbe`, `progressEmitter`, `idlePolicy` (`HEARTBEAT_EXTENDED` | `DB_PROGRESS_ONLY`). `ProcessWaitLoop` calls strategies only; new agents add a strategy constant. Crash reconciliation: `FeatureTaskRuntimeWorkerSupervisor` self-heals expired-lease rows to resumable at startup.

Goal planning forwards observed agent activity to the parent goal. Status reads its
active planning phase instead of the preceding child's progress. A verified live
planning owner can clear a stale blocked label; operator pauses still apply.

Status heartbeats report that a child process remains active. Workflow progress
labels and observed file activity report separate facts; process activity alone
does not establish source progress. A passing local check reports only a local
result. Remote CI remains pending until the monitor observes its result. While
checks remain pending, the existing monitor progress event can refresh status
every 30 seconds. That update reports waiting activity; it does not change the
CI verdict, deadline, or repair attempt count.

Each goal runs in `<state>/goals/<repo>/<goal>/checkout`. Standalone phases use the
caller's checkout. A desktop start that names a branch runs in the checkout holding
that branch, or in a sibling worktree the runtime creates; the runtime removes that
worktree with plain `git worktree remove` once the last run holding it completes, and
keeps it, noting why, when removal fails. After recording completion, the runtime removes a clean worktree
and keeps the goal branch. It retains worktrees with `dirty_worktree`,
`untracked_files`, `unpublished_commits`, `publication_unknown`, `unowned_resource`,
or `cleanup_failed`. `goal status` shows the path, reason, detail, and the exact
`git worktree remove --force <path>` command. `goal purge` refuses when a retained
worktree has work. A goal branch checked out in another checkout blocks the goal.

A goal child requires evidence only for its own acceptance criteria and completed
dependencies. Planning must order migration and evidence acquisition before strict
consumer activation, or keep existing entry points compatible until adoption.
Audit, review, finding verification, and validation must not demand work or evidence
explicitly assigned to a later subtask. They trace each alleged gap to its owning
criterion and subtask, and retain deferred work as context without claiming it is
complete. Current regressions still require repair and every required project check
still runs. A child must keep existing callers and persisted data usable; deferral
never waives failures or weakens evidence rules. Final goal assessment requires all
parent outcomes after the subtasks finish.

Every non-skipped goal child runs `audit -> review -> validate -> write_history`, so each subtask runs the full project validation gate and records its own validation result. Goal children no longer run `qa` or `qa_fix`. After every child completes, goal finalization runs `goal_assessment -> build -> qa when enabled and build usable -> validate -> write_history -> commit_push -> pr -> monitor`. The goal-level build is the initial compile-only cycle after assessment; it does not make build mandatory for every standalone phase or child launch. The build-selection rules below still govern standalone and child execution.

The assessment stage uses the same focused repair strategy as audit. Initial discovery runs once. Independent verification admits material source defects at every severity, then the runtime settles one defect, broken interaction scenario, or unrestored regression at a time. A read-only repair plan covers the selected item's remaining defect class and sibling production paths. `goal_assessment_fix` implements that plan on the goal branch and reports `<ref>: fixed: <files and how>` or `<ref>: not fixed: <reason>`. Neither planning nor source repair builds, tests, commits or pushes. Each completed source repair receives a local round commit, then independent source verification checks the next round, including after the third unsuccessful repair. There is no full validation between repairs. A repair claim or changed file alone never accepts an item. Accepted items are not re-audited and discovery does not repeat. Each item has three unsuccessful repair outcomes; failed and no-change repairs count. Exhaustion records `persistent_finding` and blocks before goal quality or publication. Missing assessors, incomplete verifier verdicts, unreadable records, and unrecorded repair commits settle `assessment_unavailable` and also block. The record's `unavailable_reason`, the block message, and `skill-bill goal status` name the cause. `converged` requires every material source item to be independently settled. Test-value notes remain report-only. A normal reload preserves the queue, accepted dispositions, repair plans, prior outcomes, and budgets. `skill-bill goal resume <KEY>` explicitly reopens a blocked assessment and grants a fresh allowance after exhaustion while retaining earlier outcomes and evidence. A recorded accepted exit skips assessment on resume. Goals without changes record `skipped_no_changes`. Superseded assessment records are quarantined with diagnostic evidence and regenerated through fresh discovery under the current accounting contract. Historical assessment validation values remain facts about their original round and do not prove current final quality. A goal resume retains accepted repairs and consumed repair budgets, then resumes any missing current-source build, QA, or validation evidence from the retained quality execution. Historical quality outcomes are adopted only through the explicit compatibility rules; missing or conflicting identities remain ambiguous, and an unknown in-flight outcome keeps the existing recovery path described below.

Goal QA runs only when enabled and supported after a usable goal build. If build remains failed after its repair turns, QA is skipped with the existing warning and the goal continues to validation on a non-paused run. Finalization still requires current-source validation when QA is disabled, skipped, unsupported, already settled, or unchanged; a resume reuses prior validation only when its retained source identity still matches. If a validation repair changes the tree tested by QA, the PR body retains the stale-QA warning. The QA report, repair loop, and build selection behavior follow.

The runtime reads the verdict from the QA report's first verdict word and never rewrites it: a `passed` report that lists findings keeps `passed` with a warning and opens no repair, and a report naming no verdict settles `incomplete`. QA findings enter `qa_fix -> build -> qa`, with three repair attempts counted across goal relaunches. `build` is the runtime-owned compile-only gate with its own repair turns, so QA re-tests only a fix that compiles; `qa_fix` itself never builds, tests, or validates. When build still fails after its repair turns, QA does not re-test: the goal records `qa_fix left the app without a passing build, so QA did not re-test the fix` with the build gate's reason in the QA outcome and the PR body, then continues to validation and `commit_push`. `qa_fix` gets each finding's steps, expected and observed behavior, and evidence, edits the goal branch working tree, and goal finalization commits its edits with the goal. `qa_fix` must fix every finding in source and report each as `<finding-id>: fixed: <files and how>`; a `qa_fix` that changes no file, or marks a finding `not changed`, is sent back for those findings. Final validation runs even when the QA loop made no source changes. If a validation repair changes the tree tested by QA, the PR body retains the stale-QA warning. QA never blocks the goal. Findings left after the third attempt are recorded as `findings` with `findings left open: <ids>` and the run continues to `commit_push`. The goal QA outcome keeps every finding QA reported during the loop, marked `fixed` when a later QA run no longer saw it and `not_fixed` otherwise; the desktop goal activity panel lists both, `skill-bill goal status` prints `qa_findings: fixed=<n> not_fixed=<m>` with the open ids, and the PR body names the not-fixed findings for the operator to review, and a goal QA run that cannot settle prints a `Warning:` line and reaches the PR body as a warning. QA warnings recorded with the report, including an unavailable QA capability and a HEAD, tree, or worktree status change made during QA, are listed in the QA output's `qa_warnings` and appended to the QA outcome in the PR body. The full run selects QA with the dispatcher token `qa:auto|off`, which the runtime receives as `--qa <auto|off>` or the `SKILL_BILL_QA_SELECTION` environment variable and persists in the goal continuation artifact. Omission resolves to `auto`. The goal records the selection on first run, and a resume that requests a different one is refused. `off` settles goal QA as skipped with `disabled_policy` and never probes a device or launches a QA agent. When the routed platform pack declares no `qa_capability`, goal finalization omits the `goal-qa` skeleton entirely. It records no QA phase or report, and the desktop progress pipeline omits the `qa` and `qa_fix` pills. An explicit standalone `phase qa` still reports an `unsupported_target` skip. An unknown, duplicate, or conflicting value (flag and environment disagreeing) is a usage error. Goals already in flight keep each recorded child's step list, so a child recorded on goal-child revision 3 still runs child-level `qa` and `qa_fix`. On such a child a tree change after QA sends `write_history` back to audit and QA up to three times; after that the child continues to history and commit with the warning `QA results are stale: code changed after the last QA pass`, which reaches the goal PR body. An in-flight goal with no recorded selection takes the first child's. Goal QA is skipped when every child ran and recorded child-level QA, or when a goal-level QA outcome already passed or skipped on the current tree. Otherwise it runs once. Build is the compile-only quality gate. It runs only when a launch explicitly selects it with `--quality-gate-selection build` (or the matching environment variable), or when a resumed child already recorded build; the runtime never switches a recorded child to a different gate. When build is selected and the dominant platform pack declares `validation_gate.build_command`, build is one agent session that runs only that pack's `build_command` (Kotlin: `./gradlew compileKotlin`), reads that output, fixes every finding in that session, then runs `cache_bypassing_build_command` once to confirm. Build is compile/buildability proof only: no suite tests, no full check, no substitute agent-run gate. Do not run `collect_all_full_gate_command`, `./gradlew check`, `check --continue`, `skill-bill validate`, `skill-bill phase validation`, or any other repo-root checklist. The runtime does not start another agent for repair turns. It may still run one cache-bypassing verify after the agent signals complete; remaining findings persist `findings_open` and block, and an operator resume starts one new build session. Do not rerun the pack build command after each individual finding, and do not launch delegated subagents. Targeted compile tasks are allowed while repairing when they are part of that same pack gate. Default standalone runs skip build (`review -> validate`); only a child recorded with build uses `review -> build -> write_history`. `implement` and `implement_fix` never run `./gradlew check` or any other full check; validate owns the full gate on every subtask.

## Commit Structure (feature-task / goal subtasks)

Decomposed goal runs use `same_branch_commit_per_subtask`: each completed subtask leaves exactly one commit on the feature branch, not a chain of checkpoint commits in branch history.

- Before review, the runtime creates the active subtask commit or amends its proven owned HEAD, records the exact reviewed target/tree identities, and launches review only after durable identity persistence. A message-only amend with the same tree may carry approval forward. `write_history` and `commit_push` never reopen earlier phases. After the bounded `review_fix` round, `commit_push` does not launch an agent. Commit/push runs no validation, CI discovery, readiness check, or agent. The preceding quality-gate phase owns local checks. PR publication requires no additional validation-evidence gate. Before that commit, the runtime marks the decomposition manifest complete so the committed file carries the completed status. The runtime then stages every non-runtime-private dirty path as this subtask's work, including files written after implement or by another process. Feature-spec files are included only when they are not gitignored. It commits with a subject from the issue key and subtask name, pushes, and records `commit_sha` into workflow state. The git-tracked manifest keeps `commit_sha` null. Extra dirty content does not block and does not re-enter audit or review.
- After the subtask commits, the goal assessment stage adds `goal_assessment` round commits, one per fix round that changed files, each with the subject `<issue_key>: goal assessment round <n>`. The runtime stages every non-runtime-private dirty path for that commit and records its `commit_sha` in the assessment record. Assessment commits are local; they are pushed with the goal by `commit_push`.
- Checkpoint history lives under `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`. Those refs preserve pre-amend commits the branch no longer names; they are not reachable through `git log` on the branch without an explicit ref argument.
- Pruning deletes a subtask's checkpoint refs only after that subtask's commit is pushed and its manifest entry records a non-blank `commit_sha`. Pruning is idempotent; a hard manifest reset prunes the refs of the subtasks it reset. Blocked or abandoned subtasks keep their refs for recovery.

## Goal CI monitoring

Monitoring counts distinct published repair commits after CI fails on those
commits. A completed repair that publishes no change while CI still fails also
consumes one of the three attempts and returns to repair instead of blocking
immediately. These failures use the monitor attempt identity, so separate no-change
repairs count separately even on the same commit. Resume derives them from the
existing completed repair, publication and monitor evidence without rewriting
records. Resume reconstructs qualified failures from retained runtime producer,
completion, publication, and Git evidence. Repeated observations of a published
repair commit keep one failure. A later repair replacing the latest phase record does not erase a
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
Setup reuse does not skip runnable checks: run all runnable required local checks for every repair
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
a repository fix and passing runnable required local checks. If investigation cannot
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
conflict, even when no checks are listed. The repair agent runs the project's local checks, as validation does, and settles completed only when they pass; the runtime commits and pushes only a completed repair. Reported checks must all pass, be skipped, or be cancelled with none pending.
Goal monitoring completes with a no-checks-reported result only after two
successful empty check observations at least two minutes apart and a fresh lookup
confirms the same nonconflicting PR number and nonblank head. A failed or
unavailable identity or check read is unknown and cannot confirm the result. A
changed PR or head starts a new window. Checks that appear during the window use
the normal pending, failure, and success paths. Conditional or delayed checks may
appear after completion. Inspect the PR again if those checks matter. A missing pull request, wrong
branch, or exhausted repair loop stops the goal at the failing finalization step. A failed GitHub identity or CI
checks read is recorded as unknown for that round and retried; a failed read cannot establish that no checks were reported. A later goal run reuses completed subtasks and the existing PR
and monitors again. Accepted no-change goals create no PR and skip monitoring.

Finishing the subtasks does not complete the goal. PR publication and monitoring
must finish before the runtime records goal completion or the IDE reports "done".
Status also honors persisted completed goal rows from older runtimes that did not
write the newer completion control marker. Completed subtasks alone do not prove
that monitoring is running. Running status requires a verified live process owner.
A merged PR completes monitoring regardless of failing or pending checks. The
monitor checks the PR's state on every poll so a merge during CI ends the wait.
When every subtask is terminal, resume skips the planning sweep and continues
finalization without reading archived planning specs. It restores the existing goal
branch before assessment and quality work. An assessment discovery round with no
accepted assessor output can bind to that restored head; completed assessment
results retain their original head binding.
Goal and IDE status read workflow state from the database without consulting spec
projections. Issue-key spec lookup uses bundles directly under the project store's
`specs/` and, for in-flight legacy goals, directly under `.feature-specs/`. The
store is flat: a finished bundle stays in place and its manifest marks it complete.
Bundles under a legacy `.feature-specs/done/` are un-imported archives and never
supply intake or an existing planning bundle, even for the same issue key;
`skill-bill project import-specs` copies them to the top level of the store.
Archived keys still count when allocating the next local issue number.
A resumed goal recognizes its merged pull request before committing, pushing, or
trying to publish another pull request, including after deletion of the remote
feature branch. Before returning a failed check or conflict, monitoring refreshes
the pull request state so an observed merge completes the run. Repair-limit
reports label the retained failure as the last observation.
An empty check report starts the two-observation window only for a known current
PR head. A prior check for that PR and head prevents a later empty response from
being treated as no checks. Failed reads and changed heads do not confirm the
window. A no-check result records that no checks were reported during the
observation window, not that CI is unconfigured or that no later check can run.

### Recovering an existing monitor workflow

Before resuming a blocked, active, or legacy monitor workflow, inspect its child
and parent status. Match the child and parent IDs, repository, issue key, PR
number, and current head. Read the current owner and lease, then refresh the PR
and check evidence. Old status output, saved check data, or an earlier process
observation is not proof of present ownership.

Use `skill-bill feature-task status <child-workflow-id>` and
`skill-bill goal status <issue-key>` to inspect the child and parent. Use
`skill-bill goal watch <issue-key> --verbose` when live process and incident
evidence are needed. If normal admission allows a resume, run
`skill-bill goal <issue-key>`. For a supported incident action, preview it with
`skill-bill work recover <work-id> --incident <token> --preview`, then apply the
same action with the preview's revision using
`skill-bill work recover <work-id> --incident <token> --revision <revision>`.
Do not take over a live or unverifiable owner, edit database rows, or mark the
workflow complete outside the runtime. Keep completed subtasks and the existing
PR. If identity, ownership, or the supported recovery action is ambiguous, stop
and report the evidence gap.

The historical IDs `wftr-20261104-162307-wmha` and
`wftr-20261104-162305-ggjh`, issue `TSW-1`, and branch
`feat/TSW-1-replace-landing-page-design` are leads from an earlier investigation.
Recheck every identity and the live lease before acting. They do not establish
that a workflow belongs to the current issue or authorize mutation.

A long-running child's phase ledger retains recent entries and the retry and
settlement entries needed to preserve repair budgets. Those older entries can
leave sequence gaps before the recent contiguous entries. Resume validates this
retention shape and checks surviving planning-import entries alongside the durable
planning provenance and completed phase records. Normal ledger retention does not
require replan or discard the child's execution progress.

## Edited subtask specs on resume

A full goal launch checks unfinished subtask specs against their saved planning hashes
before admitting an existing child. When a readable spec has changed and its saved
plan still satisfies the durable contract, the runtime applies scoped replan with
shared preplan refresh and continues through planning. It records the repair with
the subtask ID and both hashes. Completed and skipped subtasks retain their planning
records and commits. The existing scoped-replan liveness and digest checks still
refuse unsafe changes. Missing specs and invalid planning records stay with their
existing recovery paths.

## Planning implementability

Bundle planning divides broad criteria into smaller, independently verifiable pieces of the same
production work. Prefer five focused criteria over fewer broad ones when that split is coherent.
The split changes acceptance granularity while preserving requirements and the subtask boundary.
Requirements and execution boundaries determine scope before criterion counts. Planning never expands
scope or moves work between subtasks merely to reach five criteria. Fewer criteria are appropriate
when further splitting would duplicate outcomes, separate tightly
coupled work, or invent scope. Five is a preference within the existing one-to-five limit, not a
minimum or a target number of subtasks. Test obligations remain separate from acceptance criteria.

Preplan checks prerequisite capabilities against authoritative contracts and owning
source. Bundle planning reconciles requirements across subtasks, and each subtask
planner checks its final tasks against that evidence before declaring them ready.
The shared runtime instruction is `slot/plan/planning-feasibility.md` in the engine
resources; preplan, bundle planning and subtask planning all receive its body.

Planning keeps a concise component inventory in its existing digest and implementation
plan. It distinguishes existing behavior, authorized local components to build or extend,
and unavailable external capabilities. Every missing local component has an implementation
owner, consuming criteria and dependency order. Criterion sizing includes that work,
caller wiring, contracts and required migrations, even when their files do not exist yet.
Shared prerequisites have one implementation owner and precede dependent behavior;
decomposition preserves usable intermediate states. Missing assigned code is implementation
work and does not by itself justify a block. Planners reconcile this inventory against
the final tasks before declaring them ready. This adds no output schema or runtime gate.

The digest is the starting evidence, not a prohibition on resolving a critical gap.
Planning may use targeted read-only lookups for a missing or contradictory fact that
determines implementability. It does not repeat discovery or widen file-write
ownership. A missing backend capability cannot become an assumption for implement
to confirm. Local contract changes are valid when their implementations, consumers
and dependencies are planned. Simulator fixtures do not establish production API
support, and an unavailable preferred tool need not block another authorized source.

Plans retain a concise criterion-to-evidence and change trace. They reconcile timing,
failure classification, status precedence and phase authority across tasks and QA
expectations. Unsupported requirements and unresolved product conflicts are reported
for a scope decision during planning, before implementation. Operator decisions
already supplied apply without another approval request. Authorized deferrals remain
explicitly unclaimed and do not repeatedly block the supported scope. Semantic
feasibility remains an evidence-based agent check, not a claim that compilation or
QA ran or a schema validator proved the behavior possible.

## Acceptance-audit repair planning

The initial `audit` reads every authored acceptance criterion. It records findings
as ordinary prose and later rounds audit the criterion selected by committed
accounting. The runtime keeps authored text and aliases with that accounting. It
does not require headings, field labels, IDs written by the agent, coverage
markers, or a strict adoption receipt in an audit or repair plan.

Audit and repair apply the selected subtask's authored scope, non-goals and explicit
ownership boundaries before expanding a defect class. Each gap must cite an obligation
of the current subtask. Work explicitly assigned to a later subtask cannot keep the
current criterion open or become a required repair item. Missing engine acquisition
or caller integration alone is not a gap when that integration is deferred. Current
contract, pure domain enforcement, serialization and retained-evidence obligations
still apply. File ownership or worker prose alone cannot authorize a deferral.
Audit and repair reconcile retained findings and saved plans against these boundaries,
name excluded deferred work and its declared owner, and retain valid current gaps.

Audit counts only material gaps. A gap is material when a realistic user, operator
or runtime action reaches it in normal use and it causes a visible failure, wrong
behavior, or loss of data someone relies on. Absolute criterion wording ("never",
"every") is read as its practical intent. Paths that need contrived input,
representation changes that leave effective behavior unchanged, races that need
precisely timed concurrent writers, and failures that end in a clear error without
data loss are not gaps. They never keep a criterion open and are mentioned at most
as non-blocking notes.

`audit_plan_fix` plans the current criterion's open gaps. The runtime runs criteria
one at a time in catalog order through plan, repair and verify. It plans once for a
criterion, then plans again only after escalation. Reopening a closed criterion or
adding discovery gaps starts a new pass and requires a fresh plan. Operator retry
keeps the current plan. If a plan finishes but its state update does not commit, the
runtime runs that plan again.

Each open gap has an id such as `AC-002.G1`. Repair receives the current criterion,
its open gaps, its plan and the latest verify result. Verify is read-only. It reports
each open gap as closed or open, and may add a gap only for a file the repair changed.
Other observations become late findings for code review and do not keep the
criterion open. A repair that reports remaining work goes to verify.

An attempt is charged only when the number of open gaps did not decrease. Each
criterion has three charged attempts. After two charged attempts, repairs use the
provider's reasoning tier. If no reasoning tier is configured, the runtime keeps the
current model and records a diagnostic. The third charge blocks with a reason that
names the criterion, its open gaps and the attempts. An operator retry resets only
that criterion. Legitimate worker blocks remain valid outcomes. Nothing builds or
tests between repair and verify. Older workflows resume through one discovery audit.

Finding gaps, planning and routing do not consume repair budget. Closing one
criterion and selecting the next also consumes no budget. Phase execution counts
identify launches; they are not the per-criterion repair budget. Source acceptance
does not say that tests, build or other project-quality work ran. Those obligations
stay in their existing review, build and validation phases.

Only the initial discovery audits the whole authored set. After it, the runtime
settles one criterion at a time. Older workflows resume through one discovery audit.
Status and detail readers report committed facts only. Older aggregate attempts
without criterion attribution remain unavailable, not zero. Starting prospective
per-criterion accounting for such a workflow requires a separate explicit operator
decision. Audit, repair planning, source repair and verify do not run builds or tests.

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

## Bundle feasibility before implementation

After `plan_subtasks` writes the implementation details, a fresh review checks the whole bundle against
its dependency order. Every criterion must describe a passing state achievable with that subtask and
its declared earlier dependencies. Scope and non-goals cannot forbid required wiring, migration,
evidence capture, or validation repairs. Strict consumers stay compatible or inactive until their
migration and evidence producers are available.

The runtime requires approval of every criterion and binds it to the parent, immutable manifest fields,
and full subtask text. Missing, partial, malformed, or stale approval cannot release implementation.
A contradiction returns the pending specs to a planning repair session, then a fresh read-only review.
This uses the existing bounded planning correction budget. Repair preserves parent bytes, manifest,
completed and skipped specs, execution identities, commits, and history. An unrepairable conflict blocks
with its clauses and missing prerequisite. Old completed plans remain readable and reopen planning for
review before implementation; their unit plans and completed work remain intact. Execution status
updates alone do not invalidate approval.

## Interrupted legacy planning adoption

Goal continuation reopens an ownerless planning workflow when compatibility adoption
left `plan_subtasks` pending but the workflow marked running. It preserves phase
records, accepted unit plans and feasibility history, and rebases the bundle snapshot
before the required fresh feasibility review. Repeating continuation does not add
another adoption or discard completed work. A worker ownership record, an active
feasibility launch, or a running phase still refuses this recovery.
