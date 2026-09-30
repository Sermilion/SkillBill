# Boundary History Content

## Inputs Required

- Feature name
- Feature size (`SMALL` / `MEDIUM` / `LARGE`)
- Primary module/package/area (main boundary where the feature lives)
- Affected module/package/area list
- Feature flag name/pattern (or `N/A`)
- Acceptance criteria coverage (`implemented/total`)
- Change summary (what changed, patterns used, reusable components, breaking changes/limits)

## Input Recovery

- If the caller omits part of the context, derive only the missing pieces from the current diff and `.feature-specs/<ISSUE_KEY>-<feature-name>/spec.md` when available.
- Do not skip writing solely because the caller forgot to pass a change summary.

## Write/Skip Rules

- Always write for `MEDIUM` and `LARGE` features.
- For `SMALL`, write only if any applies:
  - Analytics events added/removed/changed (including properties).
  - API contracts or GraphQL schema usage changed.
  - UI behavior changed in ways that affect other features.
  - Breaking changes to shared interfaces/contracts.
- Skip only for trivial `SMALL` changes (pure bug fixes, cosmetic tweaks, isolated additions).

## Entry Format

```markdown
## [<date>] <feature-name>
Areas: <list of affected modules/packages/areas>
- <what changed> (1-2 lines each)
- <new patterns introduced or followed>
- <reusable components created> (mark with "reusable")
- <breaking changes or known limitations>
Feature flag: <name and pattern, or N/A>
Acceptance criteria: <count>/<count> implemented
```

## File Rules

- File path: `<primary-boundary>/agent/history.md`.
- **Forbidden — excluded roots:** never create `agent/` under `platform-packs/` or any other root the runtime's goal-planning discovery exclusion list denies. Planning discovery denies those roots, so history written there is unreadable memory. Write to the nearest non-excluded owning boundary instead.
- **Exception — skill source directories:** if the primary boundary is a skill source directory (`skills/<skill-name>/`), write to `skills/agent/history.md` instead. Skill source directories may contain only `content.md` and `native-agents/`; placing `agent/` inside them fails `validateAgentConfigs`.
- If the file does not exist, create it along with any missing parent directories.
- Newest entry first.
- Max 15 lines per entry.
- Entry body at most 4096 UTF-8 bytes, measured from the `## [<date>] <title>` heading to the next such heading (subheadings and undated `##` lines count as body). `BoundaryMemoryEntrySizeRepoTest` fails the build above that, and finding verification truncates longer bodies at the limit. Condense, or split into separate dated entries.
- No fixed entry cap.
- Keep older entries when they still provide reusable context; prune or merge only entries that are obsolete, redundant, or too noisy to help future feature work.
- No code snippets; focus on reusable context for future feature work.

## Output

Report one concise result:

- Written or skipped.
- Target file path.
- Top bullets included (if written).
