# Boundary Decisions Content

## Purpose

Record **why** something was done a certain way — not what changed (that belongs in `history.md`), but the reasoning behind non-obvious choices, special cases, constraints, and trade-offs that future contributors need to understand before modifying the code.

## Inputs Required

- Decision title (short, descriptive)
- Primary module/package/area where the decision applies
- User explanation of the decision (the agent captures and structures it, not invents it)

## Input Recovery

- If the user omits the primary boundary, infer it from the files most recently changed in the current diff.
- If the user gives a free-form explanation, the agent structures it into the entry format below — preserving the user's reasoning faithfully without adding interpretation.

## Entry Format

```markdown
## [<date>] <short decision title>
Context: <what situation or requirement prompted this decision — 1-2 lines>
Decision: <what was chosen — 1-2 lines>
Reason: <why this approach over alternatives — 1-3 lines>
```

Optional trailing lines (include only when relevant):

- `Alternatives considered: <what was rejected and why — 1 line>`
- `Revisit when: <condition that would make this decision worth re-evaluating>`
- `Superseded by: <new title> (<date>)`

Example with a supersession line (placed after `Reason`, before any other optional lines):

```markdown
## [2025-03-01] Use in-memory cache for session tokens
Context: Session lookup latency dominated auth middleware.
Decision: Cache tokens in a process-local map with TTL eviction.
Reason: Redis added ops burden without measurable gain at current scale.
Superseded by: Rotate session store to Redis (2026-01-15)
```

## Format Rules

- Max 10 lines per entry.
- Entry body at most 4096 UTF-8 bytes, measured from the `## [<date>] <title>` heading to the next such heading (subheadings and undated `##` lines count as body). `BoundaryMemoryEntrySizeRepoTest` fails the build above that, and finding verification truncates longer bodies at the limit. Condense, or split into separate dated entries.
- Newest entry first (reverse chronological).
- No code snippets; describe patterns and choices in plain language.
- One decision per entry — if the user describes multiple decisions, write separate entries.

## File Rules

- File path: `<primary-boundary>/agent/decisions.md`.
- **Forbidden — excluded roots:** never create `agent/` under `platform-packs/` or any other root the runtime's goal-planning discovery exclusion list denies. Planning discovery denies those roots, so decisions written there are unreadable memory. Write to the nearest non-excluded owning boundary instead.
- If the file does not exist, create it along with any missing parent directories.
- Newest entry first.
- No fixed entry cap.
- Keep older entries when they still provide useful context for understanding the boundary's design.

### Pre-write hygiene

Before or while appending a new entry, read the target boundary's existing governed `## [<date>] <title>` headings from `<primary-boundary>/agent/decisions.md`. Classify each heading relative to the new decision's scope — not keyword overlap — as one of:

- **no-conflict** — the existing entry covers a different concern; leave it unchanged.
- **fully-replaced** — the new decision fully replaces this same-boundary entry and the old `Reason` adds no useful trap or rejected-path context.
- **superseded-by-new** — the new decision replaces this entry's conclusion, but the old `Reason` still documents a trap or rejected path worth keeping.

### Supersession and delete

- **fully-replaced:** delete the entire old entry. Name the obsolete same-boundary entry before deleting; never delete by age or bulk prune.
- **superseded-by-new:** keep the old entry body and append a trailing `Superseded by: <new title> (<date>)` line inside the entry block. Do not edit the `## [<date>] <title>` heading line.
- **no-conflict:** do not modify the existing entry.

Writer-owned hygiene is supersession or delete when naming a conflicting same-boundary entry — never age-based pruning. No runtime auto-deletes decisions by age.

**Non-rule — `history_recency_days` is history-only:** `history_recency_days` applies only to `history.md` verification discovery. Do not extend it to `decisions.md`. Decision volume stays bounded by catalog caps and agent heading selection, not a date window.

## Distinguishing from History

- `history.md`: *what* changed, reusable patterns, feature scope — written after feature completion.
- `decisions.md`: *why* something was done this way — written whenever the user wants to capture reasoning.

If the user describes something that is purely a "what changed" summary with no reasoning, suggest an `agent/history.md` entry instead.

## Output

Report one concise result:

- Target file path.
- **Written:** entry count and title(s), or skipped (with reason).
- **Pruned (deleted):** entry count and title(s); state `0` when none were deleted.
- **Superseded:** entry count and title(s) for entries kept with a trailing `Superseded by:` line; state `0` when none were superseded.
