---
name: sona
description: "Run governed feature work with /sona <intake>, or explicitly requested standalone tasks. Phases: /sona phase:plan <intake> prepares a spec bundle; /sona phase:review reports code findings; /sona phase:validation runs and repairs project checks, lint, and formatting; /sona phase:qa reports QA findings for the repository's declared QA targets (backend endpoints, web journeys, a desktop app, or a device or simulator); /sona phase:pr commits, pushes, opens or updates a PR, and monitors CI; /sona phase:monitor watches CI on an exact open PR or a `branch:<name>` target from an owned temporary worktree and repairs failed checks or PR merge conflicts. Operations: /sona operation:update-check checks for updates; /sona operation:release bump:<patch|minor|major> proposes and publishes a release; /sona operation:unit-test-value-check reviews test value; /sona operation:feature-guard <intake> adds a feature flag; /sona operation:feature-guard-cleanup <intake> removes a stabilized flag; /sona operation:pr-review-fix proposes and applies selected PR feedback fixes; /sona operation:verify <intake> audits confirmed acceptance criteria."
---

# Sona Dispatcher

`sona` routes a full feature run, one phase over the working tree, or one
runtime operation to the `sona` CLI. The full run is the only feature entry
point. The runtime owns preparation, planning, execution, and durable state.
Phase and operation forms run one command and relay its output.

## Operator-only commands

Standalone phases and operations are operator tools. An agent may run
`sona phase` or `sona operation` only when the operator explicitly
requests that standalone phase or operation, through a named form or an
unambiguous standalone task such as running validation. A full feature request,
an issue URL, a workflow briefing, or a missing spec does not authorize either
command.

Agents must not select these commands as workflow steps, prerequisites,
recovery actions, or substitutes for the full run. The runtime owns its internal
phase execution. Runtime-launched workers execute their supplied briefing and
must not start standalone phase or operation commands.

## Update Check

Only the initial, user-facing full-run invocation performs this automatic check.
Call `mcp__sona__update_check` once before full-run intake.
Keep the selected runtime for the whole run, including every subtask and retry.

Runtime-launched phase workers, goal children, retries, and continuations execute
their supplied briefing. They must not call `mcp__sona__update_check`, ask
whether to update, or repeat this dispatcher's intake or confirmation
gate. Reading this skill during an active phase does not start a new invocation.
Phase and operation forms skip the automatic check. An explicit
`operation:update-check` still runs the requested check through its operation route.

When the tool returns `status: "update_available"`:

- Show the installed version (`installed_version`) and latest version
  (`latest_version`).
- Ask whether to update or continue with the current version.
- If the user chooses to update, stop and show `recommended_install_command`.
- If the user chooses to continue, proceed to Forms and Routing.

For `up_to_date`, `ahead_of_release`, or `unknown`, proceed to Forms and Routing
without prompting.

## Forms and Routing

Pick the route from the operator's explicit request. Without a standalone phase
or operation request, use the full-run route. `phase:<name>` may come before or
after the intake. Forwarded `key:value` tokens follow the intake unchanged.

An existing spec file or bundle directory is full-run intake. Planning-only
instructions recorded in that spec describe the earlier planning invocation;
they do not turn the current full-run request into `phase:plan`. Only the
operator's current request selects a standalone phase or operation.

