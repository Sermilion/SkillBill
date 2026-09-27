# PR review fix: analysis

Analyze the unresolved review threads of {{pull_request}} and propose options per
thread. The runtime fetched the threads through GitHub's GraphQL API and
classified them from their `isResolved` and `isOutdated` flags; the digest below
is the complete input. Read the code each thread points at before you judge it.

This step is read-only. Do not edit, create, or delete files. Do not run `gh`
mutations, post replies, resolve threads, commit, or push. The runtime refuses
the analysis if the worktree changes.

## Recommendation matrix

For each actionable thread, in ordinal order, render:

```
Thread <ordinal> — <path>:<line>
Summary: <one-line summary of what the reviewer said>
Verdict: agree | partial | disagree
Rationale: <why this verdict, citing the code or constraint>
Hidden/special context: <anything the reviewer may not know, or "—">
Options:
  1. (recommended) <description>
  2. <description>
  3. <description, optional>
Proposed reply:
  <reply text — see Reply Style below>
```

Option 1 is always the recommended one. Use the runtime's ordinals (`T1`, `T2`,
...) as the thread labels; the operator selects by them.

After the matrix, add two sections:

- **Informational** — actionable threads where the reviewer asked a question or
  made an observation but no code change is implied.
- **Already handled** — the resolved or outdated threads (ordinal-free id and
  path:line only). List them; recommend no change.

Include each thread's file, line, and comment body verbatim wherever a verdict
depends on it, so the operator can override you.

## Reply Style — strict

- `👍` **only** when verdict is `agree` AND no additional context, references, or
  qualifications are appropriate. A bare 👍 must mean "you were right, I changed
  exactly what you asked, nothing more to say."
- Otherwise write 1-3 sentences in plain prose. No bullet lists in replies unless
  the reviewer used them and you're answering point-by-point.
- Never apologize ("Sorry, good catch!"). State facts: what changed, why, or why
  not.
- Reference the new code by file path or commit SHA, not by quoting it back.

Surface a significant architectural tradeoff as options instead of deciding it.

## Value

Your value is the matrix and the two sections above, as prose. The runtime
stores it verbatim and shows it to the operator, who answers with
`select:all-recommended`, `select:fix-all-unresolved`, or
`select:<ordinal>=<option>,...`.

## Threads

{{thread_digest}}
