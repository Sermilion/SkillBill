## [2026-11-11] Keep live-policy Send now separate from Stop
Context: Both chat consumers previously interrupted a reply for Send now. SKILL-530 requires Claude and Codex follow-ups to preserve ongoing work and its context.
Decision: Read transport-owned policy separately from availability. Claude and Codex use live delivery with non-stopping queued fallback; Cursor and Junie retain interruption. Explicit Stop keeps cancellation authority.
Reason: An unavailable live handle must not turn guidance into cancellation or create a replacement process.

## [2026-11-11] Keep uncertain delivery out of automatic replay
Context: The operator removed duplicate-free delivery as an acceptance requirement while retaining visible uncertainty and warned explicit retry. Local delivery IDs are not provider idempotency keys.
Decision: Require matching provider acknowledgement for acceptance. Restore proven unsent or rejected entries in order; retain possible writes until late acknowledgement, explicit warned Retry or confirmed discard. Retire the old identity before retry.
Reason: Automatic replay after a possible write can duplicate guidance. Session and generation fences keep late acknowledgements associated with their original attempt, so they cannot consume a newer retry.

## [2026-11-11] Keep unresolved follow-ups with their session recovery owner
Context: Queue claims contain original text, images and metadata. Saved chat records do not persist image bytes, and replacing a chat can otherwise discard unresolved work.
Decision: Retain claims in ChatThread and gate destructive lifecycle actions and application quit on recovery or discard. Keep process handles and recovery images in memory.
Reason: Recovery must stay reachable without changing durable chat schemas or reconstructing process state. This limits recovery to the live application session.

## [2026-11-11] Admit New Task actions before returning
Context: Deferring admission lets later messages enter an earlier Send now batch or lets immediate Stop arrive before the ordinary turn is registered.
Decision: Claim Send now batches and register ordinary turns under the existing transition lock before returning. Keep asynchronous transport and completion work on the configured transition dispatcher.
Reason: The action boundary must establish queue ownership and the stoppable turn before the next operator action or composer observation, including image-only input.

## [2026-11-08] Separate proposed keys from accepted launch identity
Context: A SkillBill chat began by discussing another project's run. The leading key became the new goal identity without an explicit selection.
Decision: Only complete-key operator messages and visible bare-key acceptance chips select chat identity. The latest selection wins. Linear links propose keys, and missing-key preparation proposes the next local project keys. The picker separates selection from launch. Repository changes invalidate earlier acceptance.
Reason: Describing a run does not authorize using its identity for new work. Start actions show the accepted key before launch. Existing persisted runs and chats remain intact.

## [2026-11-08] Preserve the chat's dirty working tree baseline
Context: SKILL-528 requires chat changes to exclude tracked edits and untracked files that existed before the first agent turn.
Decision: Capture HEAD, untracked paths and an optional unreferenced stash through WorkTreeSnapshotPort. Read against that stash when present and report unavailable if it no longer resolves.
Reason: A HEAD-only baseline would count pre-existing tracked edits as chat work. Stash creation writes a Git object, so it belongs outside read-only WorkChangesPort. Falling back after pruning would silently change attribution.
Revisit when: Baselines need retention beyond Git's unreferenced-object lifetime.

## [2026-11-08] Do not reconstruct a missing baseline after chat work starts
Context: Older saved chats have no baseline, capture can fail before the first turn, and agent switches rebind the conversation to a new session.
Decision: Keep baseline decoding optional and preserve transcripts on malformed data. Attempt capture once before the first send; retain baseline and attempt state across same-repository rebinds and drop them on repository changes. Started chats without baselines remain without one.
Reason: A later capture would include earlier agent edits in the baseline and omit them from the chat's changes. Keeping the original baseline across agent switches preserves attribution for the same repository.

## [2026-11-08] Keep changes-popup state in the chat anchor
Context: The changes pill can disappear while its popup is open when the chat's changes state becomes None.
Decision: Keep the open flag local and keyed by chat. Keep SbPopover composed through the transition and return focus to the composer when the pill is absent, otherwise to the pill.
Reason: No caller outside the composer needs this open state. The disappearance scenario requires focus to remain in the composer rather than target a removed pill.

## [2026-11-07] Keep tool-only messages in one tool group
Context: Claude sends message_start for every API message, including ones that only call a tool, so each command became its own transcript entry.
Decision: MessageStarted closes the streaming entry only when it already shows text or actions. Tool-only messages keep adding to the same tool group, and the next message with text lands in that entry.
Reason: Tool calls between two pieces of agent text read as one block of work, as they did before message boundaries existed.