| Invocation | Route | Intake |
| --- | --- | --- |
| `/sona <intake>` | full run: Intake, Issue resolution, Launch, Relay | required |
| `/sona <intake> phase:plan` | `sona phase plan <intake> --agent <currently-executing-agent>` | required |
| `/sona [<intake>] phase:review` | `sona phase review [<intake>] [mode:<value>] [target:<value>] --agent <currently-executing-agent>` | optional |
| `/sona [<intake>] phase:validation` | `sona phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/sona <standalone quality check: run checks, lint, format, or quality validation>` | `sona phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/sona [<instructions>] phase:qa` | `sona phase qa [<instructions>] --agent <currently-executing-agent>` | optional |
| `/sona [<intake>] phase:pr` | `sona phase pr [<intake>] --agent <currently-executing-agent>` | optional |
| `/sona [<issue-key\|pr-url\|branch:<name>] phase:monitor` | `sona phase monitor [<intake>] --agent <currently-executing-agent>` | optional |
| `/sona operation:update-check [--include-prereleases] [--format json]` | `sona operation update-check [--include-prereleases] [--format json]` | none |
| `/sona [<instructions>] operation:release bump:<patch\|minor\|major>` | `sona operation release bump:<value> [<instructions>] --agent <currently-executing-agent>` | optional |
| `/sona [<scope>] operation:unit-test-value-check` | `sona operation unit-test-value-check [scope:<value>] --agent <currently-executing-agent>` | optional |
| `/sona <intake> operation:feature-guard` | `sona operation feature-guard <intake> --agent <currently-executing-agent>` | required |
| `/sona <intake> operation:feature-guard-cleanup` | `sona operation feature-guard-cleanup <intake> --agent <currently-executing-agent>` | required |
| `/sona [<pr>] operation:pr-review-fix [mode:local\|remote] [scope:analyze-only] [push:on] [replies:draft]` | `sona operation pr-review-fix [<pr>] [<tokens>] --agent <currently-executing-agent>` | optional |
| `/sona operation:verify <intake> [target:<pr\|branch\|base..head>] [mode:inline\|delegated] [qa:on\|off]` | `sona operation verify <intake> [spec:<value>] [target:<value>] [mode:inline\|delegated] [qa:on\|off] [--qa-device <serial:...\|avd:...>] --agent <currently-executing-agent>` | required |

For `operation:pr-review-fix`, local mode is the default; `push:` and `replies:` require `mode:remote`.

`phase:plan` is a durable, resumable plan: it seeds the parent spec, opens a
plan workflow, stops after the verified spec bundle, and prints the bundle
paths, the plan workflow id, and `sona <KEY>`. Running the full run for
that key later finishes an incomplete plan and then executes the goal. The full
run plans new work through the same plan skeleton (intake, preplan, plan), so a
single feature is a one-subtask bundle and there is no separate single-feature
run. The dispatcher never invokes `phase plan` itself.

If `phase:plan` has no intake, stop and ask for it. For any
other `phase:` name, stop and list the names in this table. `phase:verify` stops
and points to `operation:verify`, because `verify` is an operation. If
`operation:feature-guard` has no intake describing the change to guard, or
`operation:feature-guard-cleanup` has no intake naming the flag, stop and ask for
it. For `operation:unit-test-value-check`, forward a scope the caller gives (a test
file, commit sha, or ref) verbatim as `scope:<value>`; without one, omit `scope:`
and the runtime reviews the current staged and unstaged changes. For
`operation:pr-review-fix`, forward any `mode:` token verbatim. Local is the
default: omit the PR and the runtime fixes the current branch's open local diff
comments. With `mode:remote`, forward a PR first as `#<number>` or its URL;
without one, the runtime uses the current branch's PR. For `operation:update-check`, forward `--include-prereleases` and
`--format json` verbatim when the caller gives them; without them, omit them.

`operation:<name>` translates to `sona operation <name>`, forwarding
`bump:`, `confirm:`, `select:`, `mode:`, `scope:`, `push:`, `replies:`, `spec:`,
`target:`, and `qa:` tokens verbatim and any other text as operator
instructions. The runtime rejects an unknown operation name, a missing bump, a
missing guard intake, a `push:` or `replies:` token outside
`operation:pr-review-fix` or with `mode:local`, or a `spec:`, `target:`, or `qa:` token outside `operation:verify`, or a
`mode:` token outside `operation:verify` and `operation:pr-review-fix`, and a
duplicate, conflicting, or unknown `qa:` value; relay its usage error. The rule for
`mode:` or `target:` outside `phase:review` and `operation:verify` has one
exception: `operation:pr-review-fix` accepts `mode:local|remote`. For `operation:verify`, forward the intake
verbatim: a Linear issue key or URL, the requirements as raw text, or a
`spec:<path>` token. If it has no intake, stop and ask for it. Forward
`target:`, `mode:`, and `qa:` verbatim; without `target:`, the runtime verifies
HEAD against `origin/HEAD`, without `mode:`, it reviews inline, and without
`qa:`, QA is `off`. `qa:on` adds QA in a temporary QA checkout at the target
head after the completeness audit. With `qa:on`, forward an operator-named
device as `--qa-device serial:<serial>` or `--qa-device avd:<name>`.

