---
name: skill-bill
description: "Dispatcher for the full governed feature run, single standalone phases, and runtime operations."
---

# Skill Bill Dispatcher

`skill-bill` routes a full feature run, one phase over the working tree, or one
runtime operation to the `skill-bill` CLI. The full run is the only feature entry
point. The runtime owns preparation, planning, execution, and durable state.
Phase and operation forms run one command and relay its output.

## Operator-only commands

Standalone phases and operations are operator tools. An agent may run
`skill-bill phase` or `skill-bill operation` only when the operator explicitly
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
Call `mcp__skill-bill__update_check` once before full-run intake.
Keep the selected runtime for the whole run, including every subtask and retry.

Runtime-launched phase workers, goal children, retries, and continuations execute
their supplied briefing. They must not call `mcp__skill-bill__update_check`, ask
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

| Invocation | Route | Intake |
| --- | --- | --- |
| `/skill-bill <intake>` | full run: Intake, Issue resolution, Launch, Relay | required |
| `/skill-bill <intake> phase:plan` | `skill-bill phase plan <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill [<intake>] phase:review` | `skill-bill phase review [<intake>] [mode:<value>] [target:<value>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:validation` | `skill-bill phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill <standalone quality check: run checks, lint, format, or quality validation>` | `skill-bill phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:pr` | `skill-bill phase pr [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<issue-key\|pr-url>] phase:monitor` | `skill-bill phase monitor [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill operation:update-check [--include-prereleases] [--format json]` | `skill-bill operation update-check [--include-prereleases] [--format json]` | none |
| `/skill-bill [<instructions>] operation:release bump:<patch\|minor\|major>` | `skill-bill operation release bump:<value> [<instructions>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<scope>] operation:unit-test-value-check` | `skill-bill operation unit-test-value-check [scope:<value>] --agent <currently-executing-agent>` | optional |
| `/skill-bill <intake> operation:feature-guard` | `skill-bill operation feature-guard <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill <intake> operation:feature-guard-cleanup` | `skill-bill operation feature-guard-cleanup <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill [<pr>] operation:pr-review-fix [scope:analyze-only] [push:on] [replies:draft]` | `skill-bill operation pr-review-fix [<pr>] [<tokens>] --agent <currently-executing-agent>` | optional |
| `/skill-bill operation:verify <intake> [target:<pr\|branch\|base..head>] [mode:inline\|delegated]` | `skill-bill operation verify <intake> [spec:<value>] [target:<value>] [mode:inline\|delegated] --agent <currently-executing-agent>` | required |

`phase:plan` is a durable, resumable plan: it seeds the parent spec, opens a
plan workflow, stops after the verified spec bundle, and prints the bundle
paths, the plan workflow id, and `skill-bill <KEY>`. Running the full run for
that key later finishes an incomplete plan and then executes the goal. The
dispatcher never invokes `phase plan` itself.

If `phase:plan` has no intake, stop and ask for it. For any
other `phase:` name, stop and list the names in this table. `phase:verify` stops
and points to `operation:verify`, because `verify` is an operation. If
`operation:feature-guard` has no intake describing the change to guard, or
`operation:feature-guard-cleanup` has no intake naming the flag, stop and ask for
it. For `operation:unit-test-value-check`, forward a scope the caller gives (a test
file, commit sha, or ref) verbatim as `scope:<value>`; without one, omit `scope:`
and the runtime reviews the current staged and unstaged changes. For
`operation:pr-review-fix`, forward a PR the caller gives first, before any
token, as `#<number>` or its URL; without one, the runtime uses the current
branch's PR. For `operation:update-check`, forward `--include-prereleases` and
`--format json` verbatim when the caller gives them; without them, omit them.

