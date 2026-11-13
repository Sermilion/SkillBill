## [2026-11-13] SONA-21 chat without a repository
Areas: desktop/core/domain, desktop/core/data/chat, desktop/core/data/control, desktop/feature/chat and newtask, docs/runtime-command-guidance.md
- MEDIUM feature. New task separates workingDirectory from nullable selectedRepositoryRoot. Window-supplied HostEnvironment.userHome supports ordinary chat, tools, file creation, saves and resume without selecting home through Git ancestry; branch and changes features require actual selection.
- ChatStore context version 1 preserves explicit null selection. Legacy bound drafts migrate atomically before projection, retaining source fields, identities, provider bindings, transcript, summaries and metadata. Typed failures retain unreadable originals and protect them from updates and orphan sweeping.
- One terminal skillbill-repository block proposes an exact path after successful completion. Guidance covers requested creation, cloning and StudioProjects lookup. Invalid metadata produces diagnostics and a visible notice; failed or stale validation retains selection and user files.
- reusable: manual and chat candidates share canonical exact-root validation through RepositoryBranchesRepository. Project lookup supplies display metadata only; an identical root retains the session and accepted key.
- Manual selection stays available after chat starts. Replacement waits for accepted saves and provider shutdown, persists before publication, and restores the old context on failure. Candidate settlement holds queue draining and carries ordinary queued text and images through replacement or recovery.
- Repository changes retain visible conversation as inactive history and clear accepted keys, branch, offer, changes and repository-specific MCP state. Old operation requests, observations and callbacks lose authority after replacement commits.
- reusable: OperationCardCoordinator accepts caller-owned admission checks and rechecks after saves. New task starts and card decisions require current selection and originating context/database authority; refusal never adopts a replacement database session or retries on selection.
- RuntimeWorkControlRepository validates canonical selected-root equality before all nine NewTaskKind dispatches. Database changes take refusal precedence; only GoalRun and PlanOnly apply new-work base-branch policy.
- Compatibility: legacy root-only constructors remain repository-bound, repositoryRoot remains the provider/approval directory, and execution-chat scope is unchanged. Changed-home resume stages a fresh provider session while retaining the original saved draft.
- Limits: admission proves validity at dispatch, not against later external deletion. No global CLI admission or repository-management service. Supplied capability_absent warning says validation changed code after QA settled; final-tree QA and native macOS/Windows behavior remain unverified.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-11] SKILL-538 rename desktop app to Sona
Areas: desktop/app copy, tray and packaging; desktop/core/data and designsystem; desktop/feature/chat, config, settings, specs and tray; desktop/build-logic; docs/assets; root installer/uninstaller; scripts smoke and USB display; runtime-cli installer fixtures
- MEDIUM feature. Desktop windows, menus, tray labels, settings, chat guidance, data messages and readable build diagnostics now call the app Sona.
- Renamed the canonical and docs Ping SVGs, generated PNG/ICO/ICNS and their consumers to Sona. Preserved the supplied drawing and selected design study; skillBillIcon and BrandMark keep their APIs.
- TrayIconRenderResult is reusable within tray consumers. It carries owned PNG bytes beside retained paths; macOS consumes bytes through JNI, keeps status dots and its native numeric title, and reports image failures through the existing fallback. Other platforms retain count badges.
- Native update and disposal wait for main-thread completion; cleanup clears callbacks. Package metadata and readers agree on Sona.app/Contents/MacOS/Sona, Sona/Sona.exe and Sona/bin/Sona, with sona launchers and sona.desktop.
- Activation and removal use current Sona targets and verified registrations. Linux ownership matches both Exec and Icon; staged registration writes and app backups restore prior outputs on failure. Historical apps, registrations and unrelated occupants remain outside cleanup.
- Retained CLI/MCP and package identities, /sona commands, environment names, saved state, bundle ID, Windows upgrade UUID and release archive names. Linux autostart keeps skill-bill-desktop.desktop with Name=Sona; no data migration.
- Existing prompt assertions and runtime-cli installer fixtures follow the new copy and package layouts. Regression coverage covers byte lifetime, bridge failures, coexistence, ownership refusal and registration rollback.
- Limits: the supplied capability_absent warning leaves visual QA unclaimed. Native macOS AppKit behavior and macOS/Windows package execution remain unverified by the supplied material.
- Renaming the package means an installed copy of the old app is not upgraded in place and has to be removed by hand.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-10] SKILL-529 desktop always allow similar
Areas: desktop/core/domain, core/data approval and chat, feature/chat production and JVM tests
- Pending approval cards show Always allow similar when the runtime supplies a suggested pattern and no pattern refusal; an inline editor prefills the pattern, saves through decide with alwaysAllow and an optional pattern, and stays open with runtime refusal text.
- Domain and data carry optional pattern through decide, card mapping and chat persistence; ChatApprovalStore writes rule_pattern only when set so older chat files still load.
- Card details, decision lines, outcome text and Repository rules name pattern rules; exact rules keep prior wording. reusable: ChatApprovalSimilarEditor beside existing controls.
- Limits: no desktop pattern grammar beyond blank trim; no editing saved patterns in the rules list; requires DesktopReadSchema 120 from runtime migration 120.
Feature flag: N/A
Acceptance criteria: 4/4 implemented across runtime and desktop