## Token Forwarding

The full run accepts at most one `code-review:inline|auto`, forwarded verbatim as
`--code-review-mode <value>`, and zero or more ordered `agent-addon:<slug>`
tokens, each forwarded as `--agent-addon <slug>` in the given order. Omitted
values remain omitted.

The full run accepts one `qa:auto|off` token and forwards it as `--qa <value>`.
Omission selects `auto`. In the full run, QA runs once per goal, after all
subtasks and before the PR (skipped when `qa:off`). QA findings enter `qa_fix`,
which must fix every finding in source, then the compile-only build gate, then QA again, for up to three attempts across
relaunches; full validation runs once after QA settles when the repairs changed
code. QA never blocks the goal: findings left after the third attempt, a fix the
build gate cannot make compile, and any QA run that cannot settle are reported as
warnings in the run output and the PR body, and the goal continues to
`commit_push`. An unavailable QA capability and a repository change
made during QA are reported the same way. A subtask recorded before QA moved to
the goal re-runs QA after a tree change at most three times, then continues with
a stale-QA warning. A `qa:` token on a form that does not accept it is a
usage error.

`phase:review` forwards `mode:inline|delegated` and
`target:pr|staged|unstaged|HEAD|last|uncommitted|<sha>` verbatim as `key:value`
tokens. Without `mode:`, the runtime reviews inline.

The dispatcher never resolves a review mode, a target, or an add-on catalogue, and
never constructs JSON; the runtime selects.

Stop without running any CLI command when:

- the caller passes `parallel-review:<agent>`: name the removed dual-agent
  parallel review capability.
- the caller passes `phase:` together with `operation:`: report a usage error.
- a token reaches a form that does not accept it (`code-review:`, `qa:`, or
  `agent-addon:`):
  report a usage error naming the token and the form that accepts it. Never drop
  the token or fold it into the intake.
- a `mode:` token appears outside `phase:review`, `operation:verify`, and
  `operation:pr-review-fix`, or a `target:` token appears outside `phase:review`
  and `operation:verify`:
  report a usage error naming the token and the form that accepts it. Never drop
  the token or fold it into the intake.

## Issue resolution

For a tracker link or issue key, check local specs and persisted workflow state
before contacting the tracker. Get the project store with `sona project path`
and search bundles directly under `<store>/specs/`. The store is flat: finished
bundles stay beside the others and their manifests mark them complete. Then
search active bundles directly under the legacy `./.feature-specs/` in the
current repository, including ignored and untracked files, for in-flight legacy
goals, and exclude `./.feature-specs/done/`, which holds un-imported archived
bundles that never supply intake or resume work. Use
`rg --files --hidden --no-ignore <directory>` for the store,
`rg --files --hidden --no-ignore -g '!done/**' <directory>` for the legacy
directory, or inspect each directory directly. `sona project path <issue-key>` prints the matching
bundle directory when one exists. Match the issue key and read the matching
`spec.md`. A filename search that honors ignore rules does not prove that a spec
is absent. If several bundles match, ask for the intended spec path.

A readable local spec launches directly through the full runtime. Do not run
`work status` or `work list` during intake. The runtime looks up only workflows
for the requested issue and repository before deciding whether to resume or
start new work. Unrelated workflows and their contract versions cannot block
this lookup.

When no readable local spec exists, inspect only the requested goal with
`sona goal status <issue-key> --repo-root <repo-root>`. This targeted
intake lookup is permitted before launch. A matching persisted goal takes
precedence over tracker lookup. Report a lookup error for the requested goal;
never replace it with a global workflow scan or treat it as absent work.

When a readable local spec or matching persisted goal exists, launch the full
runtime with the existing spec path or original issue reference. Let the runtime
resume its durable state. Do not fetch replacement requirements, create another
workflow, or edit database rows. If the persisted workflow references a missing
spec, launch with the issue reference so the runtime can report its recovery
requirements. An explicit `phase:plan` reuses a readable local spec but does not
resume a goal or inspect workflow state.

