## [2026-11-07] SKILL-453 history pointer
Cross-cutting approval broker, provider adapters, parked-operation cards and limits are recorded in the root [agent/history.md](../../agent/history.md#2026-11-07-skill-453-operator-approval-cards-and-provider-refusal-boundaries).

## [2026-11-04] SKILL-474: export and import goal archives
Areas: desktop/app, desktop/core/data, desktop/core/domain, desktop/core/ui, desktop/feature/specs, desktop/feature/work, runtime-goalrunner
- Specs and Work offer export and global import with native file selection. Live export opens a pause-first dialog before file selection.
- WorkControlRepository delegates archive operations to the runtime. Import confirmation names the issue, branch and commit; the runtime checks the inspected archive fingerprint before restore.
- Archives do not include worktrees or unpublished git objects. Import does not check out a branch.
Feature flag: N/A
Acceptance criteria: 4/4 implemented


## [2026-11-04] SKILL-478 spec bundle favorites
Areas: desktop/app/shell, desktop/core/domain, desktop/core/datastore, desktop/core/testing, desktop/feature/specs
- FavoriteSpecKey identifies a bundle by project ID and spec-relative path. DataStore persists spec favorites separately from run favorites with versioned, length-prefixed keys. Database session changes do not define spec identity.
- The Specs toolbar offers a favorite toggle only for bundles. The Favorites sidebar lists run favorites first, then spec favorites ordered by project and path; unavailable spec entries remain removable.
- A spec favorite opens the Specs pane and selects the bundle after its project tree loads. The pending reveal takes precedence over automatic project following.
- Limits: macOS and Windows desktop checks were not run on the Linux host. QA or validation reported capability_absent.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-03] SKILL-469 subtask 1: agent process ending
Areas: desktop/core/data chat
- ChatProcesses.destroyTree snapshots descendants, sends SIGTERM, waits STOP_GRACE_MS (3s), force-kills survivors, records chat.process_force_killed once, then waits FORCE_WAIT_MS (1s). live.remove makes a second destroyTree from killer plus outer finally a no-op. The JVM shutdown hook uses signalTree, SIGTERM with no wait.
- runProcess reads stdout on daemon thread chat-stdout through a buffered Channel. Cancel closes the channel and still runs destroyTree, even if an orphan holds the pipe.
- ClaudeChatTransport.stopping joins the prior session job up to STOP_WAIT_MS (5s) before registered. Timeout records chat.stop_wait_expired and opens anyway. Different session ids do not wait on each other.
- JunieChatTransport.onCompletion saves a cancelled execution turn when the reply is non-blank and scope is not NewTask.
- Limits: AC-002 has no unit test because the holder case differs only in elapsed time. A descendant still cleaning up after SIGTERM can be force-killed once the parent exits. A blocked stdin write can still delay stop. Real CLI resume after kill stays with QA.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-03] SKILL-476 add run favorites
Areas: desktop/app, desktop/core/domain, desktop/core/data, desktop/core/datastore, desktop/core/designsystem, desktop/core/testing, desktop/feature/detail, desktop/feature/settings
- FavoriteWorkKey identifies a run by normalized database path, file key and work ID. DataStore persists versioned, length-prefixed keys and preserves unsupported entries on updates. reusable
- Detail headers expose an accessible favorite toggle backed by confirmed settings. Writes are serialized, failures show a toast, and session changes invalidate pending results.
- The fixed-height sidebar resolves saved IDs through session detail reads beyond the board window. It opens resolved runs and keeps unavailable entries removable across database identities.
- Window-scoped sidebar state orders keys, resolves up to four at a time, publishes results as they arrive and discards stale session or identity results. Refreshes during a batch coalesce into a follow-up pass. reusable
- Limits: macOS and Windows desktop checks were not run on the Linux host. QA or validation reported capability_absent.
Feature flag: N/A
Acceptance criteria: 2/2 implemented

