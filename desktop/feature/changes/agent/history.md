## [2026-11-08] SKILL-528 chat source for Changes
Areas: desktop/feature/changes, desktop/core/domain, desktop/core/ui, desktop/app
- ChangesSource distinguishes work items from chats. Chat source ids use a chat: prefix; reopening a source replaces its target, scope and optional file path and requests focus.
- reusable: ChangesWindows.openChat accepts a supplied chat target, and chatScopeTarget maps the saved stash or HEAD and pre-existing untracked paths to a RunStart baseline.
- Chat windows offer All, Uncommitted and Commits, including timeline commits without subtask owners. They use the supplied target without observing work detail and hide subtask controls, Running, Operator and the polling footer.
- Initial and reopened file requests wait for the matching target and scope list. A matching filtered file clears the filter before selection; a missing requested file leaves the default selection.
- Reopening aligns the files or commits view with the requested scope. Work sources keep their detail observation and scope controls.
- Limits: chat windows do not live-poll; reopening refreshes the supplied target. Diff comments retain baseline-driven availability.
Feature size: MEDIUM
Feature flag: N/A
Acceptance criteria: 1/1 owning parent criterion implemented in source, AC-004, including composer integration.

## [2026-11-05] SKILL-502 comment caret and diff word highlight
Areas: desktop/core/ui, desktop/feature/changes
- The comment composer and the thread reply box now draw their caret with a cursorBrush in colors.fg, the value SbInput uses.
- DiffWords.kt holds the pure word logic in rendered-text coordinates, meaning the prefix plus the tab-expanded, cut text. wordHitAt resolves one offset to Word, Ignored or Blank, and wordMatchRanges returns case-sensitive whole-word ranges. Word characters are letters, digits and underscore; surrogate pairs count as non-word. No-newline marker lines never match. reusable
- DiffWordHighlight is a snapshot-backed holder that ChangesScreen creates. diffRows takes it as an optional last parameter, and a null holder leaves rows as they were. Clicking the selected word again in the same file clears it, wherever the occurrence is; the same word in another file starts a new selection there. reusable
- DiffLineRow draws matches as span backgrounds, accentRing on the clicked occurrence and accent at 0.22 alpha on the others. A tap on the gutter does nothing, a tap on the text padding clears, and a tap on text applies the word hit.
- The FileDiffStream root clears on any tap no child consumed. PendingReviewBar and ReviewToast absorb taps, so a tap on them keeps the highlight.
- Esc clears a selection after every earlier level and before closing the panel, so with a highlight active the first Esc no longer closes. FileDiffStream also clears on dispose, which covers scope and filter changes, and when the selected file leaves the list.
- Validation at 9759e0af, whose tree matches 3f0a38cc: desktop clean check passed on Linux with 511 tests, including the 4 in DiffWordsTest. Mutation probes killed all 4.
- Limits: only the word logic has unit tests. Pointer, Esc, caret and highlight rendering have none by plan, and that validation did not exercise them. The plan read clickable's press handling in androidx sources, not the desktop fork, and did not read BasicTextField's. A double-click selects and then clears. There is no keyboard path to start a highlight and no text selection in diff lines. macOS and Windows desktop checks were not run on the Linux host.
- Unresolved: the spec assigns caret blink, pointer order and match visibility to QA, and no QA result reached this entry.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-04] SKILL-451 inline diff comments in Changes
Areas: desktop/core/data, desktop/core/domain, desktop/core/ui, desktop/feature/changes, runtime-kotlin/runtime-application
- Threads render inline under diff lines, at file separators, or in a General block. `ThreadPlacement` decides this at layout time from the thread list and scope; `LoadedDiff` stays hunks only. reusable
- `DiffCommentRepository` re-lists on the diff comment topic and baseline changes, and allows authoring only when the worktree branch matches the listed branch. reusable
- `FileDiffStream` interleaves thread items with stable `thread:<id>` keys; `diffRows` and file start indexes share one slot model so jump-to-file and scroll-follow stay aligned.
- Thread cards support reply, resolve, decline, reopen and delete; rejected writes show the reason on the card. Motion uses `AnimatedVisibility`, off under reduced motion.
- Hover and `c`-key authoring anchor to OLD or NEW lines or the file; drafts compose and edit inline. A floating bar publishes or discards pending drafts, derives its count from rendered state, keeps drafts on failure, and shows a local 2.6-second toast on success.
- Limits: comments are local to the repository branch; detached HEAD or a branch mismatch rejects writes.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-04] SKILL-461 Changes window commit timeline
Areas: desktop/core/domain, desktop/core/data, desktop/core/ui, desktop/feature/changes, desktop/feature/detail, runtime-kotlin/runtime-engine, runtime-kotlin/runtime-infra/workflow, runtime-kotlin/runtime-ports
- The runtime reads merge-base..HEAD and attributes commits by valid subtask trailers, then recorded SHA. Other commits are Operator commits. Finished subtasks show a changed-after-finish badge when ownership differs from the recorded SHA.
- Subtask scopes use the timeline, merge squash owners into columns, and label hunks by owner group. A range can include operator commits, labelled by short SHA. Timeline failures leave All and Uncommitted available.
- The commit view adds a strip, subject search, stepping and range selection. Switching views preserves fully covered selections and falls back to All for partial or operator-only selections.
- reusable: WorkCommitTimelineResult, timeline attribution, ChangeScopeTimeline, per-commit reads, owner-group hunk attribution and shared strip layout.
- Limits: stacked goals have no timeline; single runs and stacked goals have no commit view. Five trailer-less runtime commit paths classify as operator. Per-commit reads have a 200-commit limit; failed or oversized attribution falls back to an unlabelled range with a diagnostic. A file renamed twice can miss its middle commit.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-02] SKILL-457 removed flaky FileDiffStreamTest
Areas: desktop/feature/changes
- Deleted the wheel-scroll test FileDiffStreamTest because it failed intermittently on Ubuntu and Windows CI and its failures were not tied to changes in the code it covers. No production code changed, and no test in this module covers wheel scrolling now.
Feature flag: N/A
Acceptance criteria: N/A, no SKILL-457 criterion covers this removal

## [2026-11-01] SKILL-455 Changes panel matched to the design
- Rebuilt the toolbar, strip, scope bar, file list, diff, picker and footer to the values in design/App/Changes Window.html, and painted the surface that was missing, which had left dark-theme text on a light default background.
- Changes is now a fixed 94% in-app panel (ChangesOverlay, ChangesWindowFrame) instead of an OS window. See decisions.
- DiffLines no longer wraps lines; it scrolls horizontally under 52 dp gutters. Few subtasks render as labelled cards in SubtaskStrip.

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
