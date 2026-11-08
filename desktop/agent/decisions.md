## [2026-11-08] Map build presentation in core/ui
Context: Work rows and run details consume WorkBuild, while design-system components accept SbBuild presentation values.
Decision: Map runtime builds to WorkBuild in core/data and convert WorkBuild to SbBuild in core/ui/status for both features.
Reason: core/ui already depends on core/domain and core/designsystem and owns status mapping. This keeps the design system independent of domain models and gives both features one presentation conversion.
## [2026-11-07] Keep summary writes separate and stamp the summarized snapshot
Context: Conversation saves rewrite the chat file, and a new turn or discard can happen while a background summary runs.
Decision: saveChat preserves stored summary fields and ignores the entry's summary. Only saveSummary changes them, under the store lock on an existing file, with the snapshot's updated_at as summary_source_updated_at_ms and no change to the stored updated_at.
Reason: Ordinary saves must not erase summaries. Stamping a newer stored time would mark old content current; preserving updated_at keeps Recent chats order, and refusing a missing file prevents a discarded chat from reappearing.
Revisit when: Other callers need to edit or clear summaries, or summaries must incorporate turns saved during a call.

## [2026-11-07] Recover stale summaries at startup instead of tray quit
Context: Tray Quit exits without closing chats, and the shutdown hook signals live agent processes, so a summary started at quit would die.
Decision: Start one app-scoped serial queue and sweep at most 20 stale chats, newest first, once at launch. Chat close, leaving an idle chat and window close request summaries; per-reply saves and hiding the pane do not.
Reason: Persisted staleness lets the next launch recover unfinished work without delaying quit or racing process shutdown. App scope lets window-close requests finish while hidden to the tray, and the startup cap bounds agent spend after a long gap.
Revisit when: Quit can wait for background work, or the newest-20 cap leaves needed summaries missing.

## [2026-11-07] Restrict summary calls and use an explicit cheap-model map
Context: Saved transcripts are untrusted input, and summary calls bill the chat's own agent account.
Decision: Run separate one-shot commands outside repositories with Claude haiku, Codex gpt-6-luna or Cursor gpt-5.4-nano-none. Use tool-less, read-only or ask-mode arguments, omit session-resume and permission-bypass flags, and skip unmapped agents with chat.summary_unsupported_agent.
Reason: Transcript instructions must not gain tool or write authority or disturb the chat's resume state. Junie has no confirmed cheap model, so guessing could select an expensive model or fail.
Alternatives considered: Reusing ChatTransport.run would carry the chat's full permissions and save agent session state.
Revisit when: Agent models or CLI flags change, or a cheap Junie model is confirmed.

## [2026-11-07] Use a stationary ellipsis for summaries under reduced motion
Context: Summary rows must scroll sideways when text overflows, while the app also supports LocalReducedMotion.
Decision: SbMarqueeText applies continuous basicMarquee only when reduced motion is off. Reduced motion renders one stationary ellipsized line; fallback titles retain their existing rendering.
Reason: Following the existing reduced-motion gate avoids scrolling for operators who disable motion and still leaves readable truncated text.
Revisit when: The design calls for wrapping or another overflow treatment under reduced motion.

## [2026-11-07] Bind preview lifetime to the visible selected QA execution
Context: Historical runs can retain framebuffer files, concurrent runs can be active, and a detail ViewModel can survive route disposal.
Decision: Derive the request from the selected standalone desktop QA execution and current database session. Require visible route and pane authorization, clear frames on request changes, cancel the former observer and reject late generations. Terminal selections never read a source.
Reason: File presence and ViewModel lifetime do not establish that the operator is viewing an active run. The selected execution and current visibility must authorize every published frame.

## [2026-11-07] Confine framebuffer opens through directory handles
Context: Preview reads must remain inside the selected invocation even if a path changes between validation and opening.
Decision: Traverse the invocation and fixed preview child through scoped SecureDirectoryStream handles, reject symlinks and open the final regular file read-only without following links. Refuse the source when secure directory streams are unavailable.
Reason: Path-string validation alone cannot confine the actual open. Directory-relative handles preserve that boundary during path replacement and close on every exit.

## [2026-11-07] Use only the invocation-owned private framebuffer
Context: QA can use an existing display when private capture is unavailable, but preview must never expose the operator's visible display.
Decision: Capture only the registered owner-only live-preview child through private Xvfb -fbdir. Registration refusal disables capture. Reuse the existing resource registry and stop producer processes before releasing the child.
Reason: Exact invocation ownership isolates concurrent runs and preserves retained-resource and cancellation cleanup behavior. Existing-display QA fallback supplies no authorized preview source.
## [2026-11-07] Force the Linux source-run software renderer
Context: SKILL-514 QA reported an X11 visual lookup crash during the source-run launch. QA-2 remains open after three repair attempts.
Decision: Select Skiko SOFTWARE_COMPAT for Linux source runs through both the JVM property and SKIKO_RENDER_API.
Reason: SKIKO_RENDER_API takes precedence over an inherited renderer setting, so the environment override is required to force the selected renderer.

## [2026-11-07] Stamp copied source-run identities as one launch pair
Context: A QA checkout can differ from the installed runtime. Dirty build identities require a matching commit and a shared non-empty install run id.
Decision: Stage runtime-cli and prepared app resources from the same checkout, then stamp only the copied identity records with one fresh source-run UUID on every execution.
Reason: BuildIdentityPlugin reads the install run id during configuration and omits it for clean trees. Stamping the staged pair supplies the dirty-build identity without changing gate rules or real-install behavior.

## [2026-11-07] Isolate source-run settings and child-agent state
Context: Desktop settings live under user.home without an environment override. A saved database path can override SKILL_BILL_DB, and blank TERM lets startup import login-shell exports.
Decision: Recreate a scratch home for each QA launch, redirect JVM and child-process state and temp paths there, and set TERM to `dumb` only when inherited TERM is blank.
Reason: Overriding SKILL_BILL_STATE_DIR alone leaves the operator's settings and database reachable. The TERM fallback preserves launch overrides. Scratch agent configuration means QA agents do not inherit the operator's login.
Alternatives considered: Keeping the real user.home would require a production settings-path override, outside the spec's real-install constraints.

