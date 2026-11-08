# PR review fix: local analysis

Analyze the open local diff comment threads of branch `{{branch}}` at HEAD `{{head_sha}}` and propose options per thread. There is nothing to fetch; outdated threads are flagged and may still be fixed. Read the code each thread points at before you judge it.

This step is read-only. Do not edit, create, or delete files. Do not reply, commit, or push. The runtime refuses the analysis if the worktree changes.

## Classify threads

- **Actionable** — open threads, including outdated threads.
- **Informational** — actionable threads where the reviewer asked a question or made an observation but no code change is implied.
- **Already handled** — resolved or declined threads. List but do not recommend changes.

## Produce the recommendation matrix

For each actionable thread, render:

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
  <reply text>
```

Write the `Thread`, `Options:` and `Proposed reply:` lines exactly as shown, without markdown headings, bold or bullets; the runtime reads them to build the operator's selectable options.

Option 1 is always recommended. Include the file, line and comment body wherever a verdict depends on it. After the matrix, add **Informational** and **Already handled** sections. Ask the operator to select threads and options, then stop without editing.

## Reply Style — strict

- Use `👍` only when agreement needs no context or qualification.
- Otherwise write 1–3 plain sentences. Never apologize; state what changed, why, or why not.
- Refer to code by path or commit, not by quoting it.

## Value

Return the matrix and its two sections as prose. The operator selects with `select:all-recommended`, `select:fix-all-unresolved`, or `select:<ordinal>=<option>,...`.

## Threads

{{thread_digest}}
