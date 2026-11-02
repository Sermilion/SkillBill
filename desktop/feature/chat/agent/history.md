## [2026-11-02] SKILL-467 new-task chip rebind and disabled why
Areas: desktop/feature/chat
- After a New task conversation has started, idle repository and agent chips stay openable. They stay disabled while a turn is running, Start goal run is in flight, or the draft's repository is missing. The SKILL-459 lock that disabled them whenever a chat existed is not restored.
- Changing repository, base branch, or agent ends the current agent process and opens a new session on that binding, keeping the transcript. Later turns and Start goal run / Plan only use the new checkout and agent. Discard and New chat are not required.
- Disabled chips drop the hand cursor and expose a chip-local why through SbTooltip and stateDescription, with busy then start-in-flight then missing-repository precedence. Shared AgentPicker gained optional disabledWhy; execution enablement stays unchanged. reusable
- Choosing another agent keeps the previous ChatAgent visible until the new one resolves, so the chip and Goal run offer do not flash No agent. Same-agent model and effort stay in Settings.
- Limits: base-branch rebind waits until the repository picker closes, even though the open popover writes the branch live. An enabled AgentPicker with an empty agent list is not a New task disabled-why.
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