## [2026-11-07] Keep QA discovery explicit and picks owned by settings
Context: The sidebar and New Task chip display the same persisted pick while device availability and leases can change independently.
Decision: AppSettingsRepository owns the pick. Window-scoped QaDevicePickerState owns cancellable discovery on open and explicit refresh. Preserve a missing pick and match an AVD pick to its running emulator.
Reason: Explicit discovery avoids background adb polling. Both UI surfaces observe saved settings so rejected writes retain the prior pick. Disconnecting hardware must not silently select auto.
Revisit when: Live device updates become a requirement or device picks need a narrower scope than app settings.

## [2026-11-07] Read the confirmed QA pick before eligible launches
Context: The general settings flow converts an I/O failure to empty preferences, which cannot distinguish auto from an unreadable saved selector.
Decision: Use readQaDevice against one store snapshot before launch writes, retain it through allocation retries and refuse eligible launches on read failure.
Reason: Storage failure must not silently launch QA with auto targeting or combine selector values from different reads. Hardware discovery must not erase a saved pick.

## [2026-11-07] Keep picker lease discovery advisory and read-only
Context: The picker needs repository lease occupancy, but occupancy can change after discovery and the writer's lock helper creates storage.
Decision: Read typed lease snapshots through the active runtime port without writer locks, reclamation or file changes. Retain unverifiable owners and diagnose unavailable occupancy.
Reason: Discovery must not acquire or release devices. QA owns actual lease acquisition, and an unavailable occupancy read must not prevent adb discovery.
Alternatives considered: Reusing the writer's lock helper would mutate storage during a read.

## [2026-11-06] Save the chat width after a drag settles and flush it on window close
Context: Dragging the chat divider changes the width many times per second. Writing each value to DataStore is wasteful, and a width changed just before quitting could be lost.
Decision: ChatPaneWidth updates its state flow immediately and saves through AppSettingsRepository.setChatWidthDp after a 300 ms pause. Closing the window cancels the pending timer and writes the last value once, bounded by a one-second timeout. A rejected write is dropped, not retried. The width is clamped to 360 to 2000 dp on write and again on read.
Reason: One write per drag keeps settings storage quiet, and the close flush keeps the last width. Clamping on both sides stops an unclamped writer from being hidden by the reader. The timeout stops a stuck store from delaying window shutdown.
Revisit when: Settings writes become cheap enough to save on every change, or window close gains an asynchronous shutdown hook that replaces the blocking flush.

## [2026-11-06] Dock chat in the shared right pane slot and clamp it to a main-column floor
Context: Chat floated over the work area as an overlay, covering the main column and competing with the specs pane for Esc and focus.
Decision: Chat renders through WorkArea's right slot and replaces the specs pane while open outside Settings. DockedPane clamps width to at least 360 dp and at most the work-area width minus 420 dp, and every resize reports the clamped result. In drawer mode it covers the work area without a grip. Esc is routed to the pane only while focus is inside it.
Reason: A docked pane keeps the main column usable and avoids a scrim that blocks it. Clamping at the pane, from the rendered width, keeps stored widths valid after a window shrink.
Revisit when: Chat and specs need to be visible together, or the main column floor needs a per-route value.

## [2026-11-06] Keep New Task chat ownership independent of layout
Context: The Open chats setting switches live between Tabs and Side list while chats may be replying or hold unsent text.
Decision: Window-scoped NewTaskChats owns open drafts and selection outside the screen view model. Both layouts read the same OpenChatItem projection; shortcuts and busy-close confirmation stay outside the layout branch.
Reason: A presentation change must preserve drafts, sessions, order and selection. Keeping ownership in the window also preserves the existing navigation and database-session lifetime contract.
Revisit when: Transcript scroll must survive layout changes. The current spec permits it to reset.

## [2026-11-06] Keep the open New Task chat set in memory
Context: Saved New Task drafts already return through Recent chats and the resume picker, and restoring the open set after restart is out of scope.
Decision: Persist saved conversations through the existing chat store, but keep open-chat membership and order in window memory only.
Reason: Saved drafts provide recovery. Persisting the open set would add another store for those chats beyond the requested scope.
Revisit when: Operators need their open tabs restored after restart.

## [2026-11-06] Desktop treats a missing worktree directory as Removed, whatever the runtime reports
Context: The runtime can lag the disk. A goal worktree deleted externally stayed Active, so chat and terminal started agents in a directory that no longer existed (WE-5187).
Decision: The mapper downgrades Active or Retained to Removed when the worktree path is blank, invalid or not an existing directory. Chat, terminal and the changes window resolve their root at use time: the checkout root if it exists, else the repository root. The changes window opens committed-range files only while the main checkout is on the goal's branch.
Reason: The directory on disk is the only fact an agent launch depends on; trusting runtime state alone leaves a window of launches into a failing path.
Alternatives considered: A runtime-only fix leaves stale state between refreshes; a path watcher is more machinery than an existence check.
Revisit when: The runtime reports worktree liveness itself or the desktop gets a change feed that makes the per-row check unnecessary.

## [2026-11-06] Continue a chat in the main checkout by moving agent session files
Context: After removal, saved Claude and Codex sessions still live under the worktree path, so opening the chat at the main checkout started a fresh session and lost the conversation.
Decision: End the agent process, then move or rewrite saved sessions to the main checkout path once per chat session under a lock. Keep the session id only for Claude or Codex when the move reported no failures; otherwise start fresh and say so. Claude files move without overwriting a destination; Codex files get only the first-line cwd rewritten through a same-directory temporary file and atomic replace.
Reason: Moving the files is the only way to keep the agent's own history, the no-failure condition prevents resuming a half-moved session, and not overwriting protects sessions already at the main checkout.
Revisit when: Cursor or Junie expose a relocatable session store, or Claude and Codex resume by id regardless of directory.

