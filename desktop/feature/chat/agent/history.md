## [2026-11-10] SKILL-535 — Continue chats after checkout removal
Areas: desktop/feature/chat production and JVM tests
- Select the existing checkout when present, then the captured primary root, then the legacy repository root for turns, saved-chat listing, and resume.
- Under the session move lock, relocate eligible removed Goal, Phase, and Operation chats; retain settled outcomes and allow retry after cancellation.
- Successful Claude/Codex moves may resume the saved session; failed moves and other providers start fresh at the selected root. Resume preserves one-time removal notices and goal-only notice order.
- Reuse `ExecutionChatRepository.moveSessions`; keep relocation outcome and notices in chat-session state. Notices identify Verify/phase/operation runs and the destination.
- Limits: deleted historical checkouts without a distinct captured root keep the legacy fallback; no root discovery, provider format, or branch-switch behavior was added.
Feature flag: N/A
Acceptance criteria: 2/2 implemented in this boundary

## [2026-11-08] SKILL-528 chat changes indicator
Areas: desktop/feature/chat/newtask, desktop/core/domain, desktop/core/data, desktop/core/testing, desktop/core/designsystem, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra/workflow, runtime-kotlin/runtime-core
- New Task captures HEAD, pre-existing untracked paths, an optional unreferenced stash SHA and capture time before its first agent turn. Saved drafts retain the baseline and capture-attempt state; older started chats load without a baseline, and malformed baseline data records a diagnostic without losing the transcript.
- Same-repository agent rebinds keep the original baseline and attempt state. Repository switches drop them. Failed capture is not retried after chat work starts.
- reusable: WorkTreeSnapshotPort and GitWorkTreeSnapshotAdapter capture without moving refs; WorkChangesPort remains read-only. ChatChangesRepository and ChatChangeReads combine baseline diffs, commit timeline and current-HEAD reads under GitReadLock on IO.
- Reads refresh after turns and on baseline restore, sort files by path and total available line counts. Cancelled or replaced read jobs cannot publish stale state. Empty or unavailable results hide the pill; unavailable reads record diagnostics.
- The composer places the non-compressing file-count and line-total pill before Stop and Send. Its end-aligned popup lists six files at most, a more-files action, baseline time, commit and uncommitted-edit text, and a resumed-chat note.
- Popup actions close it and open the chat Changes window in All scope, optionally selecting a file. Chat-keyed popup state returns focus to the pill or to the composer if the pill disappears.
- reusable: SbPopover.AboveEnd mirrors end alignment with clamping and below fallback; SbPickerButton accepts a chevron override. Non-system toasts gain Dismiss while retaining their five-second lifetime.
- Limits: an unreferenced stash can be pruned. A missing stash hides changes without falling back to HEAD. There is no mid-turn polling. Visual parity remains unconfirmed.
Feature size: MEDIUM
Feature flag: N/A
Acceptance criteria: 5/5 implemented in source across the feature; visual acceptance remains unconfirmed.

## [2026-11-07] Paste images in New Task and run chats
Areas: desktop/feature/chat production and JVM tests
- Both composers use the same Cmd/Ctrl+V image handler. Text paste still reaches the text field, and image encoding runs off the UI thread.
- New Task drafts hold pending images and send them through the queue, transcript and agent request, including image-only messages. Saved drafts retain image labels without persisting image bytes.
- Pending image chips open previews. Escape closes a preview and restores focus to its chip; removing the chip returns focus to the input.
- Pending images keep Queue available instead of Send now. Stop returns queued images to the draft in both chat types.

