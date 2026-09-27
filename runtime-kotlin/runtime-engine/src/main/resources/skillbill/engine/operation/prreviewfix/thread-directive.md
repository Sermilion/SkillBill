# PR review fix: thread {{ordinal}}

Apply the operator's selection for exactly one review thread of {{pull_request}}.
The stored analysis matrix is your prior value; the operator confirmed it
unchanged.

Selected thread: {{ordinal}} ({{thread_id}}) at {{location}}
Selected option: {{option}}

{{option_note}}

## Thread comments

{{comments}}

## Rules

- Fix only this thread. One thread's fix must not bleed into unrelated files; if
  it must, do not edit: end the step with status `blocked` and a summary naming
  the files it would spread into.
- Do not "drive-by fix" unrelated issues you meet while editing.
- If this selection conflicts with the code another selected thread asked for
  (reviewer A says X, reviewer B says Y on the same line), do not edit: end the
  step with status `blocked` and a summary naming the conflict.
- Never put a stop report in your value. The value is posted publicly on the
  thread; a blocked step stops the run before any reply is posted.
- Do not run `gh` mutations, post replies, resolve threads, commit, or push. The
  runtime runs the quality gate, posts your reply, and pushes only when the
  operator enabled push.

## Learnings

When the verdict is `agree` or `partial` and you changed code, append a
high-signal entry to the nearest boundary `agent/history.md`, following the
`bill-boundary-history` format and write/skip rules. Skip one-off patch details
that won't apply to future work and changes that simply restate existing
conventions; keep entries reusable and future-actionable (pattern, pitfall, named
constraint).

## Deferred work

If the selected option defers a recommended fix as out of scope, draft a spec in
`.feature-specs/<NEXT-KEY>-<kebab-title>/spec.md`. Detect the key from the host
repo: scan `.feature-specs/` for the dominant `<PREFIX>-<N>` pattern (the most
frequent prefix wins) and use the next number after the highest one for that
prefix. If no pattern exists, write no spec and say so in your reply; do not
invent a prefix. One spec per deferred theme:

```markdown
# Feature: <KEY>-<kebab-title>

Created: <YYYY-MM-DD>
Status: Proposed
Sources:
- Deferred from PR #<n> thread <thread-id>
- <reviewer name> raised this as out-of-scope for the current PR

## Problem
<one-paragraph problem statement>

## Goal
<what shipping this would deliver>

## Acceptance Criteria
1. <criterion>
2. <criterion>

## Non-Goals
- <explicitly out>

## Risks
- <risk>

## Rollout
<plan>
```

## Value

Your value is the reply text for this thread and nothing else. Reply with exactly
`👍` only for plain agreement where you changed exactly what the reviewer asked;
otherwise write 1-3 plain sentences stating what changed (or why nothing changed).
Never apologize, and reference new code by file path, not by quoting it.
