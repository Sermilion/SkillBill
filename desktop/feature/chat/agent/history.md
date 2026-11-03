## [2026-11-03] SKILL-450 Paste images in the chat composer
Areas: desktop/feature/chat
- Cmd/Ctrl+V in ChatComposer reads the clipboard through ClipboardImageReader (default SystemClipboardImageReader). The key is consumed only when the clipboard holds an image and no text; otherwise normal text paste runs. The image is scaled to a 1568 px longest edge and encoded as PNG; over 3,750,000 bytes it is re-encoded as JPEG at 0.85 on white. Encoding runs off the UI thread; a failure adds no chip.
- ChatWorkspace keeps pastedImages per chat. The composer shows an [Image #N] chip per image with a Remove image N button; removing returns focus to the input. Pending images clear on close, closeExecutionChats, clear and submit. Send is enabled by text or images. An image finishing after its chat closed is dropped.
- send and pauseAndAct take images. QueuedChatMessage and ChatMessage.Operator carry them, and drainQueue passes them to ChatTurnRequest.images. An image-only prompt has no trailing blank line. Suggested-action messages carry no images.
- Operator bubbles show [Image #N] preview chips; clicking opens a popover with the image fitted into 320x240 dp, or "Preview unavailable" if it cannot decode.
- Limits: images live only in the session and are not written to chat history, so a restored Operator message has no chips and an image-only message saves as a blank-text line. Only the clipboard path exists; no file drop or picker. No test covers chip styling or the popover. QA reported capability_absent.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-03] SKILL-471 Preserve suggested start input
Areas: desktop/feature/chat
- Suggested goal and plan clicks carry the selected action through the screen host to NewTaskStart, using its instruction and exact nullable title instead of transcript-derived values.
- A pending prefix choice retains that action for retry. A new turn or launch clears or replaces it; Goal run card starts and retries use transcript-derived requirements and title.
- Other start kinds retain target routing, home chips pass no action, and existing start guards and launch context remain in place.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-11-03] SKILL-521 Goal run card is contextual
Areas: desktop/feature/chat
- showGoalOfferCard replaces first-turn-plus-stands-aside. A finished first turn is not enough. The card appears when the operator asks to run a goal or plan, the latest reply offers start:goal or start:plan, or a prefix choice, start problem or started confirmation is pending.
- A question about an issue, a feature description, and a review, validate, qa, pr or release request keep it hidden. An earlier goal-run ask stays through clarification until one of those standalone routes takes over.
- asksToRunGoal matches "goal run", start/run … goal, plan-only preview phrasing, and "implement/ship/build this". It does not treat every Goal preview as a start, so "Add a release button" stays hidden.
Feature flag: N/A

## [2026-11-02] SKILL-466 chats know skill-bill vocabulary and launch from actions
Areas: desktop/feature/chat, desktop/core/data chat, control and session, desktop/core/domain, runtime-engine work/control, runtime-ports, runtime-infra launcher
- Both prompts share SHARED_START_ACTION_GUIDANCE: start wires goal, plan, review, validation, qa, pr, monitor, verify and pr-review-fix, with phrase mapping and Run phase:/Run operation: labels. Release has no start button. update-check, unit-test-value-check and the feature-guard operations stay ordinary chat turns.
- ChatSuggestedAction carries optional title for goal and plan starts and optional target for monitor, verify, and pr-review-fix. decodeActions validates both fields; ChatStore round-trips them and loads older lines without those keys as null.
- New Task clicks pass title from agent actions and target from the button into NewTaskStart. NewTaskKind adds Monitor, Verify and PrReviewFix, and shared toNewTaskKind maps action kinds. reusable. Home chips still pass a null target.
- RuntimeWorkControlRepository routes Monitor through startStandaloneTask, and Verify and PrReviewFix through startOperationTask. Phases settle the reservation and release it on exit. Operations release once started.
- Execution chats branch on action.start. A running execution or a database session that is not Open gets a Notice and no launch. Otherwise the run starts in that item's repositoryRoot with no Operator turn. A start button on a running execution reads Run phase:<id> with no pause badge.
- NeedsPrefix in an execution chat sets ChatSession.prefixChoice, and ChatComposer shows IssuePrefixPicker, extracted from NewTaskOfferCard. reusable. choosePrefix retries through the same running-execution refusal.
- Standalone starts post "Started <name>. Follow it in Work." in a New Task chat, or a toast when none is open. A later non-zero exit posts "<name> stopped." with the reason, through awaitFailure on NewTaskStartOutcome.Started.
- goalOfferStandsAside hides the Goal run card when the latest reply offers a standalone start or the latest operator message asks for a release. A pending prefix choice or goal problem keeps it. RoutePreview adds conversational openers, "code review" and a Release route. Standalone refusals go into the chat as notices.
- Limits: goal and plan starts get no exit signal, so a plan that blocks before the board may hold its key. A cancelled run that exits non-zero also posts "stopped". A notice added after a turn ends is saved only at the next turn or flush. previewRoute is still a guess. No auto-confirm for verify or pr-review-fix. Validate moved log reading into the launcher after QA settled.
- Unresolved: a start refused while the execution runs says it waits, then never launches; the refused button stays grey and a second click does nothing (QA-10, open after 3 qa_fix attempts). Asking again yields a fresh button that works. Model phrase picking still needs the agreed manual try. No test covers the repository call to releaseOnExit or renders the prefix picker in the composer.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-02] SKILL-467 new-task chip rebind and disabled why
Areas: desktop/feature/chat
- After a New task conversation has started, idle repository and agent chips stay openable. They stay disabled while a turn is running, Start goal run is in flight, or the draft's repository is missing. The SKILL-459 lock that disabled them whenever a chat existed is not restored.
- Changing repository, base branch, or agent ends the current agent process and opens a new session on that binding, keeping the transcript. Later turns and Start goal run / Plan only use the new checkout and agent. Discard and New chat are not required.
- Disabled chips drop the hand cursor and expose a chip-local why through SbTooltip and stateDescription, with busy then start-in-flight then missing-repository precedence. Shared AgentPicker gained optional disabledWhy; execution enablement stays unchanged. reusable
- Choosing another agent keeps the previous ChatAgent visible until the new one resolves, so the chip and Goal run offer do not flash No agent. Same-agent model and effort stay in Settings.
- Limits: base-branch rebind waits until the repository picker closes, even though the open popover writes the branch live. An enabled AgentPicker with an empty agent list is not a New task disabled-why.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

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