## [2026-11-06] Refresh observed standalone plans on a bounded pulse
Context: Plan output snapshots have no change revision, so database signals alone do not guarantee a full detail refresh when output changes.
Decision: Refresh full detail every two seconds while an observed standalone plan is active or blocked. Stop when a full read reports it idle or the observer closes.
Reason: The pulse makes persisted progress visible without changing producer or persistence ownership.
Revisit when: Output writes provide a reliable change signal.

## [2026-11-06] Require saved plan units before implementation
Context: Seeded spec files and a saved report do not establish that every plan unit finished writing its spec.
Decision: Enable Implement only for a finished standalone plan with a saved report and all projected units in the saved state.
Reason: File existence alone can admit an incomplete plan.
## [2026-11-06] Drop only the invalid suggested action and say why in the reply
Context: An instruction over 2000 characters made decodeActions drop the whole skillbill-actions block. Only a chat.actions_invalid diagnostic recorded it, so the agent told the operator to click a start button that never appeared.
Decision: Each action is checked on its own. An invalid one is dropped, records chat.action_invalid (or chat.action_start_unknown), and appends one visible notice to the reply naming the label and reason. Valid siblings still become buttons. A block that does not parse, has more than 3 actions, or repeats a label still drops as a whole, with chat.actions_invalid and the same visible notice.
Reason: The operator is the only one who can see the app's verdict on a reply. Without the notice, a missing button looks like a UI bug and neither side knows to ask for a shorter instruction.

## [2026-11-05] List New task chats from every repository on the home
Context: The home showed one Continue row for the selected repository's newest draft.
Decision: The home lists up to 6 saved New Task drafts from every repository with resume and discard per row. "Show all" opens the /resume picker when more drafts exist. `refreshSaved` and the picker read every repository's drafts, and the list stays a plain capped column with no nested scroll.
Reason: Drafts in other repositories stayed hidden until the operator switched repository.
Superseded by: Scope the New Task resume palette to the picked repository (2026-11-06), for the picker only. The cross-repository home list decision stands.

## [2026-11-05] Keep spec deletion refusal in the runtime
Context: Desktop control facts and the runtime board projection can disagree while a goal changes state.
Decision: Guard desktop deletion with matching loaded canDelete verdicts and launch reservations, while allowing requests without loaded control. SpecBrowserService.deleteBundle retains the final refusal.
Reason: Desktop facts can be stale or absent, so they cannot replace the runtime check for a live goal or errors.

## [2026-11-05] Share bundle targets and Specs actions across surfaces
Context: A selected file needs its own tree highlight, while controls, favorites and reservations belong to its bundle. Menus and the command palette expose the same actions.
Decision: Resolve the parent bundle for controls and derive menu and palette entries from SpecsMenuModel. Keep extended popover keyboard handling opt-in and retain control identity when selection moves within a bundle.
Reason: A shared target prevents actions from operating on a file path and avoids reloading control within a bundle. A shared action source keeps order and availability aligned without changing other popovers.

## [2026-11-05] Admit Specs export through its preflight
Context: Export is offered when a bundle has an issue key and checkout, including while run control loads.
Decision: Send export to the existing runtime preflight without waiting for a control verdict, and check session and bundle identity across asynchronous steps.
Reason: Preflight owns export admission, including live-work refusal. Waiting for a separate control read would hide an action that preflight can assess.

## [2026-11-05] Bind a pending start to the selected bundle
Context: Starting without an agent requires a choice, while selection or the runtime verdict can change with the picker open.
Decision: Bind the pending start to selection identity and clear it on dismissal, selection change or loss of canStart. An ordinary agent choice does not start a bundle.
Reason: An agent choice must not launch a different bundle or bypass a changed runtime verdict.

## [2026-11-04] Keep archive admission and inspection in the runtime
Context: Desktop needs to refuse live export before file selection and show archive identity before import confirmation.
Decision: Delegate preflight and inspection through WorkControlRepository. Recheck export admission and bind import to the inspected fingerprint and selected database observation.
Reason: The runtime owns liveness and restore rules. The fingerprint prevents confirmation of one archive from authorizing a changed archive.

## [2026-11-05] Make the New Task repository pill static once a chat starts
Context: SKILL-467 allowed repository and agent changes after the first message. SKILL-482 requires a static repository pill once a chat starts.
Decision: The repository pill has no click or chevron and its popup does not compose after chat starts. The agent chip retains its rebind behavior.
Reason: The new requirement applies only to the repository chip. With its popup unavailable, repository and base-branch changes have no remaining trigger in the chat.

## [2026-11-05] Read New Task branches through a runtime port
Context: The picker needs local and remote refs and the default branch for the selected repository.
Decision: RepositoryBranchesPort uses a runtime Git adapter and the existing gitDefaultBranch read. Desktop reaches it through core/data.
Reason: Desktop has no Git code, and the pinned default must match the branch resolved for a blank base branch at runtime.

## [2026-11-05] Keep FileKit compatible with the installed runtime
Context: FileKit requests newer shared libraries, and its desktop transport requires JDK modules outside the image list.
Decision: Constrain shared serialization and JNA artifacts to runtime catalog versions, add jdk.net and jdk.security.auth, and use filekit-dialogs without its Compose artifact.
Reason: The packaged app excludes shared libraries supplied by the installed runtime. The Compose artifact would pull a different Compose version.

## [2026-11-05] Keep the repository picker open through folder selection
Context: A native folder dialog can dismiss the popup, and projectFor accepts folders outside a Git repository.
Decision: Ignore dismissal while the chooser is open, validate the folder through the branch read before project lookup, and retain errors and selection in the picker.
Reason: The operator needs to see errors and retry, and project lookup needs a validated repository root.

## [2026-11-05] Keep the highlighted branch by value across rereads
Context: A branch read can finish after the operator moves the highlight and change row order.
Decision: PickerHighlight stores the row key and resolves its index against visible rows.
Reason: An index alone could select a different branch when Enter follows a reread.

## [2026-11-05] Pass remote branch names through unchanged
Context: A remote-only branch may require its origin/ prefix for checkout, while later merge and PR paths can prepend origin/.
Decision: The picker passes the displayed remote name as the base branch, matching the previous typed input.
Reason: Stripping the prefix can make checkout fail. Run and merge handling do not yet share a branch representation.
Revisit when: Remote base branches share a representation across checkout and merge paths.