## [2026-11-07] Preserve provider message boundaries inside a chat turn
Context: Paragraph separators fixed run-on text but still combined every assistant message in a turn into one transcript entry.
Decision: Claude message starts and Codex and Cursor whole-message items emit MessageStarted. The reply parser settles buffered text and action metadata at that boundary, and the thread finishes its prior entry without completing the turn. Deltas within a message still append to one entry. Operator and finished assistant text share a copy control with copied feedback.
Reason: Message identity comes from the provider rather than text formatting. Separate saved history lines preserve the boundary on resume, while late tool results still update their original entry.

## [2026-11-07] Fill the tab title so the close control keeps the trailing inset
Context: A short tab title that does not fill leftover width stays at its text width, so the 20dp close control follows the title instead of the tab's trailing edge.
Decision: The title fills the space between the marker and the close slot. The row stays padded 10dp at the start and 4dp at the end.
Reason: The design grows the label, which pins the close control's trailing edge on that 4dp inset for a selected tab and on hover.

## [2026-11-07] Mask home card content inside the border
Context: Home cards need overflow fades while retaining their border and the blocked-run and selected-row tints.
Decision: Apply 24dp vertical gradients with DstIn blending on an offscreen layer around the inner scrolling column, inside the card's border padding. Cards with five or fewer rows draw no fade.
Reason: An alpha mask fades the existing row content without painting a solid panel color over its tints or fading the border. This follows the tab strip's masking technique.

## [2026-11-07] Keep home card keys local and pass through digits and open-chat Delete
Context: Recent chats and Needs you each own selection. The brief has Up, Down, Enter and Delete inside a focused card, and neither the brief nor the mock has digit shortcuts.
Decision: The card consumes Up, Down and Enter, and consumes Delete only for a chat absent from the open sessions. Digits, Ctrl, Meta, Alt, open-chat Delete and run Delete pass through. Row and discard controls stay outside Tab traversal, and Recent chats targets Needs you when that card exists.
Reason: Digit keys are not row shortcuts. Delete discards only a saved chat that is not already open. An open chat resumes onto its tab, and a run has no discard.

## [2026-11-08] Resolve repository aliases when listing saved chats
Context: Older chats can store a repository through a different filesystem alias than the selected repository.
Decision: Recent chats and the New Task resume palette compare canonical directory paths, resolving each distinct path once per listing. Resolution failures log a warning and use absolute normalized paths. Saved records retain their original paths.
Reason: Directory aliases must show the same chats without including other repositories or rewriting saved conversations.

## [2026-11-07] List Recent chats with the resume palette's repository filter
Context: The home listed saved chats across repositories, while the resume palette was already limited to the picked repository.
Decision: Recent chats uses resumableDrafts for the selected chat's repository root and hides when that list is empty, including when no repository is selected. Search chats still opens that scoped palette.
Reason: The brief's Recent chats section is every saved chat for that chat's repository, using the same filter as the palette.

## [2026-11-07] Drop InboxItem and keep pure chat title helpers
Context: The merged inbox, its filters and its day groups are gone, so a home row is either a blocked run or a saved chat.
Decision: Run rows take WorkItem and chat rows take ChatHistoryEntry. chatTitle, chatRepoLabel and shortAgo stay free of Compose, and saved-chat contracts stay unchanged.
Reason: Filters, day groups and the merged list are removed, so rows no longer share an InboxItem wrapper. The helpers still format titles, repository badges and short times without a UI harness.

## [2026-11-06] Reserve the overflow spacer's row gap
Context: The overflow row contains tabs, New chat, a weighted spacer and the chevron. Row spacing adds three gaps, while the existing button-slot reservations account for two.
Decision: Keep row spacing and subtract one additional 2dp gap from the available tab-container width when overflowing.
Reason: Without that reservation the fixed children exceed the available width by 2dp and displace the chevron. Explicit tab width also keeps New chat beside fitting tabs.

## [2026-11-06] Keep the strip hairline outside tab fade masks
Context: Only the selected tab should join the content below. Painted frame-color fades and full-height unselected hover fills can hide the strip hairline.
Decision: Keep the hairline on the parent strip, apply offscreen DstIn masks to tab content, and stop unselected hover fill 1dp above the bottom. Selected fill reaches the bottom and uses an open top-and-side border.
Reason: The parent hairline remains visible through faded content and under hovered unselected tabs. The selected tab alone covers it to form the panel join.