## [2026-11-08] SKILL-522 per-run build indicator placement
Areas: desktop/core/domain, core/data, core/ui, core/designsystem, feature/work and feature/detail; runtime build capture and work read contracts
- MEDIUM feature. Nullable WorkBuild carries current and previous build state, command metadata, timestamps, durations and exit code through board and detail mapping.
- WorkRow places BuildChip before Stalled for running and failed builds. Passed, finished and absent builds have no row chip.
- DetailScreen places the keyed BuildStrip immediately below the header and above pause notices for every non-null build, except standalone validation and Verify documents. Those rows can still show chips.
- WorkBuild.toSbBuild and showsChip in core/ui/status are reusable. Existing commit-signal refresh supplies new snapshots; component-owned LocalUiTicker advances presentation without added polling.
- Limits: no prototype hash routes, sidebar card, log streaming, cancel control or aggregate statistics. Unknown outcomes use finished, and unknown exit codes omit the exit segment.
Feature flag: N/A
Acceptance criteria: 3/3 placement subtask criteria implemented; 5/5 parent criteria implemented across runtime and desktop

## [2026-11-08] SKILL-520 contextual usage limits
Areas: desktop/app/usage, desktop/app/shell, desktop/app/di, desktop/core/domain/usage, desktop/core/data/usage, desktop/core/data/chat, desktop/core/data/session, desktop/core/ui, desktop/core/designsystem, desktop/feature/chat
- MEDIUM feature. Added a usage card above Specs in the fixed sidebar footer and a 316 dp details popup. It follows the selected New task chat or open run; Work and History remember a provider per window. Junie and unknown contextual providers hide the card.
- Typed usage separates unverified profile lookups, verified account/workspace subjects and session-only status. Exact decimal amounts retain units, currency, periods and observation times; missing data cannot imply unlimited allowance or request permission. UsageRepository and UsageObservationSink are reusable contracts.
- Codex capability acquisition uses the selected execution environment and a separate bounded ChatProcessLauncher mode. UTF-8 input has a 256 KiB line cap, 1 MiB response budget and 32-message queue; startup/initialize and reads have deadlines, and one application-wide startup slot stays owned through cleanup.
- Production numeric admission remains closed. Codex reports explicit capability/identity outcomes, Claude supplies only nonnumeric status from existing desktop sessions, and Cursor has no supported personal snapshot. Fixtures do not authorize a production numeric source.
- Claude status captures the effective profile, logical session and process generation before decoding chat events. Stop and completion revoke that generation, so late events and closes cannot alter a replacement process's status.
- One app-scoped ProviderUsageRepository shares bounded in-memory state, reservations, demand and refresh coordination. Codex pull policy uses 60-second active and five-minute idle intervals, a ten-second manual cooldown, source floors and 30-to-900-second jittered retries. Authentication suspension stops automatic work; the last visible lease stops pulls and application exit closes the supervised usage owner.
- UsageObservationMerge, UsageMeterRanking and UsageRefreshPolicy are reusable. Revision checks protect newer fields from older pulls; partial results preserve untouched ages, and reset expiry labels retained values without refilling them. Captured freshness remains separate from current scheduling demand.
- Window-scoped UsageSidebarModel follows route/database generations and the executing chat agent/session. Hidden, minimized, Settings and closed-drawer hosts release their leases; activity updates preserve context. Run scope says Current local account because historical identity is unverified.
- UsageCard, UsagePanel and UsageMeterRow are reusable presentation components. The card shows at most two ranked meters and details show all; reported values remain intact while visual progress clamps. SbPopover and ShellLayer bind dismissal and conditional focus return to the current context.
- Limits: production numbers, disk caching and historical-account capture remain outside this release; runtime-launched Claude sessions have no event bridge. Dedicated popup lifecycle/external-action coverage and native macOS/Windows checks remain unverified. The supplied environment_unavailable warning records that validation changed code after QA settled, so the published change differs from the tree QA tested; QA does not rerun.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
## [2026-11-07] SKILL-525 background chat summaries with marquee
Areas: desktop/app, desktop/core/data chat, desktop/core/domain, desktop/core/designsystem, desktop/core/testing, desktop/feature/chat and its newtask package
- MEDIUM feature. Recent chats and /resume rows prefer a saved one-sentence summary, retain their existing title fallback and image suffix, and match summaries in the resume palette filter. Open tabs, transcript headers and chat search keep their titles.
- Closing chats, leaving an idle chat and closing the window request summaries. Per-reply saves and hiding the pane do not. Summary update events refresh Recent chats; startup queues at most 20 stale chats, newest first, to recover work left by quit.
- ChatSummarizer is an app-scoped sequential queue with pending-request deduplication, a staleness read before each call, a 120-second timeout and diagnosed failures that leave the worker running. reusable
- One-shot calls use the chat's agent and account with Claude haiku, Codex gpt-6-luna or Cursor gpt-5.4-nano-none. They run outside repositories with tool-less, read-only or ask-mode arguments and bypass ChatTransport so chat resume state stays unchanged.
- ChatStore adds optional summary and summary_source_updated_at_ms keys without migration. Conversation saves preserve both and retain updated_at for unchanged lines. The dedicated writer updates existing records only, preserves ordering and stamps the summarized snapshot so newer turns remain stale.
- SbMarqueeText is a reusable single-line String or AnnotatedString component. Overflowing summaries scroll continuously; LocalReducedMotion selects a stationary ellipsis. Fallback titles keep their existing ellipsis, and the palette preview uses two lines without a marquee.
- Transcript input keeps the first operator line and newest operator/agent lines within 12,000 characters, with 2,000 per line. Output takes the first nonblank line, strips markers and quotes, and cuts at a word within 200 characters.
- QA repairs append an actual operator/agent turn before stale-summary assertions in ChatStoreTest and ChatSummarizerTest. A timestamp-only resave no longer represents a changed conversation.
- DesktopLauncher defaults Skiko to SOFTWARE on Linux before Compose starts and honors explicit skiko.renderApi and SKIKO_RENDER_API overrides.
- Limits: Junie and unmapped agents receive no summary; CLI sessions the app never saved keep fallback titles. Live summary QA needs an installed agent, which the isolated home lacks. The store format and repository API additions preserve existing callers.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-07] SKILL-521 live desktop QA preview
Areas: desktop/core/data, desktop/core/domain, desktop/feature/detail, platform-packs/kmp/qa, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-engine, runtime-kotlin/runtime-goalrunner
- MEDIUM feature. The existing details pane shows a watch-only preview for the selected standalone desktop QA execution, with Waiting, Live and Ended states, invocation identity and accessible status text.
- KMP QA guidance registers an owner-only live-preview child before private Xvfb capture. Registration refusal disables capture and retains limitation evidence. Cleanup stops registered producers before releasing the child through the existing resource registry.
- QaPreviewRequest, QaPreviewFrame and the cold QaPreviewRepository flow are reusable domain components without filesystem or Compose types. JVM data owns snapshot reads and decoding; the detail feature owns image conversion.
- QaPreviewSource confines read-only, no-follow opens of live-preview/Xvfb_screen0 through scoped SecureDirectoryStream handles. The bounded XWD v7 decoder checks offsets, masks, stride and allocation limits, accepts both pixel byte orders and returns opaque ARGB.
- Each observer reads one bounded snapshot at a time and waits 250 milliseconds after an attempt. Degraded-reason transitions emit bounded diagnostic categories. Rejected frames clear the image; later valid frames can recover.
- DetailViewModel keys observation by database session, work, selected invocation, directory and liveness. Mandatory route and pane visibility wiring cancels hidden collection; generation checks reject late frames. Terminal selections are Ended without source reads.
- Limits: private file-backed Xvfb, SecureDirectoryStream support and the supported XWD format are required. Ambient displays, goal QA and legacy runs without that source supply no preview. Frames may tear during repaint. No database or command-contract change.
- The latest frame fits the existing pane and preserves its aspect ratio. Localized labels and image descriptions identify the selected run. The panel has no recording, input, export or expanded-viewer controls.
Feature flag: N/A
Acceptance criteria: 1/1 implemented for the parent; 5/5 implemented for subtask 1
## [2026-11-07] SKILL-514 desktop source-run QA launch
Areas: desktop/app, desktop/build-logic/convention, desktop/core/data, desktop/core/domain, desktop/core/testing, desktop/feature/detail, desktop/feature/specs, desktop/QA.md, AGENTS.md
- QA launches with `cd desktop && ./gradlew :app:runFromSource`. The opt-in task builds same-checkout runtime-cli, stages runtime and app resources, and stamps both copied identity records with one fresh shared install run id per execution.
- SourceRunStaging and SourceRunPreparation provide reusable staging and seeding. Missing identities, runtime JARs or fixture data stop launch; a missing fixture preserves the previous scratch home.
- Each launch regenerates QAFIX database rows and spec bundles, then recreates and seeds `desktop/app/build/source-run/home`. The tasks remain outside normal check, build and assemble wiring.
- JVM home, preferences, resources and temp paths, child-process temp variables, runtime, database and agent configuration all point into source-run outputs. Blank TERM becomes `dumb` to preserve launch overrides.
- Preparation verifies Git worktree ownership and fixed checkout output paths, rejects redirected paths and linked entries, and protects operator state. QA output directories require exclusive task ownership.
- Linux source runs select Skiko SOFTWARE_COMPAT through both the JVM property and SKIKO_RENDER_API, overriding an inherited renderer setting. QA-2 remains open after three repairs; these settings do not establish interactive launch success.
- Specs carries the goal owner's checkout into purge requests. Runtime control projection disables ambiguous targets across a project's checkouts.
- AGENTS.md and desktop/QA.md name the source-run command, scratch layout and fixture behavior. The scratch agent configuration does not inherit the operator's login.
- Limits: fixture live states can change after the generator exits. macOS and Windows native behavior remains unverified. Gate rules and real-install behavior are unchanged.
Feature flag: N/A
Acceptance criteria: 5/5 implemented for the parent feature; QA-2 remains unresolved