## [2026-11-05] Pass app-owned icon files to NativeTray
Context: Compose tray icon renderers and the path-based Tray API create temporary files outside the desktop temp authority.
Decision: Render PNG and ICO files under the desktop temp root and pass their paths directly to NativeTray. Retain the current and previous icon for each tray instance and render outside the availability gate.
Reason: Direct updates avoid the library's temp-file copy. Retaining the previous icon protects a path the native tray may still use, and rendering outside the gate permits recovery after failure.

## [2026-11-04] Keep spec favorite reveals pending through project loading
Context: Selecting a bundle immediately after switching projects can be dropped while the new tree loads. A favorite can also refer to a project other than the one currently followed.
Decision: Keep the requested project and bundle path pending until the matching tree loads, and give that request precedence over automatic project following. Open the Specs pane for the selection rather than navigating to a run detail.
Reason: The loaded tree is needed to reconcile the bundle selection, and a spec favorite identifies a bundle independently of any live run.

## [2026-11-04] Start chat actions in the chat's checkout or on the named branch
Context: An execution chat's agent works in the goal worktree, but start actions ran in the repository root, and a start for another branch ran against whatever the chat's checkout had checked out.
Decision: Chat actions carry an optional `branch`. `NewTaskStart` keeps `repoRoot` as the main repository for identity and reservations, and adds `checkoutRoot` and `branch` for where a standalone phase or operation runs; the runtime resolves the branch to a checkout. The execution chat passes its `checkoutRoot`. Standalone reservation keys include the branch so starts for different branches do not block each other. Monitor drops the branch and keeps `target` `branch:<name>`.
Reason: The reservation and the runtime launch ownership must stay keyed on the repository, while the working directory follows the checkout the operator meant.

## [2026-11-03] Claim destroyTree once so cancel cannot wait twice
Context: A cancelled runProcess calls destroyTree from the killer finally and again from the outer finally.
Decision: destroyTree returns unless live.remove(process) succeeds. Both call sites stay. The shutdown hook uses signalTree, SIGTERM only.
Reason: Without the claim, a process that survives SIGKILL would wait 8s and record chat.process_force_killed twice, which exceeds STOP_WAIT_MS. The killer must still escalate because it unblocks process.waitFor.

## [2026-11-03] Read chat stdout on a daemon thread
Context: useLines blocked in readLine, and flowOn made the collector wait for that producer. An orphaned child outside the descendant list can hold stdout after cancel.
Decision: stdoutLines reads on daemon thread chat-stdout into a buffered Channel. The flow emits those lines then waitFor, and cancel closes the channel and still runs destroyTree.
Reason: The collection must finish without waiting for the pipe to close. The daemon may live until the pipe closes; it holds no coroutine and cannot block JVM exit. Elapsed-time tests are forbidden, so AC-002 has no unit test.

## [2026-11-03] Wait for a stopped Claude job, then open anyway
Context: ClaudeChatTransport.stop cancelled the session job and registered opened a new process at once, so two claude processes could write the same session.
Decision: stop records the job in stopping before cancel. run joins that job for STOP_WAIT_MS (5s) before registered. On timeout it records chat.stop_wait_expired and continues. Different session ids do not wait on each other.
Reason: 5s is longer than SIGTERM plus force-kill. A stuck job must not block chat forever. Registering stopping before remove keeps a concurrent run from seeing the session gone with no entry.

## [2026-11-03] Keep a stopped Junie execution exchange
Context: JunieChatTransport saved exchanges only on Completed, so a cancelled turn vanished from the next prompt.
Decision: onCompletion saves history plus the prompt and trimmed reply when the cause is CancellationException, the reply is not blank, and scope is not NewTask.
Reason: The next execution turn reads store.exchanges. New-task Junie builds history from saved draft lines, which subtask 3 keeps current, so this path must not write them.

## [2026-11-03] Scope and resolve favorites by database file identity
Context: Run IDs can occur in different databases, files can be replaced at the same path, and saved runs can fall outside the Day board window.
Decision: Persist the normalized observed path, file key and work ID in versioned, length-prefixed keys. Resolve matching keys through session detail reads fenced by file observations, and retain unavailable or unsupported entries.
Reason: Board membership cannot establish whether an older run exists, and an ID alone cannot identify its database. File and session checks prevent stale results from opening the wrong run; retaining entries avoids data loss on transient failures or unsupported formats.

## [2026-11-03] Confirm favorite changes against settings and the active session
Context: A settings write can fail or finish as the user switches databases.
Decision: Render membership from confirmed settings, serialize toggles, and cancel or invalidate pending results when the session changes.
Reason: A failed write must not appear successful, and an old session's completion must not change the new session's control.

## [2026-11-03] Give non-Claude agents pasted images as file paths, Claude as content blocks
Context: Chat turns can carry pasted images. Claude's stream-json input accepts base64 image content blocks. Codex, Cursor and Junie take only text on stdin or argv.
Decision: Claude receives image blocks in the user message. The other three save each image to a per-session temp directory and append a footer listing absolute file paths. Each deletes the directory in end(sessionId), and saved history keeps only the typed prompt.
Reason: Inline bytes need a per-agent protocol the other CLIs lack, while a file path works for any agent that can read files. Keeping paths out of saved history stops later resends from pointing at deleted files.
Revisit when: Codex, Cursor or Junie accept inline image input, or an agent cannot read files outside its checkout.

## [2026-11-03] Read removed goal changes from committed refs
Context: A removed goal worktree cannot supply working-tree changes, but its branch commits remain accessible from the primary checkout.
Decision: Read the goal branch range from the primary checkout and hide Uncommitted and Outside scopes. Keep All available and prevent opening files from the removed worktree.
Reason: Committed refs preserve the goal's recorded changes without attributing the primary checkout's working files to that goal. Outside does not accept a goal head ref, so it could report the wrong data.
Superseded by: Desktop treats a missing worktree directory as Removed, whatever the runtime reports (2026-11-06)

