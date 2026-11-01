## [2026-11-01] SKILL-455 changes window scope selection, keys and detail strip
Areas: desktop/core/ui, desktop/feature/changes, desktop/feature/detail, desktop/app
- core/ui gains SubtaskStrip and ScopeStateDot. reusable One column per subtask (2 dp minimum, scrolls only on overflow); NOT_STARTED gets a dashed outline, other bars are max(6%, sqrt(lines / maxLines)) tall. One awaitEachGesture handles press, Shift and drag and ignores non-selectable columns. subtaskBarFraction and subtaskTooltip are pure.
- ScopeSelection.kt holds dragRange, stepTarget, commitRangeText, filterSubtasks and quickButtons. Every range goes through normalizeRange or dragRange, so state never holds a non-contiguous selection.
- The window adds the strip, a Scope picker, quick buttons (All, Running, Outside subtasks, Uncommitted) and a scope bar with the commit range, step buttons and Show all. Strip, picker and Running are hidden for targets without Subtasks, including STACKED_BRANCHES goals.
- ChangesWindowViewModel always observes Uncommitted, and Outside when offered, for quick-button counts, and reuses those reads when the shown scope is one of them. It now takes WorkChangesRepository for opening files.
- Keys s, [, ], a, j, k, /, o and Esc go through Window onPreviewKeyEvent via a per-window ChangesWindowKeys holder; a focused filter field gets every key except Esc. o and the diff Open button skip Deleted files.
- Detail: goals observe WorkChangeScopesRepository.observeSubtasks instead of WorkChangesRepository.observe and show a subtask count, the shared strip without drag, a running row and Open changes. A column or running-row click opens the window on Subtasks(n, n) only when n is selectable. Non-goals show a file and line summary with the button; NoSubtask shows "No change baseline recorded".
- Removed the SKILL-454 in-detail diff: DetailDiff.kt, DiffView.kt, DiffUi, DiffContent, ChangedFile.label() and the SelectChangedFile, OpenChangedFile and CloseDiff actions. OpenChangesWindow and its effect carry a scope (default All).
- Deviations: Running always shows when Subtasks exist and is disabled when none runs. a selects All even while the All button is disabled.
- Limits: no arrow keys in the picker; its 32 dp offset is a visual guess; composables have no UI tests, so geometry, pointer and focus rest on reading and a manual check. Only the Linux CI leg ran locally.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-01] SKILL-455 changes window with file list and diff
Areas: desktop/feature/changes, desktop/core/ui, desktop/feature/detail, desktop/app, desktop/settings.gradle.kts
- New :feature:changes module. ChangesWindows in core/ui (windows, open, close) is bound by the AppScope ChangesWindowRegistry. open appends an entry for a new work id; a known id replaces item and scope and bumps focusRequest. reusable
- Application.kt renders one ChangesWindowHost per entry beside MainWindow, keyed by work id. The host owns a ViewModelStore cleared when the window leaves composition, defaults to 1200x800 dp (minimum 720x480), and brings the window to front on each focusRequest. Hiding the main window to the tray leaves Changes windows open.
- ChangesWindowViewModel comes from an assisted (WorkItem, ChangeScope) factory on AppComponent and builds the ChangeScopeTarget from WorkDetailRepository and observeSubtasks. A scope the target does not offer falls back to All. A reopen applies the new scope through requestScope, so filter and selection survive. A Gone detail stops change reads and says so.
- File list: case-insensitive path filter, module groups (segments before src, else parent directory, else (root)) with summed counts, rows keyed by path and status, and an xN badge when two or more in-scope subtasks touched the file. Selection stays on a visible path, else the first visible file. Refreshes keep the old list until the new read lands.
- The diff pane reads fileDiff(target, scope, file) on selection or list change and drops stale results. Goal diffs show subtask chips; a chip click narrows to Subtasks(n, n) and keeps the file selected.
- DiffLines and fileDiffNotice moved to core/ui/diff and draw DiffHunk.source at the end of the hunk header row. reusable
- Detail emits DetailEffect.OpenChangesWindow, and DetailFeatureEntry calls ChangesWindows.open.
- Limits: open windows live in memory only and do not reopen after a restart. No UI or screenshot test covers the host, list or diff pane; tests cover the registry, grouping, the ViewModel and the detail effect.
Feature flag: N/A
Acceptance criteria: 4/4 implemented
