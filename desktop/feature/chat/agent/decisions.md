## [2026-11-03] Always release a cancelled turn after a NonCancellable save
Context: The window scope uses Dispatchers.Main, not Main.immediate, so a job cancelled before first dispatch never enters its body. A cancelled IO save can skip clearing the turn job, and /clear can replace turnJob during that suspension.
Decision: Both chats save the stopped turn under NonCancellable and clear busy, stoppable, and the turn job in a nested finally. invokeOnCompletion still finishes the turn when the body never ran. Execution chat also clears only when turnJob still matches.
Reason: A cancelled turn must always release the chat so drainQueue is not stuck. Without the completion path the chat stays busy until /clear. Without the second identity check the old turn would unlock the composer in the middle of a swap.

## [2026-11-03] Open a new agent session when Stop happens before any output
Context: Whether the Claude CLI rejects a reused session id after a kill is unverified. Saving a never-started session would make /resume open a session the agent never created.
Decision: Both chats rotate to a new session id when Stop happens before output. Execution chat saves nothing. New-task chat deletes the old draft and saves with sessionEstablished false.
Reason: The next turn must be a first turn on a fresh session for every agent. The stopped message stays in the transcript even though the new session never saw it.

## [2026-11-03] Drop late unprompted events after Stop
Context: Unprompted replies have no turnJob. Abandon emits no terminal event, and unpromptedEvents is a buffered shared flow. showUnprompted takes busy when no turn runs.
Decision: Stop and Send now set dropUnprompted. unprompted and showUnprompted return immediately while it is true. The next turn clears it only after ensureActive on its first event. A null turnJob ends the agent then finishStopped.
Reason: A late event would set busy again with nothing collecting it, or append after Stopped. ensureActive keeps a cancelled turn from clearing the flag itself.

## [2026-11-03] Return queued chat text to the draft on Stop
Context: DrainQueue runs when a turn ends, so Stop would send the next queued message. New-task stopUnprompted also ends in drainQueue.
Decision: Stop empties the queue in both chats, including an unprompted new-task reply, and restoredDraft writes that list into the draft ahead of existing text before cancel.
Reason: Nothing sends and nothing is lost. A queued message's action and pause-and-act flag do not survive, so the operator chooses Send or Pause and act again. Emptying only the running-turn path would let an unprompted stop drain the queue.

## [2026-11-03] Watch a stopped pause wait without holding the chat busy
Context: resumeAfter is registered only after the pause wait, so Stop during that wait would drop the relaunch.
Decision: A Stop or Send now cancellation during the wait starts watchStoppedPause, which waits with the original deadline then resumeAfter. It does not set busy while waiting. A refused or failed pause starts no watcher. The pause control call runs under NonCancellable so its outcome is known.
Reason: The operator can keep chatting. At most one watcher runs per session. /clear and tab close do not set stoppedTurn, so they start no watcher.

## [2026-11-03] Refuse held Enter and double-tap Send now
Context: SbInput fires onSubmit on every Enter key-down with no repeat detection. After Queue, the empty draft would fire Send now.
Decision: SendNowGate.admit refuses a press within 500ms of the last recorded press and refuses Enter until a key-up. Each refused press restarts the window. A click needs no key-up.
Reason: A held key or double tap would kill the running turn. The class has no Compose types so the new-task composer can reuse it.

## [2026-11-03] Send now leaves the queue so drain sends
Context: Stop and Send now both end the running reply, and drainQueue would send after either if the queue remains.
Decision: sendNow uses the same interrupt as Stop and leaves the queue in place. Stop empties it first.
Reason: The stopped notice is written in the turn finally before drain runs, so Stopped always precedes the new operator message.

## [2026-11-03] Keep pasted chat images in the session, not in chat history
Context: Operator messages can carry pasted images, but ChatStore history lines hold text only and images are large.
Decision: Images stay on QueuedChatMessage and ChatMessage.Operator for the running session. ChatThread.historyLines and restored name their fields explicitly and drop images. No persistence code or history contract change was added.
Reason: Persisting bytes would grow the history file and need a legacy read path for old lines. The agent already receives the images on the turn.
Revisit when: restored chats must show or resend earlier images.

## [2026-11-03] Normalize pasted images before they reach the turn request
Context: Clipboard images can be arbitrarily large and are sent to the agent CLI.
Decision: normalizedChatImage scales to a 1568 px longest edge and encodes PNG; above 3,750,000 bytes it re-encodes as JPEG at 0.85 on white. ChatComposer takes encodeContext, defaulting to Dispatchers.Default, so tests encode inline without polling.
Reason: Agent image limits are lower than raw screenshot sizes, and encoding on the UI thread would freeze the composer. The injectable context avoids timing-based tests.

