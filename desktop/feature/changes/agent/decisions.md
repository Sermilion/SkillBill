## [2026-11-01] Show Changes as a fixed in-app panel, not an OS window
Context: The operator wants Changes to look like a window while staying inside the app, unmovable, with the main window visible around it.
Decision: `ChangesOverlay` shows `ChangesWindowFrame` in a Compose `Dialog` at 94% of the main window's width and height, over the shell scrim. `ChangesWindows` holds one current entry; opening another execution replaces it. The main window's `onPreviewKeyEvent` routes every key to `ChangesWindowKeys` while the panel is open, so no shell shortcut fires under it. Esc closes the picker, then leaves the filter, then closes the panel; the close button and a click on the scrim also close it.
Reason: A modal panel keeps the operator's place in the app, and a single entry matches a modal: two cannot be visible at once. `Dialog` already traps focus away from the shell.
Supersedes: "Route Changes window keys through the window's onPreviewKeyEvent", "Open Changes windows through a core/ui interface and an app-wide registry" (the core/ui interface stays; the per-execution OS windows do not).

## [2026-11-01] Show labelled cards when subtasks have room
Context: `design/App/Changes Window.html` is drawn for 33 subtasks. Bars for 1 to 5 subtasks are wide blocks that say little.
Decision: `SubtaskStrip` draws a card per subtask (state, number, name, files, +N/-N) when the width gives each at least 140 dp, capped at 320 dp wide, and bars otherwise. Cards keep the strip's height and its click, Shift-click and drag gestures. The detail screen's running row shows under bars only, since a card already names its subtask.
Reason: The switch follows available width, so the same run reads well in both the narrow detail column and the wide panel.
Revisit when: A designed few-subtask state replaces this one.

## [2026-11-01] Keep Uncommitted and Outside observed for quick-button counts
Context: The quick buttons show file counts for Uncommitted and Outside subtasks whatever the current scope is, and a button with zero files is disabled.
Decision: ChangesWindowViewModel observes Uncommitted, and Outside when availableScopes offers it, for the window's lifetime, keyed on target and running. When the shown scope is one of those two, the shown list reuses that read instead of starting a second observation.
Reason: Counts stay current without polling the same scope twice through the shared git lock.

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
