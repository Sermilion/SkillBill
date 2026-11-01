## [2026-11-01] SKILL-456 subtask inspector review and audit sections
Areas: desktop/feature/detail, desktop/core/data, desktop/core/domain, desktop/core/designsystem, runtime-domain, runtime-engine, runtime-goalrunner
- The selected subtask inspector shows review passes, findings, running lane dispositions, audit criteria and attempts, and execution and run counts scoped to its workflow.
- Read-only status projections load durable results and report the actual degraded source. Page merges retain the latest result per subtask; missing goal status shows an unreadable state.
- Audit attempt projection derives settled attempts from repair state. The dashed label border supports unfinished review lanes. reusable
- Running review progress comes from persisted dispositions, so a first attempt can show zero completed lanes until the batch is stored. Live lane progress is outside this change.
- Panels omit empty execution rows and sections. Liveness appears only for the current subtask. Goal finish and standalone runs keep goal-level values.
- Live goal reviews run pass 1 only. The projection accepts later pass numbers from stored records, and this change does not alter review execution.
- A non-current started subtask with a blank workflow id can remain loading; a raw review decode failure can prevent pass recovery. Audit attempt copy can name audit_plan_fix before a focused re-audit.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-01] SKILL-455 recorded Activity timeline
Areas: desktop/feature/detail, desktop/core/domain, desktop/core/data
- Replaced ActivityFeed with subtask attempt cards, day labels, a phase-time summary, and a timeline that compresses idle gaps.
- Cards preserve expansion choices. Blocks opens matching cards. Loop rows and long text expand independently, and timeline segments scroll to their card.
- Goals observe a session-owned runtime timeline while running and read once when stopped. Standalone execution rows use recorded run times and results.
- Goal finish shows untimed assessment facts and the latest update separately from phase totals. Card metadata lists agents. Model labels appear only where recorded for the attempt.
- Removed the unused ActivityFeed component and replaced its block-line regression test with timeline coverage.
- Idle totals use phase spans. Block notices alone never imply idle time. Interaction coverage checks Blocks filtering, card collapse, and timeline navigation.

## [2026-10-31] SKILL-454 desktop changes panel and diff view
Areas: desktop/feature/detail, desktop/core/designsystem
- Detail shows a Changes section after the pipeline section, fed by WorkChangesRepository. DetailViewModel keys observation on the loaded detail's change baseline and the header's running flag, so a baseline or running change re-observes and an idle item stops polling. Only the open item's worktree is read.
- Re-observing the same baseline keeps the shown list instead of showing Loading again. Every ChangesState (loading, empty, no subtask, base unavailable, not a repository, read problem) renders text inside the section without hiding the rest of the detail.
- A single click shows a read-only unified diff in place of the document column; a double click opens the file with the system opener and shows a problem toast on failure. Deleted rows get no double-click handler, and the ViewModel ignores an open for them.
- DetailDiff, shaped like DetailControls, owns the open diff: one read at a time, and a result applies only to the file and base SHA it was read for. A refreshed list re-reads a diff whose row or base SHA changed and keeps the old lines until the new read lands. A file that leaves the list shows "This file no longer has changes".
- Change actions carry the ChangedFile, and rows match by path and status because git can list one path as both deleted and untracked. Row keys include the status.
- DiffView renders hunk separators and lines with old and new number gutters in a keyed LazyColumn, uses only theme tokens, and closes on Esc through ShellEdit or the back button. J/K keep paging items, and the next item opens with no diff.
- SkillBillColors gains diffAddedBg, diffRemovedBg and diffHunkBg for later diff surfaces and palettes. reusable
- Limits: no syntax highlighting, editing, staging or external editor setting; long paths end-ellipsize.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-30] SKILL-447 desktop detail
Areas: desktop/feature/detail, desktop/core/ui, desktop/app
- Detail renders its initial header from the route, observes runtime sections, pages each list by its runtime cursor and retains stale sections after refresh failure.
- Standalone run detail shows saved phase launch briefings in pipeline order when available.
- Detail section collapse state persists while paging between items, and goal finish links navigate to section headers.
- Controls use typed outcomes and reservations; incident recovery checks prerequisites and preview identity. Operator decisions also appear for goals without incidents.
- RepositoryWebPages is reusable and reads git configuration without launching git. URL insteadOf rewrites are not resolved.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