## [2026-11-03] Return focus to the input when a pasted image chip is removed
Context: The remove button leaves composition with its chip, which dropped focus from the input, so the next paste shortcut never reached onPreviewKeyEvent.
Decision: The chip's onRemove calls focus.requestFocus() after removePastedImage, as submit already does.
Reason: Remove then paste again is a normal flow and must keep working from the keyboard.

## [2026-11-03] Footer Chats only flips pane visibility
Context: Cmd/Ctrl+L and Work-detail Chat call toggleChat(item), which opens leftover SelectionHolder and can create a tab. Terminal toggle creates a tab when none exist. toggleChat(null) does not hide an already-open empty pane because shownId is null with no tabs.
Decision: The footer calls ChatCommands.toggleChatPane, which only hides or setPaneOpen(true). It does not read SelectionHolder and does not call open, newChat, or openChat.
Reason: The operator asked to slide the existing pane, not add a third create path. Writing panes.chatOpen from the footer would not show FloatingPane, which reads workspace.paneOpen. CHAT_SHORTCUT stays off the item because that chord is not visibility-only.

## [2026-11-03] Keep suggested input with the pending prefix choice
Context: A suggested goal or plan can need a prefix before it starts. Transcript-derived retry input can select an unrelated instruction or title.
Decision: Carry the selected action through the screen host and retain it with the pending prefix choice. Use its instruction and exact nullable title for the initial request and retry. Clear or replace pending input on a new turn or launch; card starts use the transcript.
Reason: The retry must start the action the operator selected, while a later card start must not inherit that action.

## [2026-11-03] Show the Goal run card only when the operator is asking to run a goal
Context: After the first turn the card appeared on every New task chat, including questions about an issue that were not a request to start a run.
Decision: showGoalOfferCard is the only visibility gate. It stays hidden until the operator asks to run a goal or plan, or the latest reply offers a goal or plan start. A later review, validate, qa, pr or release request, or a later standalone start, hides it again. A pending prefix choice, start problem or started confirmation still keeps it, because only the card shows those.
Reason: The card starts a goal run. Showing it on a Q&A turn offered the wrong action. Agent start:goal already marks a clear implement request, and an explicit "start a goal run" should not wait for that button.
Supersedes: Hide the Goal run card for standalone starts and release, which only listed the hide cases and still showed the card after any other first turn.

## [2026-11-02] Hide the Goal run card for standalone starts and release
Context: Standalone starts and release requests still showed Start goal run, which would launch the wrong kind of work.
Decision: goalOfferStandsAside hides the card when the latest reply offers a standalone start or the latest operator message matches the Release preview. Keep it when a prefix choice or goal problem is pending.
Reason: Only the card can show those pending problems. Release has no start button, so the card would start a goal instead.
Revisit when: release matching should cover wording that does not name a release.

## [2026-11-02] Release a standalone reservation when its process exits
Context: A settled standalone reservation cleared only when the board showed a new row, so a run that blocked before creating one held the key until restart.
Decision: When a standalone start returns Running, RuntimeWorkControlRepository calls LaunchReservations.releaseOnExit, which releases that handle when the process exits.
Reason: A run still in progress keeps the refusal, and a late exit from an old run cannot clear a newer reservation.

## [2026-11-02] Retry NeedsPrefix from the execution composer
Context: NeedsPrefix in an execution chat left a notice with no way to pick a prefix and retry the start.
Decision: Keep the notice, store the action in ChatSession.prefixChoice, and show IssuePrefixPicker in ChatComposer. choosePrefix retries the same start with the chosen keyChoice.
Reason: New Task chats already had the picker, so extracting it keeps one prefix UI. The pending choice clears on a new start, /clear, /resume or a new message, and the retry still refuses a running execution.

## [2026-11-02] Refuse blank verify at the engine, not the New Task draft
Context: Verify requires an intake, but New Task clicks must still call startNewTask so the operator sees the existing start-problem path.
Decision: Desktop always forwards Verify, including a blank target. startOperation returns Refused with a reason that verify needs an intake, before launcher.start. pr-review-fix may omit target.
Reason: A draft-level early return would hide the engine refusal the spec requires.

## [2026-11-02] Launch execution-chat starts without send or pause-and-act
Context: A suggested start in an execution chat used to ignore action.start and always send or pause-and-act on the instruction.
Decision: executeAction branches on action.start. A running or Active execution gets a Notice and no launch. A stopped execution launches startNewTask in that item's repositoryRoot, with no Operator turn.
Reason: Pause-and-act lasts one turn, so holding an execution paused for a whole review, monitor or verify is wrong. Sending the instruction would skip reservation and the phase or operation argv path.

