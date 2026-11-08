## [2026-11-08] Allow chat commit scopes without subtask attribution
Context: New Task chats supply timeline commits with empty ownerIds and have no subtasks or subtask ranges.
Decision: Mark chat targets explicitly and offer All, Uncommitted and Commits through availableScopes. Reuse normalizeCommits and resolveScope for their timeline selections.
Reason: The existing subtask-range gate would hide Commits for chats. Chat commit selection depends on membership in the supplied timeline, while All reads the captured RunStart baseline.

## [2026-11-05] Keep the word highlight in its own holder and read it in the row
Context: Highlighting must work when canAuthor is false, and a click should not recompose the whole diff stream.
Decision: ChangesScreen owns a dedicated DiffWordHighlight instead of adding a field to DiffAuthoringInput. DiffLineRow reads the selection itself, and FileDiffStream never reads it during composition.
Reason: DiffAuthoringInput is about authoring, which highlighting does not depend on. Reading the selection only in rows means a click recomposes rows and not the LazyColumn content.

## [2026-11-05] Identify the clicked occurrence by line address and start offset
Context: Identical diff lines compare equal, and a refresh can move the selected word.
Decision: The strong style applies only to the range whose start matches the selection, on the row whose DiffLineAddress matches. If a refresh moved the word, no range is strong and the matches still show.
Reason: DiffLine equality cannot tell two identical lines apart.

## [2026-11-05] Do not match or click words at the diff line cut
Context: Lines over 5,000 tab-expanded characters render cut and followed by a marker.
Decision: Word finding scans only the text before the cut and reads the next character to judge the right boundary. A word crossing the cut is neither clickable nor a match, a word ending exactly at 5,000 is whole, and the marker is never searched.
Reason: Marking hidden or partly hidden text would highlight words the operator cannot see in full.

## [2026-11-05] Clear a word highlight the operator can no longer see
Context: Esc now clears an active selection before it closes the panel, so a selection that is off screen for good would swallow an Esc.
Decision: FileDiffStream clears the holder on dispose, which the scope and filter key triggers, and when the selected file leaves the list. A refresh that keeps the file needs no handling, because rows recompute their matches.
Reason: Without this, Esc would appear to do nothing after a scope change, a filter change or a dropped file.

## [2026-11-05] Mark other word matches with accent at 0.22 alpha
Context: accentTint at alpha 0.12 is fainter than the 0.15 diff row backgrounds.
Decision: Unclicked matches use accent at 0.22 alpha and the clicked occurrence uses accentRing. No theme field is added.
Reason: Matches have to stay visible over added and removed rows, and the clicked occurrence has to stay stronger.
Revisit when: Matches prove hard to see over diffAddedBg or diffRemovedBg. The spec names 0.22 as a starting value.

## [2026-11-04] Place diff comment threads at layout time
Context: A thread can anchor to a line, file separator, or General block depending on its path, side, line, outdated state, and scope.
Decision: `ThreadPlacement` runs while `FileDiffStream` renders from the current thread list; `LoadedDiff` remains hunks only.
Reason: Cached diffs move across scopes and baselines, so storing threads in `LoadedDiff` would reload files when comments or scope change.

## [2026-11-04] Keep interleaved diff row counts aligned
Context: Inline threads add LazyColumn items, while jump-to-file and visible-row tracking use file indexes and row counts.
Decision: `diffRows` and `FileDiffStream` use the same per-line thread slots; file indexes account for General and file thread rows.
Reason: A separate scroll row model let file positions disagree with the rendered list.

## [2026-11-04] Animate thread cards with visibility transitions
Context: The approved mock calls for fade-and-slide motion, while the current Compose Desktop version lacks `Modifier.animateItem`.
Decision: Use `AnimatedVisibility` with fade and vertical expansion, disabled when reduced motion is enabled.
Reason: This provides the requested motion without a Compose upgrade.

## [2026-11-04] Attribute timeline commits by trailer then recorded SHA
Context: Recorded subtask ranges can point to pruned commits after an amend or rebase and can credit operator work to a subtask.
Decision: Attribute merge-base..HEAD commits to valid goal subtask trailers, then match recorded SHA only when no valid trailer exists. Other commits are operator commits.
Reason: The manifest supplies subtask names and statuses without determining ownership by range. The five trailer-less runtime commit paths remain operator commits to keep this change out of those paths.

## [2026-11-04] Withhold attribution outside the goal branch
Context: The timeline reads the current checkout, which can be on a different branch or detached.
Decision: When a non-blank goal branch differs from the checkout, report OnOtherBranch and hide subtask scopes and Operator. All and Uncommitted continue to read HEAD.
Reason: Attributing another branch would show false changed-after-finish badges.

## [2026-11-04] Keep stacked goals on the child review base
Context: Stacked-branch goals have no subtask ranges; the approved scope leaves them that way.
Decision: Skip their timeline and retain the child review base for stacked reads, including completed and no-change work.
Reason: The approved scope excludes a stacked timeline, subtask ranges and commit view.

## [2026-11-04] Expand non-adjacent squash members at read time
Context: A squash can join subtasks 1 and 3 while subtask 2 remains separate, but ChangeScope.Subtasks stores a contiguous range.
Decision: Store the lowest id for a non-adjacent squash pick and expand touched columns to their members when reading.
Reason: Storing 1..3 would also select subtask 2.

## [2026-11-04] Attribute hunks by owner group
Context: A squash can have multiple subtask owners, and a baseline read can span later operator commits.
Decision: Attribute patches by commit owner set or operator SHA. If a group patch fails or exceeds the per-commit limit, return the unlabelled range and record a diagnostic.
Reason: Exclusive matching per subtask cannot label a squash. Including later operator commits preserves their hunks, while the fallback keeps the range readable.

