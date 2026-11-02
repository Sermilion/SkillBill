## [2026-11-02] SKILL-465 keep New task chat after start
Areas: desktop/feature/chat
- A successful Goal run or Plan only start no longer discards the chat, reversing the SKILL-459 start path. The same NewTaskChat stays open with its agent process, typed text and saved draft. Discard is the only production path that deletes a saved draft.
- NewTaskOffer.started holds the confirmation on the chat that requested the start. Repeat Goal run and Plan only starts return before startNewTask while it is set. Review, Validation, QA and PR starts still go through and do not clear it.
- The offer card disables Start goal run, Plan only and prefix controls while started is set, and shows a success callout that says to send another message to start again. An admitted turn, New chat, Discard and dispose clear the guard.
- Limits: the guard is memory-only, so a resumed draft can start again. keyChoice is not cleared, so a later prefix panel can show the old key. Chat turns during a run are not gated. Sidebar attention is unchanged because the chat stays open.
- Unresolved: the chat stays in Act mode while a goal runs, and the spec left unverified whether a goal isolates its work in a worktree, so a chat turn may edit the same checkout during a run. A QA or validation warning recorded environment_unavailable, so this entry claims no proof from that step.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-02] SKILL-459 new-task screen and chat
Areas: desktop/feature/chat, desktop/core/domain, desktop/core/data, desktop/core/ui, desktop/app
- NewTaskFeatureEntry in the newtask package claims NewTaskRoute through renders and is bound in WindowScope, as ChatFeatureEntry is. No Gradle module was added and AppNavHost did not change.
- NewTaskDraft is the window-scoped holder of text, repository, base branch, agent and chat. ChatThread shares transcript state and rendering across execution and new-task chats, and ChatScope picks the prompt. AgentPicker in ChatComposer is internal and NewTaskComposer reuses it. reusable
- ChatStore persists new-task context, transcript and session state, and resumes by repository. Discard deletes the saved draft; New chat and window close flush the transcript and keep it. A goal or plan start from a chat discards it, while a home chip start leaves the input text alone.
- previewRoute is pure and anchored to the start of the text, so "Add a review screen" stays Goal. Only the step line and caption read it; no start path may branch on it.
- newTaskHome maps Loading and Failed to Pending, no rows with counts.running at 0 to FirstRun, and anything else to Returning. ShellBoard.query uses HistoryWindow.Day, so a returning operator with nothing in the last day gets first-run content.
- Needs you rows are local rather than ListRow, because ListRow puts the id before the status icon. Ages reuse savedAgo because FinishedTime is internal to :feature:work and ModuleGraphRulesTest forbids that edge.
- StepLine reads progress only in the Canvas draw lambda, so the animation redraws without recomposing the screen. UiTicker's 1s tick is too coarse for it.
- Limits: a slow projects read keeps the home Pending, because the board observation starts after it returns. There is no default-branch read, so the branch control reads default branch until the operator types one. No single test runs Esc then a chord across :app and :feature:chat, and no Compose test covers layout, the pinned composer or the moving dot.
- Unresolved: SbInput takes a String value, so the cursor may sit at the start of the text after an example fill. Main.dc.html is not in the repo, so the caption's 12dp gap and start alignment and the 240dp row title width were not checked against the design.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
