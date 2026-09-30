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
`boundary-history` strategy format and write/skip rules. Skip one-off patch details
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

# PR Review Fix Content

## Overview

Orchestrate review-comment resolution on an existing PR from intake to verified push. The skill has two gated phases:

1. **Analysis** — fetch unresolved threads, classify them, propose options, wait for the user's selection.
2. **Execution** — apply selected fixes, post replies, record learnings, optionally create follow-up specs, run the quality gate, and push.

Never make code changes, post replies, or push without explicit user selection in Phase 1.

## Inputs

Required:

- PR identifier: number, URL, or current-branch PR (auto-resolved from `gh pr view --json`).

Optional, ask only when ambiguous:

- Scope mode: `analyze-only` | `analyze+fix-selected` (default) | `fix-all-unresolved`.
- Reply mode: `draft-only` | `post-replies` (default).
- Hygiene: `rebase`, `quality-check` (default on), `push` (default off until user confirms).

## Phase 2 — Execution

### Apply selected fixes

For each selected thread + option, edit the relevant files. After every edit, record the mapping in working memory:

```
Thread <thread-id> → <files changed> (option <n>)
```

Rules:

- One thread's fix should not bleed into unrelated files. If it must, surface that and confirm.
- If two selected threads conflict (reviewer A says X, reviewer B says Y in the same line), stop and ask.
- Do not "drive-by fix" unrelated issues encountered while editing — that's a non-goal.

### Run quality gate

After all selected fixes land, invoke `skill-bill phase validation`. It auto-routes to whichever stack-specific quality skill the host repo's platform pack declares. Fix issues at the root cause; never use suppressions. Report initial vs final failure count.

If the routed quality skill is unavailable, fall back to the repo-native command (commonly `./gradlew check`, `npm test`, `pytest`, etc.) and say so.

### Post / draft replies

For each thread you acted on (including ones the user explicitly chose to disagree on):

- **Pure agreement, no extra context** → reply with exactly `👍`.
- **Anything else** — agreement with context, partial, or disagree — write a short contextual reply (1-3 sentences) that:
  - States what changed (or why nothing changed).
  - References commit SHAs only if helpful (`git rev-parse --short HEAD` after the fix lands).
  - Avoids restating the reviewer's comment back to them.

Reply mechanism — use the per-comment GraphQL mutation, not generic PR comments:

```bash
gh api graphql -F threadId=<id> -F body=<body> -f query='
  mutation($threadId:ID!,$body:String!){
    addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:$threadId, body:$body}){
      comment{ id url }
    }
  }'
```

If reply mode is `draft-only`, print the replies grouped by thread and skip the mutation.

Never call `resolveReviewThread` automatically. Resolution is the reviewer's prerogative unless the user explicitly asks for it.

### Record learnings

For every **agreed** thread (verdict `agree` or `partial` that landed code changes), append a high-signal entry to the nearest boundary `agent/history.md`. Delegate to the `boundary-history` strategy for format and write/skip rules — it owns hygiene.

Filters:

- Skip one-off patch details that won't apply to future work.
- Skip changes that simply restate existing conventions.
- Keep entries reusable and future-actionable (pattern, pitfall, named constraint).

### Create follow-up specs for deferred work

If a recommended fix was intentionally deferred as out-of-scope (a larger refactor flagged by the reviewer that the user chose not to address now), draft a spec in `.feature-specs/<NEXT-KEY>-<kebab-title>/spec.md`.

**Issue key detection (host-repo-aware):**

1. Scan `.feature-specs/` for the dominant `<PREFIX>-<N>` pattern (most-frequent prefix wins).
2. Use the next number after the highest existing one for that prefix.
3. If no pattern exists, ask the user for a prefix; do not invent one.

Spec template:

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

One spec per deferred theme — do not bundle unrelated deferrals.

### Optional hygiene: rebase + push

Only when the user explicitly opts in:

- **Rebase**: `git fetch origin && git rebase origin/<base-branch>`. Resolve conflicts interactively — never `--theirs`/`--ours` blindly.
- **Push**: prefer plain `git push`. Use `git push --force-with-lease` only when a rebase rewrote history, and never `--force` (no lease). Refuse to push to protected branches (`main`, `master`, `release/*`) directly — if the branch is one of those, stop and confirm.

## Output Contract

### Phase 1 — Analysis output (printed)

- Per actionable thread: id, path:line, summary, verdict, context, options (recommended first), proposed reply.
- Grouped section: informational threads.
- Grouped section: already-resolved/outdated threads (id + path:line only).
- Explicit waiting checkpoint: "Waiting for your selection. Reply with thread+option pairs, or 'all recommended'."

### Phase 2 — Execution output (printed)

- Applied fixes table: thread-id → files changed → option taken.
- Replies table: thread-id → posted | drafted → first line of reply.
- Learnings table: thread-id → path of `agent/history.md` updated → entry title.
- Out-of-scope specs created: key → path → title.
- Quality gate: command, initial failure count, final failure count, result.
- Git status: clean | dirty (with reason). Push result if performed.

## Reply Style — strict

- `👍` **only** when verdict is `agree` AND no additional context, references, or qualifications are appropriate. A bare 👍 must mean "you were right, I changed exactly what you asked, nothing more to say."
- Otherwise write 1-3 sentences in plain prose. No bullet lists in replies unless the reviewer used them and you're answering point-by-point.
- Never apologize ("Sorry, good catch!"). State facts: what changed, why, or why not.
- Reference the new code by file path or commit SHA, not by quoting it back.

## Non-Goals

- Auto-resolving GitHub threads. The reviewer resolves; the skill never calls `resolveReviewThread` without explicit user instruction.
- Making architectural decisions when the reviewer's tradeoff is significant. Surface the tradeoff and ask.
- Fixing unrelated pre-existing repo issues encountered while editing — those belong in a follow-up spec or a separate PR.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Misclassifying a thread without full context | Always include the file/line and the comment body verbatim in the matrix so the user can override. |
| Over-fixing beyond approved scope | Strict thread-ID → file traceability; refuse to bundle unrelated edits. |
| Conflicting reviewer comments on the same line | Stop and ask before editing. |
| Unsafe force-push | `--force-with-lease` only; never `--force`. Refuse direct push to protected branches. |
| Learnings polluted with one-off patch noise | Delegate write/skip rules to the `boundary-history` strategy. |

## Rules

- Phase 1 must complete before any Phase 2 work begins. No silent edits.
- One thread → one fix mapping in working memory until Phase 2 reports it.
- Quality gate runs after fixes, before replies are posted, so reply context can cite the final state.
- If quality gate fails and cannot be fixed, stop. Do not post replies promising fixes that aren't green.
- If invoked from an orchestrator, follow its instructions for telemetry forwarding; standalone runs do not emit telemetry of their own.

## Value

Your value is the reply text for this thread and nothing else. Reply with exactly
`👍` only for plain agreement where you changed exactly what the reviewer asked;
otherwise write 1-3 plain sentences stating what changed (or why nothing changed).
Never apologize, and reference new code by file path, not by quoting it.