A decomposition manifest already present is executable intake. Launch the full
runtime directly, without checking it through `phase plan` or asking the operator
to run `sona <KEY>`. If this full-run request was mistakenly sent to
`phase plan` and it refused because the manifest exists, correct the dispatch by
launching the full runtime with the same intake and forwarded flags. That
correction needs no new permission and does not apply to an explicit planning-only
request or to other refusal reasons.

Only when neither a local spec nor a matching persisted workflow exists and
the operator supplied a tracker reference without requirements, fetch
the exact referenced issue through its connected tracker. This applies to
Linear, Jira, and any other connected tracker; do not hard-code a provider. A
URL slug or issue key alone does not supply requirements. Readable local specs
need no tracker lookup. Supplied requirements need no tracker lookup either.
For requirements without a key, follow Local key allocation before launch.

If lookup fails, report the reference and returned error, then ask the operator
for the requirements before launch. Do not search substitute sources, retry
automatically, or infer requirements from the URL title. Launch when the
requirements or tracker access are supplied.

On success, retain the returned title, description, acceptance criteria, and
constraints. Pass the original reference together with those resolved
requirements as full-run intake, with the title immediately after the key or
link. Only an explicitly requested `phase:plan` passes them to `phase plan`.
Runtime workers use their supplied briefing and do not repeat this dispatcher's
intake or launch ceremony.

## Intake

New work requires a key and requirements. A connected tracker is optional.
An existing spec key or path needs neither. Preserve the operator's
requirements, acceptance criteria, constraints, affected areas, and non-goals
in the intake. When new requirements have no key, allocate the next local key
by default using Local key allocation. The explicit instruction "next available
key" uses the same rule. Never require a tracker connection or create a tracker
issue to allocate a local key. A supplied key with requirements launches without
tracker lookup. If a supplied tracker reference has no requirements and lookup
supplies none, ask for the requirements.

A design reference such as an HTML file is requirements input, not an existing
spec. Read it and preserve its path and implementation instructions in the
intake after the allocated key and a short title. Only an existing spec bundle
selects the spec-resume route.

An existing spec or matching persisted goal selects or resumes its goal.
New requirements start preparation and durable planning inside the full runtime.
A missing spec is normal for new work; never route it to an individual phase or report it as a launch prerequisite.
Do not run `goal preflight` or assemble the workflow in this session. Launch the
full runtime once with the resolved intake.

## Local key allocation

For new requirements without a key, inspect `<store>/specs/` (the project store
from `sona project path`) and the legacy `./.feature-specs/` in the current
repository, including ignored and untracked files and the legacy archive
`./.feature-specs/done/`. Use `rg --files --hidden --no-ignore <directory>` or inspect
each directory directly. Read bundle directory names, parent `spec.md` identities,
and `decomposition-manifest.yaml` issue keys. Count bundle identities, not keys
mentioned as dependencies or examples inside requirements.

Use an operator-supplied prefix when present. Otherwise use the sole project
prefix found in those local identities, excluding generated `LOCAL-` hash keys.
If several project prefixes exist, ask only which prefix to use. If no project
prefix exists, ask for a local prefix or key, without requiring a tracker.

For the selected prefix, take the highest integer issue number across both
locations and add one, so a key used in either place is never reissued.
Include completed and archived bundles, and treat a decimal suffix such as
`NEWS-144.1` as occupying integer number 144. Do not fill gaps or use timestamps
or hashes. For example, local bundles through `NEWS-151` select `NEWS-152`.
A supplied prefix with no existing keys starts at 1. Recheck the local inventory
immediately before launch and advance if another bundle now occupies the key.

Prepend the allocated key and a short title to the preserved requirements and
launch the full runtime once. The runtime owns spec and workflow creation;
do not write a placeholder spec, run a standalone phase, or mutate workflow
rows to reserve the key. Local allocation does not inspect the workflow
database or contact a tracker. Existing-work resolution keeps its separate
repository-matched database checks.

## Rehydrate

For each entry in `rehydrate_targets`, fetch the listed issue from the connected tracker and
write the returned spec content to the target path. Fetch nothing when the list is empty.

## Launch

Run the full-workflow intake form:

```text
sona <intake> --agent <currently-executing-agent> --no-live-output
```