## [2026-11-03] SKILL-450 subtask 1: deliver pasted images to chat agents
Areas: desktop/core/domain, desktop/core/data
- ChatImage (bytes, mediaType) is a plain class with content-based equals and hashCode. ChatTurnRequest gains images as its last, defaulted parameter, so existing callers are unchanged. (reusable)
- Claude turns send user content as an array: a text block (omitted when the text is blank) then one base64 image block per image, in order. A turn with no images still sends plain string content, as before.
- Codex, Cursor and Junie cannot take inline bytes. Each image is written to a temp file and the prompt gains an "Attached images" footer with one "[Image #N] <path>" line per image; the footer stands alone when the text is blank. turnWithImages in ChatImageFiles.kt wraps those transports and does no IO without images. (reusable)
- Files live under the system temp dir in skill-bill-chat-images/<sanitized session id>/, are .png unless the media type is image/jpeg, and are reported by real path. Each of the three transports deletes its session directory in end(sessionId); a session id resolving outside the root is rejected on write and ignored on delete. Deletion does not follow symlinks.
- A write failure emits ChatEvent.Failed(ProcessFailed, "Could not save a pasted image: ...") and starts no process; the next text-only turn runs normally.
- Junie's resent history keeps only the typed text, never the footer or temp paths. The Claude session reader keeps only text blocks, so image blocks do not reach the transcript.
- Limits: Claude CLI acceptance of image blocks was not exercised against the real CLI. Session files persist until the transport's end or JVM exit.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-11-03] SKILL-468 subtask 4: route desktop views by worktree state
Areas: desktop, runtime-kotlin/runtime-engine, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-goalrunner
- Runtime projects and persists active, retained and removed goal worktree state. Desktop detail displays that state and launch warnings.
- Active and retained goals route Changes, terminals and chat to the goal worktree. Removed goals route terminal and new chat turns to the primary checkout, and chat starts a new session when its checkout changes.
- Removed goals read Changes from the committed goal branch range. Uncommitted and Outside scopes are hidden, and files cannot be opened from the removed worktree. A missing goal branch reports an unavailable baseline.
- Limits: macOS and Windows desktop checks were not run on the Linux host. QA reported capability_absent.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-31] SKILL-455 desktop change scope data
Areas: desktop/core/domain, desktop/core/data
- ChangeScope models and pure rules resolve All, subtask ranges, Outside and Uncommitted reads, selectable ranges, file touch counts and conservative hunk attribution. reusable
- WorkChangeScopesRepository exposes session-following subtask and scope observations and file diffs. RuntimeReads supplies subtask metadata; git reads go through WorkChangesPort. reusable
- Running working-tree scopes refresh at the shared 3-second interval. WorkingTreeReads shares in-flight and recent working-tree list reads per baseline, so screens and windows observing one baseline cost at most one git read per interval.
- Completed commit ranges use app-wide bounded list and patch caches (CommittedChangeCache). Outside lists use a session single-slot memo keyed by worktree, target branch and ranges; a non-running execution reads Outside fresh. Subtask stats prioritize the running subtask.
- Outside diffs concatenate matching commit patches in order and label hunks with short SHAs. Multi-subtask ranges label hunks only when the contributing patches support attribution.
- Stacked-branch goals expose All and Uncommitted only. Outside reads can report TooManyCommits; failed scope reads record desktop_work_change_scopes_read_failed.
- GitReadLock shares one app-wide mutex between the existing changes repository and change scopes. See the decision on shared git reads and committed-change caching.
- The Changes window and detail strip that consume this live in desktop/feature/changes (see its history).
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-31] SKILL-454 desktop changes domain and data layer
Areas: desktop/core/domain, desktop/core/data
- ChangeModels.kt adds ChangeBaseline (SubtaskStart, RunStart, TargetMergeBase), ChangedFile with a derived binary flag, ChangeSet, ChangesState, FileDiffState and the diff hunk and line models. Detail gains changeBaseline as its last, defaulted parameter, and RuntimeDetailMapper maps it. reusable
- WorkChangesRepository (observe, fileDiff, open) is bound app-wide by RuntimeWorkChangesRepository, which delegates to a session-scoped ChangeObservations on DatabaseSessionComponent. reusable
- observe emits NoSubtask with no read for a null baseline. Otherwise it emits Loading and reads through workChangesPort; while running it repeats CHANGE_REFRESH_INTERVAL (3 seconds) after each read ends, and idle reads once. Callers flip running by re-calling observe, which emits Loading again.
- Git reads run on the IO dispatcher outside RuntimeReads. One Mutex owned by the app-scoped repository serializes every changes and fileDiff read, across session switches too.
- parseUnifiedDiff is pure Kotlin with no diff library. It tracks the remaining old and new counts from each hunk header, so body lines starting with --- or +++ stay body lines, and its insertions and deletions match git numstat. reusable
- open skips Deleted files and launches the worktree-resolved path with EDITOR_VARIABLES stripped, so the OS opener runs. EDITOR_VARIABLES is now internal in SpecObservations and shared with spec file opening.
- Failures record desktop_work_changes_read_failed, desktop_work_changes_diff_failed or desktop_work_changes_open_failed. A thrown read also records desktop_read_failed through readOrProblem.
- Limits: with no session, observe shows sessionFlow's Loading or Unavailable(Storage), not Unavailable(Runtime). The detail feature supplies running from WorkItem.running (see desktop/feature/detail history).
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-31] SKILL-453 execution agent chat panel
Areas: desktop/core/domain, desktop/core/data, desktop/core/ui, desktop/core/designsystem, desktop/core/testing, desktop/feature/chat, desktop/feature/detail, desktop/app, desktop/gradle
- ExecutionChatRepository and the per-agent ChatTransport seam stream chat events; Claude, Codex, Cursor and Junie have transports. Agent and reasoning model resolve from the execution and configuration, with recorded fallbacks. reusable
- Claude and other transports run CLI turns through a shared launcher; cancellation destroys the process, shutdown destroys live processes, and stderr draining is bounded. Sessions resume where supported; Junie resends earlier exchanges.
- ChatWorkspace keeps an in-memory session per execution, sends a full snapshot first and diffs later, and owns pause, act and resume. Closing a tab discards its session. reusable
- ReadOnly and Act select the scope prompt and pause-and-act behavior, but do not enforce tool restrictions. Agents run with execution permissions, including Claude's permission bypass. This departs from the spec's hard allowlist and read-only requirements.
- The pane shares the right slot with Specs, opens from the detail header or mod+L, and renders streamed Markdown, tool groups and suggested actions. ChatCommands and FloatingPane are reusable.
- TerminalPaneChrome accepts chat-specific labels and controls; SbInput supports multiline input. FakeExecutionChatRepository supports chat consumers. reusable
- The packaged launcher prioritizes the app jar so its patched Linux tray library loads, and the tray script maps right-click to the click callback.
- Limits: chat state is not persisted by the app; Codex and Cursor stream shapes were not checked against live CLIs and Junie was unavailable. No Bedrock or Vertex model remapping. CHAT_SHORTCUT uses os.name in commonMain and relies on the desktop JVM target.
Feature flag: N/A
Acceptance criteria: 2/4 implemented; criteria 1 and 3 lack the specified hard permission restrictions.