## [2026-11-07] SKILL-517 QA device picker in sidebar
Areas: desktop/app/shell/qadevice, desktop/core/data, desktop/core/domain, desktop/core/datastore, desktop/core/designsystem, desktop/feature/chat, desktop/feature/settings; runtime-engine, runtime-cli, runtime-goalrunner, runtime-contracts, runtime-ports, runtime-infra/workflow
- MEDIUM feature. The sidebar footer has an upward device picker, and the New Task QA chip names the saved pick. Ready devices and stopped AVDs are pickable; leased, unauthorized, offline and unsupported devices are disabled.
- Window-scoped QaDevicePickerState owns cancellable discovery on open and explicit refresh, with no polling. Pure presentation rules match AVD picks to running emulators, retain missing picks and skip disabled rows during keyboard navigation. reusable
- Discovery resolves SDK tools through ANDROID_HOME, ANDROID_SDK_ROOT, repository local.properties and PATH. Injected command execution bounds output and subprocess duration; unknown running AVD identities suppress stopped candidates.
- Read-only lease snapshots join fingerprinted holders across normalized roots from the loaded Work board. Live and unverifiable owners remain occupied; unavailable occupancy is diagnosed and does not prevent adb discovery.
- AppSettingsRepository owns the persisted Serial/Avd pick and display name. Atomic writes preserve the previous pick on failure; malformed preferences decode to diagnosed auto without exposing stored identities. QaDevicePick and QaDeviceDiscoveryRepository are reusable.
- Eligible launches read one confirmed settings snapshot before launch writes and retain it through allocation retries. Read failure refuses launch. Work-board QA and goal relaunches replace recorded device arguments with the current pick; auto clears the old selector.
- The runtime carries the selection through a typed --qa-device channel outside intake and acceptance criteria. Missing hardware stays selected so QA can skip with a reason rather than substitute another target.
- Limits: Android only, advisory occupancy and explicit refresh. No UI emulator controls or operation:verify targeting. Runtime port and settings implementations need the added members; goal-quality inputs and lease schemas retain their versions.
Feature flag: N/A
Acceptance criteria: 4/4 implemented across runtime and desktop