## [2026-11-02] Queue New task messages while a turn runs
Context: Execution chat queues Send while a reply streams. New task disabled Send because canAdmitTurn required the thread not to be busy, so follow-up messages were dropped until the turn ended.
Decision: NewTaskChat enqueues on ChatThread.queuedState and drains like ChatWorkspace. The composer labels the button Queue, keeps the input focused, and shows the queued count. Discard, close and rebind drop the queue. Start goal run still waits until the chat is idle.
Reason: The thread already had a queue. Disabling Send was a fork of ChatComposer, not a New task requirement.

## [2026-11-02] Keep New task repository and agent chips usable after the first message
Context: After Send, the repository and agent chips still showed a hand cursor but ignored clicks, so the operator could not swap project or change the displayed model without Discard or New chat.
Decision: The chips stay enabled until a turn is running, a start is in flight, or the draft's repository is gone. Choosing a different repository, base branch or agent ends the current agent process and opens a new session on that binding, keeping the transcript. A chip that cannot be used does not show a hand cursor.
Reason: The earlier lock existed because the agent process runs in that checkout, not because the operator should lose the controls. Rebinding starts a new process in the new checkout or agent instead of swallowing the click. Start goal run already reads the draft's current repository and agent.
Supersedes: SKILL-459 subtask 3 locking the chips whenever a chat exists, and its non-goal "Changing the repository or agent inside a chat. Discard first."
Superseded by: Make the New Task repository pill static once a chat starts (2026-11-05), for the repository chip only. The agent chip decision stands.

## [2026-11-02] Reset the window to New task instead of Work
Context: New task is the default route when the app opens.
Decision: The initial stack and the root rebuilt after a database switch use NewTaskRoute. Links labelled Work continue to open Work.
Reason: The default applies wherever the window builds its root, while Work links retain their meaning. This replaces only the reset target in "Put the session id in every route's content key"; its content-key decision stands, but its "resets the stack to Work" no longer describes the code.

## [2026-11-02] Keep new-task drafts and chat in the window scope
Context: A conversation has no execution and must survive navigation and database-session changes. Saved drafts must also resume after restart.
Decision: NewTaskDraft holds the draft and chat in the window scope, independently of the screen view model. Saved drafts retain repository and agent session context.
Reason: Screen or database-session ownership would end the conversation on navigation or a session change. Snapshot state also avoids driving text input through an asynchronously collected flow, which can lose characters or move the caret.

## [2026-11-02] Limit the new-task readiness line to observable facts
Context: The design sample claims a loaded pack and a configured validation gate.
Decision: Readiness reports detected agents and the selected repository. A failed agent detection reports unknown readiness.
Reason: The desktop and SystemService.doctor() have no read for the loaded pack or validation gate, so those claims would be unverified.
Revisit when: The desktop gains a read for the loaded pack or validation gate.

## [2026-11-02] Hold the plan dot amber under reduced motion
Context: The design CSS leaves every step dot grey under reduced motion, while the intake asks for a stationary amber dot.
Decision: Stop the transition and hold the plan dot amber without a glow.
Reason: The intake takes precedence over the design CSS.

## [2026-10-31] Share one git lock and an immutable committed-change cache across change scopes
Context: Several change scopes can be open for one execution, and multiple windows can request the same committed ranges.
Decision: `GitReadLock` owns the AppScope mutex shared by `RuntimeWorkChangesRepository` and `RuntimeWorkChangeScopesRepository`. Each port call holds the mutex only for that call. `CommittedChangeCache` uses separate 512-entry LRUs for committed lists and patches and stores only listed, shown, binary, and too-large results. Outside lists stay out of the app cache because they depend on HEAD. Within the session, a single-slot memo keyed by worktree, target branch and subtask ranges serves them. They are re-read when the ranges change or when a non-running execution is opened. Within the session, `WorkingTreeReads` shares working-tree list reads per baseline: a caller waits for a read already in flight and reuses a non-failed result younger than the 3 second refresh interval, so one baseline costs at most one git read per interval no matter how many screens or windows observe it.
Reason: Scope reads now share the existing single-git-read limit, while repeat reads across windows and session switches reuse completed commit-to-commit results. Working-tree results and failures stay out of the app cache.
Revisit when stats loads for runs with 100 or more subtasks measurably delay the running scope refresh.

## [2026-10-31] Serialize execution git change reads under one app-wide lock
Context: The changes panel polls git for the open execution every 3 seconds while it runs.
Decision: ChangeObservations calls workChangesPort on the IO dispatcher, outside RuntimeReads. RuntimeWorkChangesRepository owns one Mutex, passed into every session call, that serializes each changes and fileDiff read. Only the open execution's baseline is read; board rows never call this repository.
Reason: One app-wide lock keeps at most one git process for this feature running, including across a database session switch, which a session-scoped lock would not cover.
Revisit when: The app needs change lists for several executions at once.
Superseded by: Share one git lock and an immutable committed-change cache across change scopes (2026-10-31)

## [2026-10-31] Resume agent CLI sessions of the worktree with /resume
Context: The operator needs to pick up an earlier conversation in the chat, as `claude --resume` does in a terminal, on every chat agent and every agent profile (`~/.claude-*`, `~/.codex-*`, `~/.cursor-*`). Chats lived in memory, and codex, cursor and junie kept their resume handles in in-memory maps.
Decision: Typing `/resume` in the composer highlights the command and lists, newest first, every claude, codex and cursor session whose working directory is the chat's worktree, read from each profile's own store: Claude `projects/<path>/*.jsonl`, Codex `sessions/**/rollout-*.jsonl` with `session_index.jsonl` names, and Cursor `chats/*/*/meta.json`. Profiles are the default directory, `~/.<agent>-*` siblings holding that store, and directories named by CLAUDE_CONFIG_DIR, CODEX_HOME, CURSOR_CONFIG_DIR, XDG_CONFIG_HOME or the app's Claude setting. Picking one ends any running turn, shows the session's last 10 operator and agent messages, and continues it on its agent and profile: the transport sets CLAUDE_CONFIG_DIR, CODEX_HOME or CURSOR_CONFIG_DIR from ChatAgent.profile, and the next turn sends the full snapshot. ChatStore writes one JSON file per chat below `HostEnvironment.userHome`, using `CHAT_STORE_PATH`, after each finished turn with its clean lines, agent and profile, and the codex thread id, cursor chat id and junie exchanges. A session the app started shows those clean lines instead of prompts carrying the snapshot. Junie has no session store, so it lists only chats the app saved for the execution. The store keeps the newest 20 chats per execution and drops handle-only files older than 7 days.
Reason: Each agent's own session carries the full context, so the app reads only what the picker shows and resumes the session in the profile that owns it.
Revisit when: Cursor messages are needed in the transcript; they sit in a SQLite store the app does not read, so a resumed Cursor session shows only a notice. A session also cannot resume after its worktree moved.

