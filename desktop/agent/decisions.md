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

## [2026-10-31] Let the chat workspace own pause, act and resume
Context: A chat must not change a running execution unless the operator asks, but the operator may want a fix mid-run and may close the tab while the act turn streams.
Decision: Turns for a running or Active execution use the read-only profile. pauseAndAct calls pauseNow when pauseEligible, else cancel when controlEligible, waits for a detail header with running false, runs the turn in act mode, then relaunches with the execution's agent. The relaunch is a window-scope job that joins the act turn; closing the tab cancels the turn but not the relaunch, and closeAll() cancels pending relaunches before it cancels sessions.
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