## [2026-11-06] SKILL-513 docked, resizable chat pane with persisted width
Areas: desktop/app, desktop/core/datastore, desktop/core/domain, desktop/core/testing, desktop/core/ui, desktop/feature/chat
- Chat is a docked right pane in the work area, not a floating overlay. It takes the shared right slot while open outside Settings; otherwise the specs pane keeps its rule. WorkArea takes an optional right width.
- DockedPane (reusable) clamps width between 360 dp and the work-area width minus a 420 dp main-column floor, and reports the clamped result. In drawer mode it covers the work area with no grip. Esc reaches it only while focus is inside it. No scrim or shadow.
- PaneResizeGrip (reusable) is extracted from FloatingPane and shared by both panes; FloatingPane behaviour is unchanged.
- The width is saved in app settings as chatWidthDp (default 560 dp, range 360 to 2000) via AppSettingsRepository.setChatWidthDp. A stored value outside the range decodes clamped and is reported unsupported; a missing one decodes to the default. The DataStore key is additive, so no migration.
- ChatPaneWidth is window-scoped: drags update the on-screen width at once and save once, 300 ms after the last change; closing the window flushes a pending width within one second. Terminal height and floating-pane persistence are unchanged.
- Validation also isolated tests from the host environment: installer harnesses and the install smoke script no longer inherit the skip-preinstall-uninstall variable, and the governed review evidence bridge test uses the short socket path helper. No production behaviour changed.
- Limits: verified on macOS only; the agnix and agent-config script checks were not run in the headless validate session.
Feature flag: N/A
Acceptance criteria: 2/2 implemented (persistence subtask); the docked-layout subtask reported no count