## [2026-10-30] SKILL-447 desktop application
Areas: desktop/app, core modules, feature modules, desktop build and CI, runtime-kotlin
- Added the standalone Compose desktop build with a matched-runtime gate, runtime-free distribution and shared runtime version checks. The refusal and retry path uses one app class loader.
- Reusable session and repository interfaces provide shared board, detail and specs observations, typed controls, launch reservations and DataStore preferences. Switching databases stops the old session and releases its reads before opening another.
- Added the design system, navigation shell, Work, History, Detail, Specs, terminal, settings and runtime configuration screens, plus tray and transition notifications using the shared board.
- Added platform packages, CI and an opt-in benchmark and packaged-data smoke. Terminal shells are window-scoped and survive database switches.
- Preferences use DataStore; no app-owned structured data or Room database was added. The installer continues to ship desktop-tray.
- ModuleGraphRules, PolicyRules and ProcessLauncherRules in :app jvmTest guard module edges, client-side work sorting or deadline reads, and process launches. A new module must join settings.gradle.kts and pass them. reusable
- :core:testing holds RecordingDiagnostics, FakeAppSettingsRepository, FakeSessionGraph, the WorkFakes repository fakes and the WorkItems builders. reusable
- Feature screens register keys with ScreenKeyHandler and gate text fields with gatesShortcutsWhileFocused. controlFeedback and storageFailureText in :core:ui are the single copy source for control toasts and storage problems. reusable
- Platform-specific tray, login and packaging behavior requires its respective host. Benchmark targets are measurements, not a passing guarantee.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

