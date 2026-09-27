---
name: bill-code-review
description: Dominant-stack code-review entry point. Use when reviewing a PR, a commit, last commit, uncommitted changes, or when the user asks for a code review.
---

# Code review entry

## No-argument invocation

When invoked without arguments, print the accepted arguments and do not invoke
the driver:

```text
Accepted arguments:
  pr                               Review the current PR against its base.
  last                             Review HEAD against its first parent.
  <commit>                         Review that commit against its first parent.
  uncommitted                      Review uncommitted work (staged, unstaged, and untracked).
  staged                           Review the index only.
  unstaged                         Review the worktree against the index.
  mode:auto|inline|delegated       Select review depth.
  context:feature-remediation     Review a bounded remediation delta.
```

## Review mode argument

Recognize at most one `mode:auto`, `mode:inline`, or `mode:delegated` argument.
Omission means `mode:inline`.
Reject malformed, unknown, duplicate, or conflicting values before invoking the
driver.

Recognize at most one `context:feature-remediation` argument. It is valid only
with `mode:inline` when a governed feature-task caller supplies the exact
remediation delta since its checkpoint. Reject it with another mode, a full
branch/PR scope, or no bounded remediation scope.

`delegated` and `inline` are two review depths, not two ways to execute the same
review. Report the requested mode and the resolved depth in the normal review
metadata.

`inline` is the default depth. `delegated` is the experimental full-depth tier and
runs only on an explicit `mode:delegated` from this skill. Goal and feature-task
runs never select it. Neither an omitted argument nor `mode:auto` ever reaches it.
Choose it when a change genuinely warrants per-area depth, not by default.

`delegated` always runs the normal routed delegated path
including specialist selection. Inability to launch a required native worker
blocks loudly; it never degrades to inline.

`inline` is the single-prompt light tier: one review subagent launched by the
driver as the declared `bill-code-review-inline` native agent, no per-area
specialist workers, no nested baseline orchestrator, under a bounded budget at
reduced depth. The worker traverses the delta exactly once against one combined
checklist, holding all areas in mind simultaneously — it must never re-walk the
same delta once per area. Never present it as equivalent to a delegated result.

`auto` resolves to `inline` everywhere: a subtask's first review pass, a standalone
review with no pass number, and every follow-up or remediation pass. Preserve and
report the applicable named auto rule for telemetry. `auto` never reaches the
experimental delegated tier — only an explicit `mode:delegated` on this skill does.

Depth is the only thing the light tier lowers. The severity vocabulary, evidence
and observable-consequence requirements, F-XXX register guidance, and telemetry
are inherited unchanged and are never restated per tier. Register shape is
best-effort guidance. The phase result is the agent output string; the runtime
governs launch, evidence, and persistence rather than policing the format.

With `context:feature-remediation`, the pass is bounded to the supplied
remediation delta — all findings addressed in that round unioned with the
pre-fix-to-post-fix diff — rather than the full base-to-current delta, and
verification is its primary output. For every Blocker the prior pass emitted,
state `resolved`, `unresolved`, or `superseded` under the durable
`blocker_dispositions` key, and cite the specific changed lines that settle it.
A disposition without that evidence is not admissible.

## Removed parallel lane argument

If the caller passes `parallel:<agent>` or `parallel:<agent>:<model>`, stop immediately,
name the removed dual-agent parallel review capability, and do not invoke the driver.

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