## [2026-10-31] Run chat turns with the execution's own permissions on every agent
Context: The operator asked for the chat to do anything the execution agent can, with no limits added by the app, and for chat on codex, cursor and junie. Read-only turns were denying `gh pr view` and similar commands.
Decision: Claude turns pass --dangerously-skip-permissions and drop --restricted, --tools, --allowedTools, --permission-mode and --permission-prompts. Codex turns pass --dangerously-bypass-approvals-and-sandbox with shell_environment_policy.inherit=all, Cursor turns pass --force, --trust and --approve-mcps, and Junie runs headless as the launcher runs it. ReadOnly and Act still pick the scope prompt and drive pause-and-act, but limit no tools. Codex and Cursor resume the thread or chat id their first turn reported, held in memory per chat session. Junie's text output carries no session id, so each Junie turn sends the earlier exchanges of the chat, newest first up to 120,000 characters. The operator can switch a chat's agent from the composer chip; the switch takes that agent's REASONING-tier model, or the CLI default, and starts a new agent session with the full snapshot.
Reason: Executions already run these agents with permission checks off, so a stricter chat only blocked the operator. A switched agent cannot resume another agent's session.
Supersedes: "Allow shell commands in chat act mode", "Run chat turns restricted, prompt-free and without the prompt snapshot" (the snapshot flag stays off), the read-only profile in "Let the chat workspace own pause, act and resume", and "Show unsupported chat agents instead of switching to Claude" for codex, cursor and junie.

## [2026-10-31] Return executable chat suggestions as reply metadata
Context: Operators want buttons for concrete actions proposed in an agent reply.
Decision: The Claude adapter requests a trailing `skillbill-actions` fenced JSON array with at most three label, instruction and mode records. It hides metadata across stream chunks and emits typed suggestions only after a completed turn. Invalid metadata preserves the visible answer, produces no buttons and records `chat.actions_invalid`. A click is bound to the latest completed reply and sends its exact instruction through the existing send or pause-and-act path. Older, busy and unavailable suggestions cannot execute. Labels state when a running execution will pause.
Reason: Typed suggestions give each button a specific instruction while retaining streamed Markdown answers. The workspace remains the owner of pause, act and resume behavior.

## [2026-10-31] Allow shell commands in chat act mode
Context: The operator wants the chat agent to run shell commands for the selected execution.
Decision: Act turns allow the Bash tool without command-prefix restrictions and tell the agent it can run shell commands. Running executions retain the existing read-only command allowlist. This supersedes the act-mode command restrictions in the earlier permission decision.
Reason: Fixing an execution can require Git, build tools and project scripts. The existing pause-and-act flow provides act mode while keeping the execution stopped during changes.
Superseded by: Run chat turns with the execution's own permissions on every agent (2026-10-31)

## [2026-10-31] Start chat turns through a POSIX login shell
Context: An app launched from the Dock may not inherit the login PATH, so claude, skill-bill and gh may not resolve.
Decision: ClaudeChatTransport execs claude through $SHELL -l -c when SHELL is sh, bash, zsh, dash or ksh, through /bin/sh for any other shell, and as bare claude when SHELL is unset. Stdout lines before the first JSON object are skipped and recorded as chat.login_output_skipped.
Reason: The login profile supplies PATH. Fish and other non-POSIX shells expand $0 and $@ differently, so they cannot run the exec wrapper. Profiles can print to stdout, and decoding those lines would end the turn as StreamMalformed.

## [2026-10-31] Treat login-shell exit 127 as a missing claude binary
Context: Criterion 2 maps a start IOException to BinaryMissing and a non-zero exit without a result to ProcessFailed. Behind the login-shell wrapper, a missing claude is shell exit 127, not an IOException.
Decision: Exit 127 without a result line maps to BinaryMissing. Any other exit without a result, including 0, maps to ProcessFailed with the last 2000 stderr characters.
Reason: Without the 127 case a missing binary reads as a process failure, and IS-003 cannot hold. Exit 0 without a result counts as a failure so a turn never ends silently.

## [2026-10-31] Bound the chat stderr drain and tolerate a closed stdout
Context: A descendant of claude can hold stderr open after claude exits, and stdout can close while the launcher is reading it.
Decision: ProcessChatLauncher waits at most STDERR_DRAIN_MS (2 seconds) for the stderr drain and reports the tail read so far. An IOException while reading stdout ends the line stream, and Exited still reports the exit code and stderr tail.
Reason: An unbounded join would hang the turn after claude exits. Dropping Exited on a read error would hide the failure from the transport.

## [2026-10-31] Run chat turns restricted, prompt-free and without the prompt snapshot
Context: Claude Code 2.1.314 snapshots the first turn's system prompt and replays it on every resume by default.
Decision: Every turn passes --restricted, the mode's --tools and --allowedTools, --permission-mode dontAsk, --permission-prompts none and --system-prompt-snapshot off. The read-only profile has no Edit or Write and allows named read-only skill-bill commands and MCP tools plus gh issue create on Sermilion/SkillBill. Turns never bypass permissions or disable session persistence.
Reason: A headless turn cannot answer permission prompts, so anything outside the allowlist must be denied outright. The mode can change between turns, so the scope prompt has to be sent fresh on each one.
Superseded by: Run chat turns with the execution's own permissions on every agent (2026-10-31)

