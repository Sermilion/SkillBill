## [2026-11-02] SKILL-466 chats know skill-bill vocabulary and launch from actions
Areas: desktop/feature/chat, desktop/core/data chat, control and session, desktop/core/domain, runtime-engine work/control, runtime-ports, runtime-infra launcher
- Both prompts share SHARED_START_ACTION_GUIDANCE: start wires goal, plan, review, validation, qa, pr, monitor, verify and pr-review-fix, with phrase mapping and Run phase:/Run operation: labels. Release has no start button. update-check, unit-test-value-check and the feature-guard operations stay ordinary chat turns.
- ChatSuggestedAction gains an optional target. decodeActions drops a non-string target and still drops an unknown start. ChatStore writes target only when set and loads older lines as null.
- New Task clicks pass wire and target into NewTaskStart. NewTaskKind adds Monitor, Verify and PrReviewFix, and shared toNewTaskKind maps action kinds. reusable. Home chips still pass a null target.
- RuntimeWorkControlRepository routes Monitor through startStandaloneTask, and Verify and PrReviewFix through startOperationTask. Phases settle the reservation and release it on exit. Operations release once started.
- Execution chats branch on action.start. A running execution or a database session that is not Open gets a Notice and no launch. Otherwise the run starts in that item's repositoryRoot with no Operator turn. A start button on a running execution reads Run phase:<id> with no pause badge.
- NeedsPrefix in an execution chat sets ChatSession.prefixChoice, and ChatComposer shows IssuePrefixPicker, extracted from NewTaskOfferCard. reusable. choosePrefix retries through the same running-execution refusal.
- Standalone starts post "Started <name>. Follow it in Work." in a New Task chat, or a toast when none is open. A later non-zero exit posts "<name> stopped." with the reason, through awaitFailure on NewTaskStartOutcome.Started.
- goalOfferStandsAside hides the Goal run card when the latest reply offers a standalone start or the latest operator message asks for a release. A pending prefix choice or goal problem keeps it. RoutePreview adds conversational openers, "code review" and a Release route. Standalone refusals go into the chat as notices.
- Limits: goal and plan starts get no exit signal, so a plan that blocks before the board may hold its key. A cancelled run that exits non-zero also posts "stopped". A notice added after a turn ends is saved only at the next turn or flush. previewRoute is still a guess. No auto-confirm for verify or pr-review-fix. Validate moved log reading into the launcher after QA settled.
- Unresolved: a start refused while the execution runs says it waits, then never launches; the refused button stays grey and a second click does nothing (QA-10, open after 3 qa_fix attempts). Asking again yields a fresh button that works. Model phrase picking still needs the agreed manual try. No test covers the repository call to releaseOnExit or renders the prefix picker in the composer.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

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