## [2026-11-06] Keep home inbox derivation separate from saved chat data
Context: The home combines work rows and saved drafts, with filtering, keyboard selection and deferred discard layered onto the same list.
Decision: Keep InboxItem and its derivation helpers free of Compose. Derive plain titles, image names, repository badges and short times for the card without changing saved-chat contracts or resume-palette formatting.
Reason: The interaction layer can use the inbox model without changing saved-chat data. Pure helpers also allow fixed-clock and explicit-zone tests without a UI harness.
Superseded by: Drop InboxItem and keep pure chat title helpers (2026-11-07)

## [2026-11-06] Keep inbox navigation on the focused card
Context: Inbox rows share a screen with the composer. Existing shell routing allows unmodified navigation keys through when no screen handler is registered.
Decision: The card handles navigation, opening and chat Delete on key-down. Rows and discard buttons stay outside Tab traversal, and Ctrl, Meta and Alt combinations pass through.
Reason: Card-local handling keeps composer digits as text and prevents row controls from also handling the same key. No shell or screen-level handler is needed.
Superseded by: Keep home card keys local and pass through digits and open-chat Delete (2026-11-07)

## [2026-11-06] Let each saved-chat discard own its undo window
Context: Home-list discard must hide a chat immediately and allow five seconds to undo. Several chats can be discarded before their windows expire.
Decision: The window's shared NewTaskSavedChats tracks Undoable and Deleting states per session. Each job starts deletion after five seconds; Undo cancels only Undoable. Refreshes exclude both states, and window close does not flush timers.
Reason: Independent jobs preserve each chat's undo window. Keeping Deleting prevents refresh from restoring a row during suspended deletion and prevents expired Undo from cancelling deletion. Cancellation before deletion preserves the saved draft as the intended safe outcome.

## [2026-11-06] Bind New Task async results to their originating draft
Context: Operators can select another open chat while agent resolution, a branch read or a repository path check is suspended.
Decision: Capture the target draft once per action and write results only into it. Update shared composer UI only while that draft is selected; selection refreshes agent and branches and closes both pickers.
Reason: Reading the selected draft after suspension could rebind or overwrite a different chat. Per-draft agent reads also keep a choice in one chat from cancelling another chat's resolution.

## [2026-11-06] Detect background replies from the busy-to-idle transition
Context: Both prompted and unprompted replies need the same unread marker when they finish in an unselected chat.
Decision: NewTaskChats watches each draft's chat busy state, flags an unselected chat when that same chat transitions to idle, and clears the flag on selection. Removing a draft cancels its watcher.
Reason: NewTaskChat sets busy for both kinds of reply, so the transition covers both without comparing transcript contents.
Revisit when: Replies can arrive without setting busy.

## [2026-11-06] Resolve Reopen from the saved chat after close
Context: Closing flushes a running turn, which can rotate its session id. The operator may delete the saved chat before clicking Reopen.
Decision: Capture the session id after close succeeds and read saved drafts afresh when Reopen is clicked. Resume only a matching entry; otherwise show the missing-chat toast.
Reason: A pre-close id can point to the replaced session, and the cached saved list can retain a deleted entry.

## [2026-11-06] Share resume routing in the window's chat holder
Context: Recent chats, /resume and the close toast must open a saved chat without ending another conversation.
Decision: NewTaskChats selects an existing session, loads an empty selected draft or adds a draft. NewTaskDraft.resume only loads an empty draft; a failed added-draft load removes it and restores selection.
Reason: The window holder owns the open set and selection. The former draft-level close before resume would end another chat.

## [2026-11-06] Signal saved-chat loads separately from selection
Context: Reopen bypasses the view-model action, and loading an empty selected draft causes no selection change.
Decision: NewTaskChats emits successful loads through resumed. For a selected loaded draft, the view model cancels its pending agent read and refreshes its agent and branches.
Reason: Observing selection alone misses this load and can leave the composer showing the previous agent or branches.

