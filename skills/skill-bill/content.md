---
name: skill-bill
description: "Dispatcher for the full governed feature run and single in-memory phases."
---

# Skill Bill Dispatcher

`skill-bill` routes a full feature run, or one phase over the working tree, to the
`skill-bill` CLI. The full run keeps the `bill-feature` ceremony and its single
confirmation question. Phase forms run one command and relay its output.

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
| `/skill-bill <intake> phase:implement` | `skill-bill phase implement <intake> --agent <currently-executing-agent>` | required |
| `/skill-bill [<intake>] phase:review` | `skill-bill phase review [<intake>] [mode:<value>] [target:<value>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:validation` | `skill-bill phase validation [<intake>] --agent <currently-executing-agent>` | optional |
| `/skill-bill [<intake>] phase:pr` | `skill-bill phase pr [<intake>] --agent <currently-executing-agent>` | optional |

If `phase:plan` or `phase:implement` has no intake, stop and ask for it. For any
other `phase:` name, stop and list the names in this table.

## Token Forwarding

The full run accepts at most one `code-review:inline|auto`, forwarded verbatim as
`--code-review-mode <value>`, and zero or more ordered `agent-addon:<slug>`
tokens, each forwarded as `--agent-addon <slug>` in the given order. Omitted
values remain omitted.

`phase:review` forwards `mode:inline|delegated` and `target:HEAD|uncommitted|<sha>`
verbatim as `key:value` tokens. Without `mode:`, the runtime reviews inline.

The dispatcher never resolves a review mode, a target, or an add-on catalogue, and
never constructs JSON; the runtime selects.

Stop without running preflight or any CLI command when:

- the caller passes `parallel-review:<agent>`: name the removed dual-agent
  parallel review capability.
- the caller passes `operation:<name>` without `phase:`: say that operations
  arrive with SKILL-382.
- the caller passes `phase:` together with `operation:`: report a usage error.
- a token reaches a form that does not accept it (`code-review:` or
  `agent-addon:` with any `phase:`, `mode:` or `target:` outside `phase:review`):
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