Pass the intake as one shell-quoted argument with real newlines preserved. The
CLI routes it to the goal runtime. `sona goal <intake>` is the explicit
equivalent. The runtime creates missing specs and the parent workflow before
planning; existing specs resume through the same entry point.

Forward the supplied review and agent add-on flags. Never ask
the user to run the command manually.

The `feature-launch-warning` skill class (`exact: skill-bill`) injects pointers `peak-hours-warner`, `shell-ceremony`, and
`telemetry-contract`, and the three `ceremony_lines` into rendered `## Ceremony`.

## Relay

Await the launched process through the harness completion primitive. Relay its
output verbatim, adding nothing. Do not poll, sleep, tail logs, re-read status,
launch an observer, or compose monitoring, completion, summary, or progress
output. Run goal status only for the targeted intake lookup above or when the user explicitly asks.

An audit-confirmed no-change claim continues through the remaining workflow phases
without an operator approval. Existing undecided no-change pauses resume through
the audit and the same remaining phases.

## Phase Forms

Run a `phase:` form only at the operator's explicit request for that standalone
phase. These forms are operator tools, not agent-selected workflow steps. Skip
full-run Intake, Issue resolution, Rehydrate, and Launch.
`phase:plan` first follows Issue resolution. Run the translated command from
Forms and Routing once and relay its output verbatim,
adding nothing. Do not add checklists, rubrics, or steps from other skills. Never
ask the user to run the command manually.

Implementation and simplification run inside workflows and consume their plan
output. There is no standalone implementation phase.

## Phase Review

`phase:review` runs the report-only `standalone_review` slot through the existing
run loop. It prints the findings register and exits 0 for either valid verdict.
Invalid or incomplete output and execution failures retain available findings,
explain the block, and exit 1. This operator review never edits or commits. Full
feature runs keep the separate `code_review` slot and its verification and repair
steps. The sections from Review arguments through Present the register govern
standalone review arguments and output. Forward the review
target as `target:<value>` and the review mode as `mode:<value>`.

## Review arguments

Recognize at most one `mode:inline|delegated` argument.
Omission means `mode:inline`.
Reject malformed, unknown, duplicate, or conflicting values.

Recognize at most one `target:<value>`:

- `pr` reviews the current pull request against its base.
- `last` or `HEAD` reviews HEAD against its first parent.
- a commit SHA or other git revision reviews that commit against its first parent.
- `uncommitted` reviews staged, unstaged, and untracked work.
- `staged` and `unstaged` keep those narrower packets.

The accepted `target:` values are `HEAD`, `uncommitted`, `pr`, `staged`,
`unstaged`, `last` (maps to HEAD), or a commit, branch, or tag.
An omitted target reviews uncommitted changes when the worktree is dirty and HEAD otherwise.

## Present the register

Display the `phase:review` command's stdout as the review result. It already includes the risk
register with provenance labels and any recorded stage verdicts. Do not rewrite
findings, invent a second merge, or re-run the review in this session.

The `phase:review` command runs the report-only standalone review. A valid report exits 0 for
either verdict. Invalid or incomplete output and execution failures retain
available findings, explain the block, and exit 1. The operator review never
launches repair. Full feature runs retain their separate review, verification,
and repair steps. A `# Review phase blocked` line means the phase stopped before
it finished; report it and exit non-zero.

## Phase QA