## [2026-10-31] Let the chat workspace own pause, act and resume
Context: A chat must not change a running execution unless the operator asks, but the operator may want a fix mid-run and may close the tab while the act turn streams.
Decision: Turns for a running or Active execution use ReadOnly mode. As the later permissions decision records, this selects a prompt and does not enforce tool restrictions. pauseAndAct calls pauseNow when pauseEligible, else cancel when controlEligible, waits for a detail header with running false, runs the turn in act mode, then relaunches with the execution's agent. The relaunch is a window-scope job that joins the act turn; closing the tab cancels the turn but not the relaunch, and closeAll() cancels pending relaunches before it cancels sessions.
Reason: Closing a tab or a failed turn should not strand the execution paused. Quitting the window mid-act should leave it paused rather than relaunched against a half-finished change. If sessions were cancelled first, the waiting relaunch would see its turn end and launch anyway.
Alternatives considered: Letting the agent pause and resume through skill-bill commands, rejected by plan decision D1; parenting the relaunch to the session job, which drops it when the tab closes.

## [2026-10-31] Advance chat context only after the agent responds
Context: A turn can fail before the CLI session starts, for example when the agent binary is missing.
Decision: A session marks itself started and records the delivered snapshot only after the turn emits a TextDelta, ToolUse or Completed event. A turn that emitted only Failed leaves both unchanged.
Reason: The next turn then goes out as a first turn with --session-id and the full snapshot. Without this, the agent would get a diff against context it never received, or a resume of a session that does not exist.

## [2026-10-31] Show unsupported chat agents instead of switching to Claude
Context: Only Claude has a chat transport, while executions also run on codex, cursor and junie.
Decision: AgentExecutionChatRepository emits one failed AgentUnsupported event, records chat.agent_unsupported and starts no process. The tab shows `Chat is not available for agent <id> yet` and Send stays disabled.
Reason: The chat must use the execution's agent. A silent switch to Claude would answer with a different agent and model than the one running the execution.
Superseded by: Run chat turns with the execution's own permissions on every agent (2026-10-31)
Revisit when: A ChatTransport exists for another agent.

## [2026-10-31] Share the right slot between chat and Specs
Context: The chat pane and the Specs pane both want the main window's right side.
Decision: ShellPanes keeps specsOpen and chatOpen exclusive; setting one clears the other. ChatWorkspace mirrors its pane state into chatOpen and hides the pane without ending sessions when Specs opens. The details pane stays hidden while chat is open.
Reason: One right pane at a time leaves the work area width and drawer behaviour unchanged (plan decision D6).

## [2026-10-31] Extend terminal pane chrome and SbInput for the chat
Context: The chat needs tabbed pane chrome and a composer where Shift+Enter adds a newline. SbInput was single-line at a fixed height, and TerminalPaneChrome hard-coded terminal labels and controls.
Decision: Option B from the subtask spec: add icon, noun, shortcut labels and nullable new-tab, resize and rename callbacks to TerminalPaneChrome, and multiline to SbInput. Defaults keep existing callers unchanged, and no component or token was added.
Reason: It keeps the Shift+Enter requirement and gives the chat its own accessible labels instead of "terminal" ones.
Alternatives considered: Option A, dropping Shift+Enter and using the chrome as written, which would label the chat's controls as terminal ones.

## [2026-10-31] Put the app jar first on the packaged class path
Context: Sorting the packaged jars can place ComposeNativeTray's own libLinuxTray.so ahead of the app's patched copy.
Decision: The launcher orders its own jar before the other app entries and the runtime jars. AppClassLoaderTest checks that the patched resource wins.
Reason: Otherwise the tray patches applied by build-tray.sh never load in the packaged app.

## [2026-10-30] Require equal read_schema in the desktop runtime gate
Context: desktop-tray's gate accepts an installed runtime with the same commit but a different read_schema.
Decision: InstalledRuntimeBuildGate returns Available only when both identity records parse, describe the same build, carry equal read_schema and report a supported compatibility. Every refusal folds into NotInstalled, Unreadable or Mismatched with the reinstall command.
Reason: A same-commit runtime at another read_schema reads a different database shape and must be refused as Mismatched. The refusal panel distinguishes only those three problems.

## [2026-10-30] Make desktop check build the distribution
Context: In desktop-tray, verifyPackagedRuntimeFree runs only as a finalizer of createDistributable, so check never inspects the image.
Decision: In desktop/, check depends on verifyPackagedRuntimeFree as well as the shared-runtime version check. This is the one intended divergence from desktop-tray's wiring.
Reason: The validate gate runs check alone, and a dependency that drags a dev.skillbill runtime jar into the image must fail that gate.

## [2026-10-30] Pin shared runtime libraries with dependency constraints
Context: Compose lifecycle pulls kotlinx-serialization 1.7.3, and pty4j with jediterm pull newer jetbrains annotations and slf4j-api than the runtime uses, so verifySharedRuntimeVersions failed.
Decision: :app constrains kotlinx-serialization-core to the catalog version. feature/terminal pins annotations strictly and constrains slf4j-api to the runtime's versions. The version check stays unchanged.
Reason: The image excludes these libraries and the installed runtime supplies them at run time, so the desktop side must resolve the runtime's versions. Loosening the check would hide the next drift.
Alternatives considered: Excluding the transitive modules, which pty4j and jediterm still need at run time.

## [2026-10-30] Put the session id in every route's content key
Context: A database switch must drop every session-scoped view model and observation without closing the window or its terminal shells.
Decision: RouteEntry content keys are the route's entry key plus the session id, with a null session for Settings. A new or closed session resets the stack to Work, keeps Settings when it is on top and clears the selection.
Reason: New content keys clear each old entry's ViewModelStore without a separate entry scope. Terminal shells live outside NavDisplay, so they survive the switch.

## [2026-10-30] Fail a terminal tab whose directory is missing
Context: desktop-tray started the shell in home when the requested directory was gone, which looked like success.
Decision: PtyShellLauncher keeps the requested directory and returns Failed with a Directory not found message. The workspace shows one toast and removes the tab. Only a request without a directory falls back to the latest selection's root, then home.
Reason: A shell in home hides a deleted worktree or moved checkout.
Alternatives considered: An inline Retry in the pane, a second failure path for a rare case.