## [2026-11-07] SKILL-515 match New Task tabs and home
Areas: desktop/feature/chat newtask production and JVM tests
- The 36dp strip has 8dp horizontal and 4dp top padding. Selected tabs join the panel over the hairline with rounded tops, square bottoms and a 1dp top-and-side border. Unselected hover fill stops 1dp above the hairline; offscreen DstIn masks fade tab content separately.
- Explicit tab-container width puts New chat 2dp after fitting tabs. Overflow reserves the spacer's extra 2dp row gap to keep the chevron at the right edge. Existing markers, scrolling, hidden-activity indicator, overflow filtering and shortcuts remain.
- The repaired title fills the space before the 20dp close control, keeping its trailing edge 4dp inside selected and hovered tabs. Its bounds test selects the original draft after newChat selects another, and measures the close control through unmerged semantics.
- Recent chats precedes Needs you, replacing SKILL-512's merged inbox. It uses resumableDrafts for the selected repository, newest first, and hides without matches or a repository. Search chats opens the same scoped resume palette. Home width stays 720dp; block spacing is 32dp with sections and 40dp without.
- Returning homes show every blocked run under Needs you, or All clear without a card. Running and finished-today counts link to all work. InboxItem, day groups, filters, the hint row, six-chat cap, runningItems and digit shortcuts are removed; rows retain their 34dp height, title helpers, blocked styling and 52dp time column.
- HomeCard is reusable across both sections, with independent scroll and selection. Its viewport wraps up to five rows and caps at 172dp including borders; overflow uses 24dp inner DstIn fades that preserve the border and row tints. Both sections retain the shared short-time ticker.
- Card focus resets selection to the first row. Up/Down and Enter act locally, with nearest-row scrolling and reduced motion support. Recent chats targets Needs you on Tab when its card exists. Collection and focused-selection semantics accompany the focus ring; row controls stay outside Tab traversal.
- Delete discards only chats absent from openSessionIds. Open chats show an open badge, keep their time column and expose no discard control. ResumeDraft retains window-owned routing. No shared API or persistence contract changes; cards still compose every row. Native macOS and Windows validation remains unavailable, and QA did not rerun after the validation-phase test repair.
Feature size: MEDIUM
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-11-06] SKILL-512 merge New Task home lists
Areas: desktop/feature/chat newtask, desktop/core/designsystem
- Recent chats and Needs you merge into one bordered "Pick up where you left off" card. Blocked runs precede running runs, then drafts grouped by local calendar date into Today, Yesterday and Earlier.
- Rows use 34dp height, hairline dividers, status or chat/image glyphs, run stage badges and 52dp short-time columns. Hover or card selection replaces a chat's time with an accessible discard button.
- InboxItem, inboxItems, chatTitle, chatRepoLabel and shortAgo separate derivation from Compose. Titles use plain text with path fallback and image-name suffixes; repository badges compare normalized roots. reusable
- Returning homes show all saved drafts. Pending and first-run homes cap drafts at six and offer Show all through the existing repository-scoped resume palette. Drafts in the card remain cross-repository.
- All, Needs you and Chats filters use blocked-run and full saved-chat counts. The focused card owns bounded selection, Up/Down, Enter, Delete and digits 1 through 9, skipping date headers and leaving composer input alone.
- NewTaskSavedChats shares the saved list and pending discards across window drafts. Each discard hides its row immediately and owns a five-second Undo window; refresh excludes pending and deleting sessions, and expired Undo cannot cancel deletion. reusable
- InboxSection retains one shared ticker to refresh both short times and date groups. The work-summary link and keyboard hints sit below the card. An idle step line has no caption node; typed-request captions remain.
- Limits: filter selection does not persist, open-chat discard has no Undo, and window close does not flush pending discard timers. Cancelling a timer before deletion preserves the draft. No persistence or shared contract changes.
- Verification limits: native macOS and Windows targets remain unverified; QA covered an earlier tree than the final source.
Feature size: MEDIUM
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-06] SKILL-509 resume chat palette with preview
Areas: desktop/feature/chat, desktop/core/designsystem
- /resume opens a centered filterable palette with a chat preview in New Task and run-chat composers. New Task scopes drafts to the selected repository; run chats retain checkout-scoped choices. The Recent chats home list remains cross-repository.
- ChatResumeListing handles title cleanup, local calendar-day groups, filtering and the 30-chat default limit. Leading image paths become attachment chips. reusable
- The preview reads saved lines only; CLI sessions without lines show a message that content loads on resume. Arrow keys select, Enter or double-click resumes, and Esc closes and returns focus.
- The palette uses a separate Dialog focus owner. LucideIcons.image is available to other UI. reusable
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-05] SKILL-482 two-column repository and branch picker
Areas: desktop/feature/chat, desktop/core/domain, desktop/core/data, desktop/core/designsystem, desktop/build-logic, runtime-ports, runtime-infra/workflow, runtime-core
- New Task uses a two-column repository and branch picker. The repository pill becomes static after chat starts; the agent picker retains its existing behavior.
- RepositoryBranchesPort reads local refs and the runtime default without contacting a remote. It removes symbolic refs and duplicate origin branches and distinguishes non-repositories from read failures. reusable
- Desktop reads branches under GitReadLock, cancels stale reads, and retains a same-root listing during refresh. New paths that change the selected repository must request a read.
- FolderChooser validates a chosen folder as a Git repository before project lookup. The picker retains errors and selection while open, and added repositories live in the window draft. reusable
- The branch list pins the default, groups local and remote refs, and supports filtering, retry, keyboard selection and focus return. PickerHighlight tracks row keys across rereads. reusable
- SbPopover supports width, placement and arrow-key options without changing defaults; SbMenuItem includes end text in its accessible description. reusable
- Blank branch selection remains null at start. Remote names retain origin/; downstream merge or PR handling may add that prefix again. Added repositories without specs do not persist across restart.
- FileKit uses the installed runtime's shared dependency versions and requires jdk.net and jdk.security.auth in the desktop image.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-04] SKILL-479 subtask 2: desktop identity, offer card, and prompt
Areas: desktop/feature/chat newtask, desktop/core/data chat and control, runtime-engine work/control
- Goal/Plan identity comes from the offer-card complete-key field first, then a dedicated operator key or URL token. Agent lines, titles, chip starts, and in-sentence mentions are not identity. The raw token is passed; nothing mints prefix-(n+1).
- The offer card always shows a complete-key field, rejects prefixes such as NEWS, and does not start from suggested prefix choices. Empty Start asks via NeedsKey. IssuePrefixPicker remains for execution-chat retry only.
- reuseRequested is true only for a typed complete key plus reuse wording; a pasted used URL stays false. Occupied in-flight keys stay unbindable in the runtime.
- New Task prompt inspects local inventory and existing runs, suggests a next free key in prose only, and waits for operator acceptance. Start chips stay keyless.
- Desktop start uses RESOLVED_DESKTOP_IDENTITY so prepare binds only the resolved keyChoice and does not re-admit from composed requirements.
- Limits: prefix choices still populate the offer after NeedsKey but do not start a run. No next-key API; placeholder is static SKILL-12.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-03] SKILL-469 new-task chat Stop and Send now
Areas: desktop/feature/chat
- NewTaskChat.stop ends a running or unprompted reply, keeps the partial, appends Stopped, and always releases busy and the turn job. A cancelled save still writes under NonCancellable so drainQueue is not stuck after a cancelled IO save.
- Stop before any output rotates sessionId, deletes the old saved draft, and saves with sessionEstablished false so the next turn is a first turn on a new agent session.
- Late unprompted events after Stop are dropped until the next turn's first event. Send now interrupts without emptying the queue so the first queued message sends after Stopped.
- NewTaskDraft.stop restores queued texts ahead of existing draft text. NewTaskAction.Stop and SendNow reach those methods from the composer.
- NewTaskComposer shows Stop while stoppable, relabels Queue to Send now on an empty draft with a queue, and SendNowGate refuses a double tap or held Enter.
- Limits: pause-and-act, Esc, and process ending stay with subtasks 1 and 2. A queued suggested action does not survive restore. flush and end still save a cancelled turn but add no Stopped notice.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-03] SKILL-469 execution chat Stop and Send now
Areas: desktop/feature/chat
- ChatThread adds stoppableState, dropUnprompted, STOPPED_NOTICE, and restoredDraft. ChatSession passes stoppable through and holds stoppedTurn and pauseWatch. reusable
- stop restores queued text to the draft and interrupts. sendNow interrupts and leaves the queue so drainQueue sends. A null turnJob ends the agent then finishStopped.
- stoppable is true for operator send, pause-and-act, opening start, and owned unprompted replies. It is false on Stop, Send now, clear, resume, switchAgent, and launchTurn finally.
- finishStopped appends Stopped, saves a started session, and otherwise rotates the session id with no save. settleTurn saves under NonCancellable and clears busy in a nested identity check, including from invokeOnCompletion.
- Unprompted Stop sets dropUnprompted so a late event cannot busy the chat. stream resets it on the next turn's first event. watchStoppedPause relaunches a cancelled pause wait without holding busy.
- ChatComposer shows Stop while stoppable. The primary button reads Send now on an empty draft with a queue. SendNowGate refuses a held Enter or a press inside 500ms. reusable
- Limits: Stop does not end executions or goal runs. Queued action and pause-and-act flags do not survive the return to plain text. No Esc shortcut.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-03] SKILL-450 Paste images in the chat composer
Areas: desktop/feature/chat
- Cmd/Ctrl+V in ChatComposer reads the clipboard through ClipboardImageReader (default SystemClipboardImageReader). The key is consumed only when the clipboard holds an image and no text; otherwise normal text paste runs. The image is scaled to a 1568 px longest edge and encoded as PNG; over 3,750,000 bytes it is re-encoded as JPEG at 0.85 on white. Encoding runs off the UI thread; a failure adds no chip.
- ChatWorkspace keeps pastedImages per chat. The composer shows an [Image #N] chip per image with a Remove image N button; removing returns focus to the input. Pending images clear on close, closeExecutionChats, clear and submit. Send is enabled by text or images. An image finishing after its chat closed is dropped.
- send and pauseAndAct take images. QueuedChatMessage and ChatMessage.Operator carry them, and drainQueue passes them to ChatTurnRequest.images. An image-only prompt has no trailing blank line. Suggested-action messages carry no images.
- Operator bubbles show [Image #N] preview chips; clicking opens a popover with the image fitted into 320x240 dp, or "Preview unavailable" if it cannot decode.
- Limits: images live only in the session and are not written to chat history, so a restored Operator message has no chips and an image-only message saves as a blank-text line. Only the clipboard path exists; no file drop or picker. No test covers chip styling or the popover. QA reported capability_absent.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-03] SKILL-473 footer chats visibility toggle
Areas: desktop/feature/chat, desktop/core/ui pane, desktop/app shell
- ChatCommands.toggleChatPane shows or hides the run chat pane without opening, creating, or switching a chat. ChatWorkspace hides when the pane is open and otherwise only setPaneOpen(true). reusable.
- ShellSidebar footer order is Specs, Terminal, Chats, Settings, Keyboard shortcuts. Chats uses LucideIcons.bot, on = chatOpen, tooltip Chat pane, and no shortcut. MainWindow binds chatOpen to panes.chatOpen and onToggleChat to window.chat::toggleChatPane.
- Highlight follows pane visibility, not shownChatId, so an empty open pane still highlights. Settings still has no sidebar, so Chats is absent there without a second gate.
- Work-detail Chat, ChatPane +, and Cmd/Ctrl+L stay on toggleChat(item) or newChat. Leftover SelectionHolder does not create a tab from this footer path.
- ChatWorkspaceTest covers leftover-selection empty-pane and hide-then-show without open or newChat. NoChat stubs the new interface member.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

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
- After a New task conversation has started, idle repository and agent chips stay openable. They stay disabled while a turn is running, Start goal run is in flight, or the draft's repository is missing. The SKILL-459 lock that disabled them whenever a chat existed is not restored. SKILL-482 made the repository pill static once a chat exists, so this and the repository and base-branch rebinding below now apply to the agent chip only.
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
- Limits: a slow projects read keeps the home Pending, because the board observation starts after it returns. SKILL-482 added the default-branch read. The pill reads default branch only when the read fails or finds no default. No single test runs Esc then a chord across :app and :feature:chat, and no Compose test covers layout, the pinned composer or the moving dot.
- Unresolved: SbInput takes a String value, so the cursor may sit at the start of the text after an example fill. Main.dc.html is not in the repo, so the caption's 12dp gap and start alignment and the 240dp row title width were not checked against the design.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
