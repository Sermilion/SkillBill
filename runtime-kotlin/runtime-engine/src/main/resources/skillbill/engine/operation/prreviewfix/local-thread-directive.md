# PR review fix: local thread {{ordinal}}

Apply the operator's selection for exactly one local diff comment thread.
The stored analysis matrix is your prior value; the operator confirmed it unchanged.

Selected thread: {{ordinal}} ({{thread_id}}) at {{location}}
Selected option: {{option}}

{{option_note}}

## Thread comments

{{comments}}

## Rules

- Fix only this thread. Do not drive-by fix unrelated issues.
- If this selection conflicts with another selected thread, stop without editing and explain the conflict.
- Do not put a stop report in your reply value; a failed or refused step stops before any reply is written.
- Do not edit local diff comments, commit, or push; the runtime replies on the thread and commits after validation.

## Learnings

When the verdict is agree or partial and code changed, append a high-signal reusable entry to the nearest boundary `agent/history.md`. Skip one-off patch details and changes that restate existing conventions.

## Deferred work

If the selection defers a recommended fix as out of scope, draft a follow-up spec under `.feature-specs/` only when the repository has an established issue-key pattern. Otherwise explain that no key pattern exists.

## Value

Start with exactly one line `outcome: fixed|refuted|skipped|failed`, followed by 1–3 plain sentences explaining what changed or why nothing changed.