## [2026-11-06] SKILL-511 multiple New Task chats
Areas: desktop/app, desktop/core/domain, desktop/core/datastore, desktop/core/testing, desktop/core/ui, desktop/feature/settings, desktop/feature/chat, design/new-task-chat-tabs
- NewTaskChats owns an uncapped, window-scoped open set with independent repository, branch, agent, text, offer and session state. Selection leaves background streams running; the set always retains an empty draft after its last chat closes.
- NewTaskSavedChats shares saved drafts across open chats and the home. Resume selects an open session, loads an empty selected draft or adds a chat without ending another. reusable
- Close confirms a running reply, saves its partial transcript and offers Reopen using a fresh saved-chat read. Discard deletes the save; failed close keeps the chat open.
- Tabs and Side list share OpenChatItem, OpenChatRow and reply markers. Background busy-to-idle transitions set unread state until selection. reusable
- Tabs shrink to 112dp before scrolling, translate vertical wheel input, fade clipped edges and expose an overflow menu with filtering above eight chats. Ctrl/Cmd shortcuts create, close, cycle and select chats; the sidebar shows the open count above one.
- Settings > General persists Open chats as newTaskChatLayout through the existing wire-value enum and DataStore pattern. Tabs is the default for missing or unknown values; layout changes preserve drafts, selection and sessions.
- Async agent, repository and branch actions capture their target draft. Successful resume signals refresh composer context even when selection stays unchanged.
- Rebound sessions replay operator and finished agent messages on the first turn, with a 24,000-character conversation budget. Replay stays off visible and saved operator lines, survives failure before output and does not stack across unsent switches.
- Design references live in design/new-task-chat-tabs. Limits: open tabs are not restored after restart; closing and resuming an unsent rebound chat loses pending replay; layout changes may reset transcript scroll.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-06] SKILL-512 desktop follows a removed worktree to the main checkout
Areas: desktop/core/data, desktop/core/domain, desktop/core/testing, desktop/feature/chat, desktop/feature/terminal, desktop/feature/changes, desktop/feature/detail
- A goal worktree row reads as Removed when its directory is gone from disk, even if the runtime reports Active or Retained. The shared mapper keeps path, retention and main-checkout outcome fields, and the detail header explains removal by outcome.
- Chat resolves its root from the work item: the worktree if it exists, else the repository root. A live session in a vanished worktree ends and resumes at the repository root; the Claude transport restarts a process whose directory changed. reusable
- ExecutionChatRepository.moveSessions moves saved Claude session files and rewrites the first-line cwd of Codex sessions, returning claude, codex and failed counts. Started Claude or Codex chats keep their session id; Cursor, Junie and failed moves start fresh.
- The chat notice names the main checkout and says whether the chat continues or restarts, plus a not-switched notice when the outcome was skipped or failed. Resume and the history picker follow the removal first.
- The terminal opens at the repository root; the changes window opens committed-range files only while the main checkout is on the goal's branch.
- Limits: existence is checked once per row at mapping time; Cursor and Junie sessions never move; the Codex scan stops at the existing limit; the terminal root has no test.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-11-06] SKILL-507 New Task chat history list
Areas: desktop/core/data, desktop/core/domain, desktop/core/testing, desktop/feature/chat
- Saved New Task drafts now span repositories, newest first. The optional store limit applies after conversion to usable history entries.
- The home shows up to six recent drafts with resume and discard controls. Show all opens the uncapped resume picker; the list uses the existing scroll area.
- Draft refresh runs without a selected repository. Resuming restores the saved repository, branch and agent.
- The repository draft-read interface replaces its repository argument with an optional limit. The chat storage format is unchanged.
- QA or validation reported environment_unavailable.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-05] SKILL-503 Specs panel selection bar
Areas: desktop/app, desktop/core/data, desktop/core/domain, desktop/core/ui, desktop/core/designsystem, desktop/feature/specs, runtime-kotlin/runtime-engine
- A docked selection bar and panel header replace the old toolbar. File selection keeps its row highlight while its parent bundle owns controls, favorites and reservations.
- SpecsMenuModel supplies ordered actions and disabled reasons to menus and the command palette. Keyboard navigation and shortcuts operate on the same bundle target. reusable
- Runtime canDelete verdicts and launch reservations guard deletion; SpecBrowserService remains authoritative. Export enters the existing runtime preflight.
- Shared confirmation dialogs focus the keep action. SbMenu supports disabled reasons and monospace detail; spec popover keyboard handling is opt-in. reusable
- A pending start waits for an agent choice and remains bound to the selected bundle and current start verdict.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

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