`phase:qa` runs the report-only `qa` slot for the repository's declared QA
targets, such as backend endpoints, web journeys, a desktop app, or a device or simulator. Pack
manifests declare QA support through `qa_capability`, and one `phase:qa` serves
every declared target. The runtime resolves the targets from the installed packs,
not the dispatcher: each routed pack's `qa_capability` names its guidance, and a
repository with no declaring pack completes as a skipped `unsupported_target`
run without launching an agent. The intake is the QA requirements: a key that
resolves to a local spec supplies its acceptance criteria, and a tracker URL or
free text becomes the criterion the QA agent reads and tests. With no intake, QA
tests what the current branch changes against its base. QA tests the checked-out
tree as it is. It never inspects history, diffs, or source to decide whether a
change is present; missing behavior is a finding. The QA agent maps criteria to
declared target kinds before probing tools. A kind with no applicable criterion is
reported as out of scope. An in-scope flow is untested when its required tools
are unavailable. Forward the
intake verbatim; the dispatcher does not fetch the issue for this phase. The QA agent drives the app, service, browser,
device, or simulator the guidance names and writes a prose `report.md` and its evidence under
`qa/<repo-key>/<invocation>/` in the sona state directory
(`SKILL_BILL_STATE_DIR` when set), never in the working tree or `.git/`. It runs on a
test account, so every in-app action is allowed, and it picks a Local or staging
environment, never production; a production-only app settles `skipped` and
names the missing environment. It builds and tests in a temporary worktree of the
tree under test, created by the runtime under the invocation directory with the
repository's ignored local configuration files. When a criterion needs a state
the environment does not produce on its own, QA forces it, for example with an
injected fault or a network cut, and leaves a flow unexercised only after a
forced attempt failed. The runtime
deletes that worktree afterwards; QA never edits the working tree, commits,
pushes, or opens a PR. A standalone QA agent reports live progress with `sona qa plan`,
`sona qa step`, and `sona qa target`. These commands exit 0 for `recorded`, `rejected`,
and `failed`, and they cannot change the verdict. A usage error exits non-zero, and QA continues.
QA never blocks and always exits 0. The runtime reads the report with
no schema check: the first line naming `passed`, `findings_open`, `incomplete`, or
`skipped` is the verdict, and a report with none settles `incomplete`. Each
`### <finding-id> <title>` section is a finding. The runtime never rewrites the
stated verdict. A missing `report.md` or a stopped QA agent settles `incomplete`;
a run that could not start or whose worktree cannot be created settles
`skipped`. A change to the working tree made during QA is kept and named in a
warning. Each case prints a `Warning:` line with the
verdict. Relay the printed verdict, warnings, report text, and cleanup note as a
phase form.

## Phase PR

`phase:pr` composes `commit_push`, `pr`, and `monitor`. The runtime refuses a
detached, protected, or base branch before staging. It commits staged, unstaged, and
untracked changes, excluding ignored and runtime-private files, then pushes. The PR
step creates or updates the branch's open PR, and monitor then watches its CI as in
Phase Monitor. Specs are no longer committed, so the PR description carries a short
spec summary: the intended outcome and the acceptance criteria titles from the
bundle's parent spec. A clean retry reuses the existing commit. The phase creates no
workflow row, branch, or checkpoint commit.

## Phase Monitor