## [2026-11-04] Keep Uncommitted and Operator observed for quick-button counts
Context: Quick buttons show file counts regardless of the selected scope.
Decision: Observe Uncommitted and, when offered, Operator for the window's lifetime. Reuse the observation when its scope is selected.
Reason: Counts stay current without polling the same scope twice through the shared git lock.

## [2026-11-04] Retain commit statistics across timeline refreshes
Context: Commit statistics are immutable for an unchanged commit SHA, while the goal timeline can refresh during the view.
Decision: Keep observed statistics for SHAs that remain on the refreshed timeline and merge each new per-commit emission into that map.
Reason: The strip keeps its bars while the repository reads the next commit.

## [2026-11-04] Validate subtask view switches after range normalization
Context: Normalizing a selected subtask range can expand it to include squash partners.
Decision: After mapping selected commits to covered subtasks, normalize the range and require every selectable id in the expanded range to be covered by the selected commits.
Reason: The subtask view must not display commits the commit view did not select.

## [2026-11-01] Show the scope's diffs as one continuous stream
Context: The operator wants to keep scrolling from the end of one file into the next instead of picking each file.
Decision: The diff pane is one LazyColumn over every visible file. The view model keeps loaded diffs per path for the scope and reads what the stream asks for, the files on screen plus two ahead, one at a time. A user scroll selects the file at the top; a file click, j and k jump to it. Scrolling never bumps `scrollRequest`, so the two cannot fight. A refreshed list drops only diffs whose ChangedFile changed.
Reason: Reading on demand keeps large scopes cheap, since All can list hundreds of files, and each read still goes through the shared git lock.
Revisit when: Long sessions over large scopes hold too much diff text; loaded diffs are kept until the scope changes.

## [2026-11-01] Show Changes as a fixed in-app panel, not an OS window
Context: The operator wants Changes to look like a window while staying inside the app, unmovable, with the main window visible around it.
Decision: `ChangesOverlay` shows `ChangesWindowFrame` in a Compose `Dialog` at 94% of the main window's width and height, over the shell scrim. `ChangesWindows` holds one current entry; opening another execution replaces it. The main window's `onPreviewKeyEvent` routes every key to `ChangesWindowKeys` while the panel is open, so no shell shortcut fires under it. Esc closes the picker, then leaves the filter, then closes the panel; the close button and a click on the scrim also close it. The title bar has no decorative window lights; the close button sits left on macOS and right elsewhere.
Reason: A modal panel keeps the operator's place in the app, and a single entry matches a modal: two cannot be visible at once. `Dialog` already traps focus away from the shell.
Supersedes: "Route Changes window keys through the window's onPreviewKeyEvent", "Open Changes windows through a core/ui interface and an app-wide registry" (the core/ui interface stays; the per-execution OS windows do not).

## [2026-11-01] Cut diff lines at 5,000 characters
Context: Unwrapped diff rows are sized to the longest line. A 327 KB line asked Compose for a row 3.6 million pixels wide, past what Constraints can represent, and the app showed an error dialog.
Decision: DiffLines shows at most 5,000 characters of a line, then "… N more characters". The full file stays reachable through Open in editor.
Reason: No one reads a line that long in a diff row, and the cap keeps the row inside the layout limit at any UI scale.

## [2026-11-01] Show labelled cards when subtasks have room
Context: `design/App/Changes Window.html` is drawn for 33 subtasks. Bars for 1 to 5 subtasks are wide blocks that say little.
Decision: `SubtaskStrip` draws a card per subtask (state, number, name, files, +N/-N) when the width gives each at least 140 dp, capped at 320 dp wide, and bars otherwise. Cards keep the strip's height and its click, Shift-click and drag gestures. The detail screen's running row shows under bars only, since a card already names its subtask.
Reason: The switch follows available width, so the same run reads well in both the narrow detail column and the wide panel.
Revisit when: A designed few-subtask state replaces this one.

## [2026-11-01] Build the Scope picker on a focusable Popup instead of SbPopover
Context: The picker lists every subtask of a goal, which can run to 100 or more rows, and its search field must take focus on open.
Decision: ScopePicker uses a focusable Popup that dismisses on outside click and back press, with a LazyColumn, anchored 32 dp under the picker button.
Reason: SbPopover wraps content in a vertical scroll, which cannot host a lazy list. A focusable popup also gets its own key events, so Esc closes it through dismissOnBackPress.
Revisit when: SbPopover supports lazy content.

## [2026-11-01] Route Changes window keys through the window's onPreviewKeyEvent
Context: The Changes window binds single-letter keys (s, [, ], a, j, k, /, o) that must work with no focused node, while the filter field and picker search must still receive typed text.
Decision: ChangesWindowHost passes ChangesWindowKeys.handle to Window(onPreviewKeyEvent), and ChangesScreen installs the handler while shown. The handler lets every key except Esc through to a focused filter field; Esc closes the picker and clears focus.
Reason: A modifier key handler inside the content only fires when a node in that subtree has focus, which the window lacks right after opening. A holder per window keeps two open Changes windows from sharing a handler.

## [2026-11-01] Open Changes windows through a core/ui interface and an app-wide registry
Context: The detail screen must open a separate Changes window owned by :feature:changes, and ModuleGraphRules gives a feature module production edges only to core modules.
Decision: ChangesWindows lives in core/ui, and :feature:changes binds it with ChangesWindowRegistry in AppScope. Detail emits an effect that DetailFeatureEntry turns into ChangesWindows.open, and Application.kt renders one window per registry entry beside MainWindow.
Reason: Detail cannot depend on :feature:changes. A registry outside MainWindow keeps windows open when the main window hides to the tray, and a known work id reuses its window instead of adding a second one.
Revisit when: Open windows need to survive an app restart, or a database session switch should close them.