## [2026-10-30] Page detail lists by the runtime's per-list cursor
Context: The spec named the paging token an anchor, but in WorkDetailPagePosition an anchor marks where the current page started and only the cursor addresses the next page.
Decision: DetailSections.Loaded exposes listCursors. WorkDetailRepository.loadList keeps its anchor parameter name, documented as the token from listCursors, and data sends it as the cursor. A refresh re-reads each paged list from its head at its loaded size.
Reason: Sending the anchor would re-read the current page instead of the next one.

## [2026-10-30] Refresh failed spec snapshots explicitly
Context: The shared spec tree flow can keep an unavailable result after its first snapshot read, so resubscribing cannot implement Retry.
Decision: SpecRepository.refreshTree sends a per-project refresh signal into the session's tree observation.
Reason: Retry needs a fresh snapshot without polling and without changing who owns the directory watcher and commit signals.

## [2026-10-30] Carry runtime completion and QA facts for notifications
Context: Transition notifications need completion source, standalone execution and QA outcome, which WorkItem previously dropped.
Decision: Map those facts from runtime board records into WorkItem and keep notification classification in the app. Native tray and notification services stay in the app; the tray feature uses core models only.
Reason: The facts separate goal completion, standalone phase completion and incomplete QA without guessing from a terminal state.

## [2026-10-30] Map unknown subtask statuses to Pending
Context: The runtime can add a subtask status before the desktop recognizes it.
Decision: StatusKindMapping in core/ui maps any unrecognized subtask status string to Pending.
Reason: A rendering helper must not crash the board on a new status. core/ui has no logger, so the fallback records no diagnostic.

## [2026-10-30] Keep desktop preferences in DataStore without a Room database
Context: The new app needs preferences, but its runtime data remains owned by runtime services and its terminal tabs live in memory.
Decision: Store preferences in core:datastore and defer a Room database until the app has structured data of its own.
Reason: Adding another persistence model now would introduce behavior outside the handoff's scope.

## [2026-10-30] Keep the new desktop app beside the shipped tray
Context: The handoff leaves the choice of replacing desktop-tray in the installer open.
Decision: Build the new app as a standalone desktop build while the installer and release assets continue to ship desktop-tray.
Reason: Switching the shipped app requires an operator decision.

## [2026-10-30] Let settings own contributed page routing
Context: Multiple features contribute settings pages, while the navigation host selects one entry for a route.
Decision: Settings owns SettingsRoute and dispatches page bodies to their contributing FeatureEntry. Page selection remains local to Settings.
Reason: Route ownership must not depend on set iteration order, and Back and Esc should leave Settings in one step.

## [2026-10-30] Use one class loader for runtime refusal and retry
Context: The unavailable panel must retry the runtime gate without restarting the process.
Decision: Load the refusal panel and the app in one app class loader, adding runtime jars after a successful retry. The refusal entry avoids types absent before that retry.
Reason: Skiko binds its native library to the first class loader that loads it, so a bootstrap-loaded panel prevents an in-process retry.

## [2026-10-30] Close the old database session before opening another
Context: A database switch must stop old observations and avoid concurrent graphs holding read connections.
Decision: Serialize session changes, cancel and join the old session, release its reads, then open the new graph. Window-scoped terminal shells remain alive.
Reason: Joining before release prevents an old read from reopening connections and prevents stale observations reaching the new session.

## [2026-10-30] Follow runtime board placement and schedule deadline refreshes
Context: Membership and freshness may change without a database commit, and the runtime owns board placement.
Decision: Use runtime liveSection and order for board sections. Schedule one refresh at the earliest deadline strictly after observedAt, replacing it after each read.
Reason: Client placement would duplicate policy, while excluding past deadlines prevents an immediate refresh loop.

## [2026-10-30] Keep terminal control keys with the shell
Context: Compose does not receive keys while the Swing terminal has focus, and terminal control keys have shell meanings.
Decision: Handle only Ctrl+Backquote and Ctrl+Shift+Backquote over the terminal; send other keys to the PTY.
Reason: Taking Ctrl+K or Ctrl+I would break shell editing and Tab.

## [2026-10-30] Register login items before saving preferences
Context: OS registration or the following preference write can fail.
Decision: Apply the OS change first, persist after success, and attempt compensation if persistence fails. Report possible divergence if compensation also fails.
Reason: The saved toggle must not claim a registration the OS refused. The desktop uses its own Linux autostart entry to avoid overwriting the tray's entry.

## [2026-10-30] Isolate desktop benchmark data and execution
Context: Benchmark runs must not alter a supplied store or the user's settings, and routine checks should not execute timed workloads.
Decision: Snapshot supplied databases through a read-only SQLite connection, use a temporary home and an opt-in task, and use fresh class loaders for cold-start samples.
Reason: The snapshot includes WAL changes without writing the source; the temporary home isolates preferences.


## [2026-10-31] Run runtime calls on the shared IO pool
Context: `DatabaseDispatcher` capped runtime reads and writes at two. Cancelled reads keep their slot until they finish, so a burst of spec tree reads after a delete held up the refresh behind them.
Decision: Run runtime calls on `Dispatchers.IO` without a separate cap.
Reason: The IO pool already bounds blocking threads. SQLite in WAL mode runs concurrent readers beside one writer, and no caller relies on the cap for mutual exclusion.

## [2026-11-02] Blend the Swing terminal beneath Compose overlays
Context: The terminal's SwingPanel painted over the Changes dialog and its scrim.
Decision: DesktopLauncher enables `compose.interop.blending` before either the runtime refusal window or the application initializes Compose.
Reason: SwingPanel otherwise paints above Compose content regardless of composable order. Blending lets dialogs, scrims and other overlays cover the terminal while its session stays attached.

## [2026-11-04] Keep review submission feedback in the diff pane
Context: Review publishing needs a short success message tied to the comment bar and diff context.
Decision: Render the review toast in the Changes diff pane and expire it after 2.6 seconds through the view-model coroutine delay.
Reason: The application toast stack has a fixed 5-second lifetime and appears outside the review context.
