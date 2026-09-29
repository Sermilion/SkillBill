---
name: skill-bill
description: "Dispatcher for the full governed feature run, single in-memory phases, and runtime operations."
---

# Skill Bill Dispatcher

`skill-bill` routes a full feature run, one phase over the working tree, or one
runtime operation to the `skill-bill` CLI. The full run is the only feature entry
point and keeps the governed feature ceremony and its single confirmation
question. Phase and operation forms run one command and relay its output.

## Update Check

Call `mcp__skill-bill__update_check` before any other action.

When the tool returns `status: "update_available"`:

- Show the installed version (`installed_version`) and latest version
  (`latest_version`).
- Ask whether to update or continue with the current version.
- If the user chooses to update, stop and show `recommended_install_command`.
- If the user chooses to continue, proceed to Forms and Routing.

For `up_to_date`, `ahead_of_release`, or `unknown`, proceed to Forms and Routing
without prompting.

## Forms and Routing

Pick the route from the invocation. `phase:<name>` may come before or after the
intake. Forwarded `key:value` tokens follow the intake unchanged.

| Invocation | Route | Intake |
| --- | --- | --- |
| `/skill-bill <intake>` | full run: Intake, Preflight, Gate, Rehydrate, Launch, Relay | required |
| `/skill-bill <intake> phase:plan` | `skill-bill phase plan <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill [<intake>] phase:review` | `skill-bill phase review [<intake>] [mode:<value>] [target:<value>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:validation` | `skill-bill phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill <standalone quality check: run checks, lint, format, or quality validation>` | `skill-bill phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:pr` | `skill-bill phase pr [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill operation:update-check [--include-prereleases] [--format json]` | `skill-bill operation update-check [--include-prereleases] [--format json]` | none |
| `/skill-bill [<instructions>] operation:release bump:<patch\|minor\|major>` | `skill-bill operation release bump:<value> [<instructions>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<scope>] operation:unit-test-value-check` | `skill-bill operation unit-test-value-check [scope:<value>] --agent <currently-executing-agent>` | optional |
| `/skill-bill <intake> operation:feature-guard` | `skill-bill operation feature-guard <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill <intake> operation:feature-guard-cleanup` | `skill-bill operation feature-guard-cleanup <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill [<pr>] operation:pr-review-fix [scope:analyze-only] [push:on] [replies:draft]` | `skill-bill operation pr-review-fix [<pr>] [<tokens>] --agent <currently-executing-agent>` | optional |
| `/skill-bill operation:verify <intake> [target:<pr\|branch\|base..head>] [mode:inline\|delegated]` | `skill-bill operation verify <intake> [spec:<value>] [target:<value>] [mode:inline\|delegated] --agent <currently-executing-agent>` | required |

If `phase:plan` has no intake, stop and ask for it. For any
other `phase:` name, stop and list the names in this table. If
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

Stop without running preflight or any CLI command when:

- the caller passes `parallel-review:<agent>`: name the removed dual-agent
  parallel review capability.
- the caller passes `phase:` together with `operation:`: report a usage error.
- a token reaches a form that does not accept it (`code-review:` or
  `agent-addon:` with any `phase:`, `mode:` or `target:` outside `phase:review`
  and `operation:verify`):
  report a usage error naming the token and the form that accepts it. Never drop
  the token or fold it into the intake.

## Intake

For the full run, establish:

- the issue key
- the intended outcome
- the acceptance criteria
- constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Preflight

For the full run, call this command exactly once:

```text
skill-bill goal preflight <issue-key> --agent <currently-executing-agent> --format json
```

Always pass the currently executing agent explicitly; do not rely on environment
detection. Forward the review and agent add-on values as flags.
Derive the next action from the returned `verdict`. When the verdict reports new
work, the spec is missing: run
`skill-bill phase plan <intake> --agent <currently-executing-agent>` after this
preflight, retaining the returned gate state without recomputing it. Report and
stop for an already-running or terminal-only goal. Report every candidate for an
ambiguous verdict. Surface loud failures.

## Gate

Present the returned `gate_block` as a concise human-readable summary. Include
the issue key, feature name, child agent, review settings, add-ons, expected
first runnable subtask, and each subtask with its status and dependencies.
Do not print the raw JSON or expose internal field names. Ask exactly one
question: whether to proceed. Do not launch while unconfirmed. If the user
declines, stop.

## Rehydrate

For each entry in `rehydrate_targets`, fetch the listed issue from Linear and
write the returned spec content to the target path. Fetch nothing when the list is empty.

## Launch

After confirmation and any required rehydration, run:

```text
skill-bill goal <issue-key> --agent <currently-executing-agent> --no-live-output
```

Forward the supplied review and agent add-on flags. Never ask
the user to run the command manually.

## Relay