`operation:<name>` translates to `skill-bill operation <name>`, forwarding
`bump:`, `confirm:`, `select:`, `mode:`, `scope:`, `push:`, `replies:`, `spec:`,
and `target:` tokens verbatim and any other text as operator instructions. The
runtime rejects an unknown operation name, a missing bump, a missing guard
intake, a `push:` or `replies:` token outside `operation:pr-review-fix`, or a
`spec:`, `target:`, or `mode:` token outside `operation:verify`; relay its usage
error. For `operation:verify`, forward the intake verbatim: a Linear issue key or
URL, the requirements as raw text, or a `spec:<path>` token. If it has no intake,
stop and ask for it. Forward `target:` and `mode:` verbatim; without `target:`,
the runtime verifies HEAD against `origin/HEAD`, and without `mode:`, it reviews
inline.

## Token Forwarding

The full run accepts at most one `code-review:inline|auto`, forwarded verbatim as
`--code-review-mode <value>`, and zero or more ordered `agent-addon:<slug>`
tokens, each forwarded as `--agent-addon <slug>` in the given order. Omitted
values remain omitted.

`phase:review` forwards `mode:inline|delegated` and
`target:pr|staged|unstaged|HEAD|last|uncommitted|<sha>` verbatim as `key:value`
tokens. Without `mode:`, the runtime reviews inline.

The dispatcher never resolves a review mode, a target, or an add-on catalogue, and
never constructs JSON; the runtime selects.

Stop without running any CLI command when:

- the caller passes `parallel-review:<agent>`: name the removed dual-agent
  parallel review capability.
- the caller passes `phase:` together with `operation:`: report a usage error.
- a token reaches a form that does not accept it (`code-review:` or
  `agent-addon:` with any `phase:`, `mode:` or `target:` outside `phase:review`
  and `operation:verify`):
  report a usage error naming the token and the form that accepts it. Never drop
  the token or fold it into the intake.

## Issue resolution

For a tracker link or issue key, check local specs and persisted workflow state
before contacting the tracker. Search `.feature-specs/` in the current repository,
including ignored and untracked files. Use
`rg --files --hidden --no-ignore .feature-specs` or inspect the directory directly.
Match the issue key and read the matching `spec.md`. A filename search that
honors ignore rules does not prove that a spec is absent. If several bundles match, ask for the intended spec path.

A readable local spec launches directly through the full runtime. Do not run
`work status` or `work list` during intake. The runtime looks up only workflows
for the requested issue and repository before deciding whether to resume or
start new work. Unrelated workflows and their contract versions cannot block
this lookup.

When no readable local spec exists, inspect only the requested goal with
`skill-bill goal status <issue-key> --repo-root <repo-root>`. This targeted
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

For new requirements without a key, inspect `.feature-specs/` in the current
repository, including ignored and untracked files and archived folders such as
`done/`. Use `rg --files --hidden --no-ignore .feature-specs` or inspect the
directory directly. Read bundle directory names, parent `spec.md` identities,
and `decomposition-manifest.yaml` issue keys. Count bundle identities, not keys
mentioned as dependencies or examples inside requirements.

Use an operator-supplied prefix when present. Otherwise use the sole project
prefix found in those local identities, excluding generated `LOCAL-` hash keys.
If several project prefixes exist, ask only which prefix to use. If no project
prefix exists, ask for a local prefix or key, without requiring a tracker.