`phase:monitor` watches CI on one exact open pull request or one named branch. Its intake is a single
`https://<host>/<owner>/<repo>/pull/<number>` URL, `branch:<name>`, a single issue key, or nothing. A
URL names that PR. `branch:<name>` watches that repository branch and follows its tip, naming each
commit it adopts. An issue key selects the one PR that mentions it in its title, body, or head
branch, preferring open PRs; when several open PRs match, the run refuses and names them. With
`branch:<name>`, exactly one open pull request on that branch monitors that PR; several open pull
requests refuse and name them; when no open pull request exists and the remote branch exists, that
branch is watched, including when only merged or closed pull request history remains; a missing remote
branch refuses. With no intake, exactly one open pull request headed by the checked-out branch
monitors that PR; several such pull requests refuse; when the all-state listing has never had a pull
request from the checked-out branch and that branch exists, the checkout is watched; when merged or
closed history still lists that branch with no open pull request, or the checked-out branch is
missing, the run completes with nothing to monitor. A protected branch, or a branch the caller cannot push, is
report-only, including the default branch when it is protected: CI failures block with the check links and no repair runs. Branch targets never receive
merge-conflict repair. A branch with no reported CI blocks after one hour. Repositories whose
workflows trigger only on `pull_request` report no CI for a branch with no open pull request. A tip
that moves during a repair blocks with the work preserved rather than following the new tip.
A malformed or multi-token intake, an unwritable head, or incomplete target data refuse the run and
name the candidates or the defect. Resolution never checks the target out in the caller checkout, and
the caller's branch, index, and uncommitted work stay untouched, so monitors for different pull
requests or branches that share a head branch name run concurrently without colliding. The runtime
fetches the pinned head and base and creates an owned temporary worktree and branch under the
`<state dir>/standalone-monitor/<repo-key>/`, outside `.git/`, because agents refuse edits under `.git/`. All repair work runs in that worktree. The
phase starts by watching CI and publishes nothing unless a repair is needed. It does not commit or
push pending caller changes. A repair that settles completed is committed and pushed to the target's
head repository and branch only if the remote head is still the last acknowledged commit, never to
the base repository. A teammate push, a moved head, or a lost ownership fence blocks publication and
keeps the repair. A merged PR completes without work. A closed, unmerged PR blocks. These early
outcomes create no worktree. When every check passes, is skipped, or is cancelled, the phase completes. It also completes when the PR is merged, regardless
of failing or pending checks. When a check fails, `monitor_fix` starts repair on that poll even
while other checks are pending. This initial failure starts repair and does not
count against the failed repair budget. When the target is a pull request, a merge conflict starts merge repair on
that poll, even when no checks are reported: the fix merges the base branch and
resolves every conflict without spending a CI repair attempt. A merge repair
fetches and merges the base tip current at that moment. A base that moves during
a repair does not block it; when the published merge still conflicts with the
newer base, the next poll starts another merge repair. For failing checks,
the repair agent reads each failing job's CI logs, identifies the failed command or assertion, and traces the cause
through the repository before editing. A passing local rerun does not resolve
the CI failure. Intermittent failures require investigation of timing,
concurrency, isolation, or environment differences as the evidence warrants.
Completion requires a repository fix and passing local project checks.
If investigation cannot establish a fix, the agent blocks with the failed check
URL, inspected evidence, and unresolved cause or evidence gap. It cannot complete
with an unchanged tree merely because local checks pass. A repair that blocks is
not pushed. Once it completes, the runtime commits and pushes the repair, then
matches CI results to that published commit. Each published repair commit counts
once after CI reports a failure. Pending, stale, unassociated, or absent checks
do not count. The runtime observes the third repair commit's CI result and blocks
only if that result fails. Merge-only work does not spend a CI repair attempt.
The cap report names failed repair commits and their check links from recorded
evidence.

For a pull request with a known nonblank head, two successful empty check
observations at least two minutes apart and a fresh lookup confirming the same PR
number and head complete with a no-checks-reported result. Failed reads cannot
confirm it, a changed head starts a new window, and checks that appear during the
window use the normal pending, failure, and success paths. Conditional or delayed
checks may appear after completion. Inspect the PR again if those checks matter.
Branch targets keep their separate one-hour no-check policy.

Admission allows one active monitor per pull request or named branch target. A held
admission lock blocks a second monitor and names its owner. Once that lock is free,
a settled previous invocation does not block a new iteration, even when its hosting
process remains alive. Without a settled outcome, same-host positive process-death
evidence is required. An unverifiable owner or an undrained repair child still blocks.
Normal new iterations need no explicit recovery token. Optional explicit recovery
with `--recover-stale-owner <owner-token>` still needs an exact PR URL or
`branch:<name>` and validates the recorded token and process evidence.

Before replacing inactive ownership, admission archives its record under
`history/<generation>/ownership.json` in the target's monitor directory, then advances
the generation. Failure to preserve that history blocks the new iteration without
erasing the original record. Cancellation discards verified owned worktrees,
branches and refs, including unfinished edits and unpublished commits. Other
unfinished resources are preserved with their archived ownership and reported path,
and no longer block a new iteration. The caller's checkout stays untouched.

Status, events and the printed report name the exact target, repositories, head
branch, invocation, owned worktree and cleanup outcome. Clean owned resources are
removed on settlement. A hard kill has no guaranteed cleanup; the next iteration
reconciles its stale ownership using process-death evidence. Cancel and rerun from
the desktop target the exact invocation and PR URL or `branch:<name>`. Rerun launches
`phase monitor <that URL or branch:<name>>` without checking out the head branch in
the caller checkout. Goal finalization and `phase:pr` retain their branch monitoring.

While CI is pending, progress reports identify the current PR and include available
check links. Describe queued checks as waiting, and name running work only when the
CI observation reports it. Explain the next useful event, such as a check starting
or settling. Report runner capacity only when an observation provides evidence;
otherwise say it is unavailable. Do not infer missing commit or check details, or
use administrative APIs to fill gaps. A live child means the process is active,
not that source files changed. Local checks can establish a local result, but only
remote CI observations establish the PR's CI result. Repeated waiting updates can
refresh progress every 30 seconds; they do not change a verdict, deadline, or repair
attempt count.