Await the launched process through the harness completion primitive. Relay its
output verbatim, adding nothing. Do not poll, sleep, tail logs, re-read status,
launch an observer, or compose monitoring, completion, summary, or progress
output. Run goal status only when the user explicitly asks.

## Phase Forms

For a `phase:` form, skip Intake, Preflight, Gate, Rehydrate, and Launch. Run the
translated command from Forms and Routing once and relay its output verbatim,
adding nothing. Do not add checklists, rubrics, or steps from other skills. Never
ask the user to run the command manually.

Implementation and simplification run inside workflows and consume their plan
output. There is no standalone implementation phase.

## Phase Review

`phase:review` runs `skill-bill phase review` from Forms and Routing. The
sections from Review mode argument through Present the register govern its
arguments and its output. An omitted target reviews uncommitted changes when
the worktree is dirty and HEAD otherwise. Where they say to invoke the driver, run the
`phase:review` command instead of `skill-bill code-review`: forward the review
target as `target:<value>` and the review mode as `mode:<value>`. The accepted
targets are `pr`, `staged`, `unstaged`, `HEAD` or `last`, `uncommitted`, and a
commit `<sha>`.

## Review mode argument

Recognize at most one `mode:auto|inline|delegated` argument.
Omission means `mode:inline`.
Reject malformed, unknown, duplicate, or conflicting values before invoking the
driver.

## Review target argument

Recognize at most one non-blank positional review target:

- `pr` reviews the current pull request against its base.
- `last` or `HEAD` reviews HEAD against its first parent.
- a commit SHA or other git revision reviews that commit against its first parent.
- `uncommitted` reviews staged, unstaged, and untracked work.
- `staged` and `unstaged` keep those narrower packets.

A positional review target cannot be combined with `--diff-file`,
`--base-revision`, `--head-revision`, or a conflicting `--scope`.
When the positional target already names the packet (`pr`, `last`,
`uncommitted`, `staged`, `unstaged`), omit `--scope`. A commit SHA uses the
default branch scope so the driver diffs that commit against its first parent.
Without a positional target, pass the caller's `--scope` normally.

## Invoke the driver

Do not invent a scope from git, classify diff signals, name rubrics, sequence
commits, account budgets, merge lanes, or launch workers in this session. Map
the caller's named target, invoke the runtime driver once, and present what it
returns:

```bash
skill-bill code-review \
  [<target>] \
  --execution-mode inline \
  [--scope <caller-scope>] \
  --repo-root <repo-root>
```

Pass the caller's named target as the positional argument (`pr`, `last`,
`<commit>`, `uncommitted`, `staged`, or `unstaged`). Do not pass `pr`,
`last`, or `uncommitted` as a git revision unless the caller supplied a real SHA.
When the positional target already names the packet, omit `--scope`.

When the caller supplied an explicit `mode:delegated`, pass `--execution-mode delegated`
instead. Omission and `mode:auto` always pass `--execution-mode inline`.

Pass `--diff-file` with paired `--base-revision` and
`--head-revision` when the caller already materialized an exact diff. With
`--execution-mode delegated`, pass `--baseline-untracked-include` /
`--baseline-untracked-exclude` when the caller supplied that inventory; inline
mode rejects them.

When a governed feature caller supplies a labelled `Selected agent add-ons`
section, treat that section as an immutable compact-context field. The driver
forwards it; do not rediscover add-ons.

## Present the register

Display the driver's stdout as the review result. It already includes the risk
register with provenance labels and any recorded stage verdicts. Do not rewrite
findings, invent a second merge, or re-run the review in this session.

The driver runs the in-memory review phase: it verifies the findings and fixes
Blocker and Major findings in the working tree before it reports the rest. Do
not apply those fixes again. A `# Review phase blocked` line means the phase
stopped before it finished; report it and exit non-zero.

## Phase PR

`phase:pr` composes `commit_push` followed by `pr`. The runtime refuses a detached,
protected, or base branch before staging. It commits staged, unstaged, and untracked
changes, excluding ignored and runtime-private files, then pushes. The PR step
creates or updates the branch's open PR. A clean retry reuses the existing commit.
The phase creates no workflow row, branch, or checkpoint commit.

## Phase Validation

`phase:validation` runs `skill-bill phase validation` from Forms and Routing. A
standalone quality check (a request to run checks, lint, format, or quality
validation) routes to `phase:validation`: run
`skill-bill phase validation [<intake>] --agent <currently-executing-agent>`
once and relay its output as a phase form.

## Routing

Review routes to the dominant pack for the current unit of work. Validation uses
the same full project validation strategy as goal validate. Its agent discovers
required checks from the repository instructions, build configuration, scripts,
and CI, then runs and repairs those checks. Compilation alone is insufficient.

## Operation Forms

For an `operation:` form, skip Intake, Preflight, Gate, Rehydrate, and Launch. Run
the translated command once and relay its output verbatim.

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