## [2026-11-06] Replay the transcript in the first prompt after a context switch
Context: Rebinding the agent, repository or base branch opens a fresh session that has not seen the transcript still visible in the chat.
Decision: Prefix the first prompt with operator and finished agent messages, retaining the newest conversation within 24,000 characters. Clear pending replay on the first non-Failed event, pass it unchanged through unsent rebinds, and keep it out of transcript storage.
Reason: Reusing the app transcript avoids an extra agent turn for a handoff summary. Clearing replay where started flips preserves retries before output without resending it on later turns. Persisting pending replay would require ChatNewTaskContext and ChatStore changes outside scope.
Revisit when: Pending replay must survive saved-chat resume, or the conversation budget needs to change.

## [2026-11-06] Scope the New Task resume palette to the picked repository
Context: The home lists drafts across repositories, while the resume palette identifies a single repository with its scope chip.
Decision: New Task passes only drafts for the picked repository to the palette, or none if no repository is picked. The home list remains cross-repository; its Show all opens the scoped palette.
Reason: A cross-repository result under a single repository label could switch repositories without warning. The home list still offers drafts from other repositories.
Superseded by: List Recent chats with the resume palette's repository filter (2026-11-07)

## [2026-11-06] Count a bare-key chip click as key acceptance
Context: The prompt offers a "Use SKILL-n" chip, but newTaskOperatorIdentity skipped every operator message sent from a suggested action, so clicking it did nothing and the operator had to type the key.
Decision: A chip message counts as identity only when the action has no start, its instruction is exactly one complete key, and its label contains that key as a whitespace token. Every other chip message stays excluded, as before.
Reason: A click on a chip whose label shows the key is the operator accepting it. Requiring the key on the label stops a model-written chip from binding a key the operator never saw, so the 2026-11-04 rule against agent-suggested keys still holds.

## [2026-11-04] Bind only desktop-resolved identity on desktop Goal/Plan start
Context: Desktop already resolved identity from the offer field or dedicated operator tokens. Re-running admission on composed chat requirements would bind an in-sentence key or an agent-suggested key that leaked into the transcript.
Decision: Desktop prepare uses identity policy RESOLVED_DESKTOP_IDENTITY and binds only keyChoice. REQUIREMENTS_FIRST remains the default for other callers.
Reason: Sentence mentions must not become identity, and GoalIntake.parseOrNull stays unchanged. Scanning requirements again would undo the desktop helpers.

## [2026-11-04] Reuse only typed complete keys, never pasted URLs
Context: An inventory-held complete or cancelled spec key may bind again, but a pasted used tracker URL must stay refused even if the operator also said reuse.
Decision: NewTaskDraft sets reuseRequested only when identity is a typed complete key and an operator message contains reuse wording with that key as a whitespace token, including when that key is then typed in the offer field. URL identity always leaves the flag false.
Reason: Subtask 1 occupancy treats a used URL as not reusable. Forcing reuse around occupied in-flight keys would steal a running run.

## [2026-11-04] Keep IssuePrefixPicker for execution-chat retry
Context: New Task Goal and Plan starts must not mint from a prefix menu, but execution chats still retry a missing key through ChatComposer.
Decision: Remove prefix-menu starts from the Goal run card. Keep IssuePrefixPicker as a sibling composable that ChatComposer still imports.
Reason: Deleting the picker would break execution-chat retry. The Goal run card now takes a complete key only.
Supersedes: Show the Goal run card only when the operator is asking to run a goal.

## [2026-11-04] Drop the Goal run card and keep start feedback in the transcript
Context: The Goal run card duplicated agent start:goal / start:plan buttons and held the success banner, prefix picker, and start errors outside the conversation.
Decision: Remove the card. Goal and plan start from the agent's in-chat actions. A successful start keeps the chat and appends a confirmation notice that restores as a success callout. Goal refusals append as Error. A pending prefix choice is a transcript footer, not a card. Home chips for review, validation, QA, and PR stay.
Reason: The operator starts a run from the action the agent offered. The confirmation has to stay visible in the chat so another message can start again.
Supersedes: Show the Goal run card only when the operator is asking to run a goal.

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
Superseded by: Keep live-policy Send now separate from Stop (2026-11-11), for Claude and Codex Send now only; Stop and legacy-provider behavior still apply.

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
Superseded by: Keep live-policy Send now separate from Stop (2026-11-11), for Claude and Codex Send now only; Stop and legacy-provider behavior still apply.

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
Decision: Carry the selected action through the screen host and retain it with the pending prefix choice. Use its instruction and exact nullable title for the initial request and retry. Clear or replace pending input on a new turn or launch; a start without a selected action uses the transcript.
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