## Phase Validation

`phase:validation` runs `sona phase validation` from Forms and Routing. A
standalone quality check (a request to run checks, lint, format, or quality
validation) routes to `phase:validation`: run
`sona phase validation [<intake>] --agent <currently-executing-agent>`
once and relay its output as a phase form.

## Pack routing

Review routes to the dominant pack for the current unit of work. Validation uses
the same full project validation strategy as goal validate. Its agent discovers
required checks from the repository instructions, build configuration, scripts,
and CI, then runs and repairs those checks. Confirmed unavailable tooling skips only
dependent checks, with warnings naming the commands or targets, missing prerequisites,
and evidence. Validation completes when every runnable required check passes. Skipped
coverage is never reported as passed. Compilation alone is insufficient.

## Operation Forms

Run an `operation:` form only at the operator's explicit request for that
standalone operation. These forms are operator tools, not agent-selected
workflow steps. Skip full-run Intake, Issue resolution, Rehydrate, and Launch. Run the
translated command once and relay its output verbatim.

When the command exits with `awaiting_confirmation` (its last line reads
`status: awaiting_confirmation confirm:<token>`), show the proposal and ask the
operator once whether to proceed. On yes, run the same operation with
`confirm:<token>`. If the operator asks for changes, run the same operation again
with those changes as instructions and relay the new proposal and its new token.
Never pass `confirm:` without an operator answer. This covers `operation:release`,
`operation:feature-guard`, and `operation:feature-guard-cleanup`; a cleanup
proposal's stabilization checklist is part of the proposal the operator answers,
so never answer it yourself.

For `operation:pr-review-fix`, local mode (the default) proposes a per-thread
recommendation matrix over open local threads on the current branch, including
user- and agent-authored threads and flagging outdated ones. Remote mode proposes
over PR threads. Each proposal labels threads `T1`, `T2`, and so on. Show it and ask the operator once
which threads to fix and with which option. Map the answer to exactly one of
`select:all-recommended`, `select:fix-all-unresolved`, or
`select:<thread>=<option>,...` (for example `select:T1=1,T3=2`), and re-run the
operation with the same `mode:`, `<pr>`, `push:`, and `replies:` tokens plus
`confirm:<token>` and that `select:`. If the answer is ambiguous, ask again; do
not infer a scope. Never pass `confirm:` or `select:` without an operator answer.
The runtime owns the thread options and refuses a selection it does not
recognise; relay that usage error and ask again. Local execution asks each agent
step to start its reply with `outcome: fixed|refuted|skipped|failed`; fixed resolves
the thread, refuted declines it, and skipped or failed leaves it unchanged. After
validation passes, local mode commits the fixes and never pushes. It refuses
protected branches and dirty worktrees.

For `operation:verify` the proposal is the extracted acceptance criteria and the
QA selection (`QA: on` or `QA: off`). Show both and ask the operator once to
confirm or adjust the criteria. On confirm, run the same operation with
`confirm:<token>`; confirm keeps the proposed QA selection and refuses a `qa:`
value that differs from it. On an adjustment, run the same operation again
with the same intake and the same `spec:`, `target:`, `mode:`, and `qa:` tokens, adding
the adjustment after the intake, then relay the new criteria and their new token; the new run
supersedes the earlier one. The verify report is final: never offer a fix or a
PR comment. When the command is blocked with a reason starting
`rehydrate-needed:`, run Rehydrate for that spec path, then run the same
operation once more.

## Update Check Operation

For `operation:update-check`, run the runtime command:

```bash
sona operation update-check
```

Use JSON output when the caller needs machine-readable output:

```bash
sona operation update-check --format json
```

To compare against prerelease tags as well as stable releases, pass:

```bash
sona operation update-check --include-prereleases
```

Do not inspect GitHub releases directly in this skill content, run `install.sh`,
rewrite installed skill links, or mutate workflow state. The runtime command owns
release selection, version comparison, output formatting, and soft failure
handling.