For the selected prefix, take the highest integer issue number and add one.
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
skill-bill <intake> --agent <currently-executing-agent> --no-live-output
```

Pass the intake as one shell-quoted argument with real newlines preserved. The
CLI routes it to the goal runtime. `skill-bill goal <intake>` is the explicit
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

The exception is a goal run that exits with `awaiting_no_change_decision` (its
last line reads `awaiting_no_change_decision: subtask:<N> reason:<reason>`). The
subtask's implementer claimed that no code change is needed and an audit confirmed
the claim, so the run is paused for the operator. Show the reported reason, the
verdict and evidence for each criterion, the citations, the boundary trace, the
owning system, and the suggested handoff, taken from the pause detail printed
above that line. Ask the operator once whether to accept the no-change result
(`accept_and_advance`), retry the fix with instructions (`retry_fix`), or abandon
the subtask (`abandon_subtask`). On the answer, run
`skill-bill goal operator-decision <ISSUE-KEY> --subtask <N> --decision <choice>`,
adding `--instructions "<text>"` only for `retry_fix` and passing the operator's
instructions verbatim. Then resume the goal with `skill-bill goal <ISSUE-KEY>` and
relay its output verbatim. Never run `operator-decision` without the operator's
answer, never choose a decision for the operator, and never ask again once the
operator has answered.

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

## Phase PR

`phase:pr` composes `commit_push`, `pr`, and `monitor`. The runtime refuses a
detached, protected, or base branch before staging. It commits staged, unstaged, and
untracked changes, excluding ignored and runtime-private files, then pushes. The PR
step creates or updates the branch's open PR, and monitor then watches its CI as in
Phase Monitor. A clean retry reuses the existing commit. The phase creates no
workflow row, branch, or checkpoint commit.

## Phase Monitor

`phase:monitor` watches CI on the open PR of the checked-out branch. An issue key
or PR URL intake names the work; it does not switch branches. The phase composes
`commit_push` followed by `monitor`, so it commits and pushes any pending changes
first, like `phase:pr`. When every check passes, is skipped, or is cancelled, the
phase completes. It also completes when the PR is merged, regardless of failing
or pending checks. When a check fails, `monitor_fix` starts repair on that poll even
while other checks are pending. The runtime commits and pushes the fix, and
monitor watches again, up to three fix attempts. The phase then blocks
with the failing checks and the last fix summary. When the branch has no open PR,
the phase completes and reports that there is nothing to monitor.

## Phase Validation

`phase:validation` runs `skill-bill phase validation` from Forms and Routing. A
standalone quality check (a request to run checks, lint, format, or quality
validation) routes to `phase:validation`: run
`skill-bill phase validation [<intake>] --agent <currently-executing-agent>`
once and relay its output as a phase form.

## Pack routing

Review routes to the dominant pack for the current unit of work. Validation uses
the same full project validation strategy as goal validate. Its agent discovers
required checks from the repository instructions, build configuration, scripts,
and CI, then runs and repairs those checks. Compilation alone is insufficient.

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

For `operation:pr-review-fix` the proposal is a per-thread recommendation matrix
with threads labelled `T1`, `T2`, and so on. Show it and ask the operator once
which threads to fix and with which option. Map the answer to exactly one of
`select:all-recommended`, `select:fix-all-unresolved`, or
`select:<thread>=<option>,...` (for example `select:T1=1,T3=2`), and re-run the
operation with the same `<pr>`, `push:`, and `replies:` tokens plus
`confirm:<token>` and that `select:`. If the answer is ambiguous, ask again; do
not infer a scope. Never pass `confirm:` or `select:` without an operator answer.
The runtime owns the thread options and refuses a selection it does not
recognise; relay that usage error and ask again.

For `operation:verify` the proposal is the extracted acceptance criteria. Show
them and ask the operator once to confirm or adjust them. On confirm, run the same
operation with `confirm:<token>`. On an adjustment, run the same operation again
with the same intake and the same `spec:`, `target:`, and `mode:` tokens, adding
the adjustment after the intake, then relay the new criteria and their new token; the new run
supersedes the earlier one. The verify report is final: never offer a fix or a
PR comment. When the command is blocked with a reason starting
`rehydrate-needed:`, run Rehydrate for that spec path, then run the same
operation once more.

## Update Check Operation

For `operation:update-check`, run the runtime command:

```bash
skill-bill operation update-check
```

Use JSON output when the caller needs machine-readable output:

```bash
skill-bill operation update-check --format json
```

To compare against prerelease tags as well as stable releases, pass:

```bash
skill-bill operation update-check --include-prereleases
```

Do not inspect GitHub releases directly in this skill content, run `install.sh`,
rewrite installed skill links, or mutate workflow state. The runtime command owns
release selection, version comparison, output formatting, and soft failure
handling.