## [2026-11-02] Reuse the StandalonePhase reservation for verify and pr-review-fix, then release
Context: Operations reserve like phases, but FEATURE_VERIFY board rows are WorkKind.Operation with a null phaseId, so AwaitingBoard never matches a StandalonePhase select.
Decision: Reserve with StandalonePhase and the repository-identity plus id key. Release the handle once the operation starts and do not settle. Monitor and other phases still settle.
Reason: With no matching board row, settling would leave the key held and refuse the next verify. A new StandaloneOperation target would have required LaunchReservations changes that subtask 2 ruled out.
Revisit when: an operation should refuse a second launch while one runs. releaseOnExit now also ends settled reservations.

## [2026-11-02] Keep Verify findings distinct from start verify
Context: Execution chats already offer Verify findings for review findings, which is a different command from operation verify.
Decision: Keep the existing Fix findings and Verify findings paragraphs and add a line that those actions must not set start verify.
Reason: Collapsing them would send review-finding verify down the skill-bill operation path.

## [2026-11-02] Decode is the strict target seam; persist stays lenient
Context: monitor needs a PR URL or issue key and verify needs intake, while chats saved before this field have no target key.
Decision: decodeActions treats a missing target as null, stores a trimmed string, and drops the action when target is present but not a string. ChatStore writes target only when non-blank and reads a missing key as null, not malformed.
Reason: A numeric target in a live reply must not become a button. Older session files must still load.

## [2026-11-02] Share one start-guidance block across both chat prompts
Context: New-task chats listed six start wires without phrase mapping. Execution chats had no phase vocabulary, so the two prompts could teach different start rules.
Decision: Extract SHARED_START_ACTION_GUIDANCE and include it in both NEW_TASK_ACTION_GUIDANCE and CHAT_ACTION_GUIDANCE.
Reason: One constant keeps both scopes on the same start list, phrase mapping, labels and target rules.

## [2026-11-02] Keep the previous New task agent visible while ChooseAgent resolves
Context: ChooseAgent cleared selectedAgent and state.agent before resolveAgent finished, so chipText flashed No agent on the chip and Goal run offer.
Decision: ChooseAgent sets agentId and launches resolveAgent without nulling the previous ChatAgent. The resolved agent replaces it only when draft.agentId still matches.
Reason: The chip and offer share selectedAgent. Clearing it was not required for rebind and made the model line blink.

## [2026-11-02] Name why a New task chip is disabled on the chip itself
Context: Busy, start in flight, and missing repository already disabled the chips, but the operator had only ChatActivityPill, a repository-missing Notice, or disabled Start buttons to guess why a click did nothing.
Decision: Each disabled chip exposes the active reason locally with SbTooltip and semantics stateDescription. Precedence is busy, then offer.starting, then repositoryMissing. The hand cursor follows the same enabled or canOpen gate as clickable.
Reason: A chip-local why is readable from the composed tree without waiting on tooltip delay. Other chrome left a dead control with no explanation at the click target.

## [2026-11-02] Change the displayed New task model by picking another agent
Context: The agent chip shows chipText of ChatAgent, which includes model and effort, so it looks like a model editor.
Decision: The chip menu lists agent ids only. onChoose stays a string callback. Same-agent model and effort stay in Settings. New task does not write machine config.
Reason: Execution chat already switches by agent id. A model payload would fork AgentPicker and put matrix writes on this surface.

## [2026-11-02] Keep the started guard in memory on the live chat
Context: The chat now stays open after a Goal run or Plan only start, and the discard that used to stop a second start is gone.
Decision: Store started on NewTaskOffer only. Clear it in clearChatFeedback and when sendTurn admits a turn. Do not persist it in ChatStore.
Reason: Persisting it needs a ChatNewTaskContext change in core/domain and ChatStore serialization, so a resumed draft can start again as an accepted limit. A repeat start would probably launch a second run under a new key, inferred from startNewTask without reading prepareNewTask or the allocator. A pending start that completes after leave does not set started on another chat because the Started copy requires ownsChat.
Revisit when: the guard needs to survive resume or restart.

## [2026-11-02] Refuse repeat Goal and Plan starts in start()
Context: After a Goal run or Plan only start, those starts must stop without hiding Review, Validation, QA or PR.
Decision: start() returns early for GoalRun and PlanOnly when started is set, before starting or startNewTask. canStart also requires started to be null. allowStartActions stays host.onStart != null. Standalone kinds skip the guard and do not clear it.
Reason: Card buttons, prefix controls and agent-suggested chips all reach draft.start, so one guard covers them. Setting allowStartActions false would hide standalone chips. The callout explains a no-op goal chip click.
