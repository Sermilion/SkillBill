## [2026-10-25] SKILL-445 subtask 3 desktop terminal split layout and toggle
Areas: desktop-tray (composeApp jvmMain ui NavigationHost and TerminalToggle, composition Main, bootstrap DesktopBootstrap; composeApp jvmTest SkillBillNavHostTest)
- MEDIUM feature. SkillBillNavHost gains an optional terminal ViewModel and a terminalSurface slot; the center column is wrapped in BoxWithConstraints and a private TerminalPaneHost draws a 6dp draggable divider plus TerminalRoute below the detail pane only while the pane is open. Sidebar and SpecsPaneHost are untouched.
- Pane height is clamped between the settings minimum and the smaller of the settings maximum and column height minus a 160dp detail minimum. The drag updates local state and ResizeTo is sent once on drag stop. The divider also resizes by keyboard and accessibility actions. reusable
- TerminalToggle copies the SpecsToggle pill (amber when open, Button role, Open/Closed state) with no dot; rememberTerminalViewModel mirrors rememberSpecsViewModel and is created only for SessionState.Active; the toggle sits in the accessory slot after SpecsToggle. No shared toggle helper was extracted.
- Main.kt wires TerminalSurface over the window owner; compose.interop.blending is set first in both main() and DesktopBootstrap.main so the heavyweight Swing panel blends with Compose.
- Test: one nav-host test covers surface presence closed/open and a multi-step drag saving exactly one height; a mutant saving per drag delta was killed once the drag used four steps.
- Limits: the heavyweight panel's bottom corners against the 12dp rounded window clip are QA-owned; bottom padding on the pane host is the pre-approved fix. Settings window and tray popover show no terminal.
Feature flag: N/A (terminalPaneOpen setting, default closed)
Acceptance criteria: 3/3 implemented

## [2026-10-25] SKILL-445 subtask 2 desktop JVM terminal host
Areas: desktop-tray (composeApp jvmMain terminal package and build, core/designsystem Theme, gradle catalog, settings.gradle.kts, DeletionRules guard, ARCHITECTURE.md)
- MEDIUM feature. composeApp jvmMain gains JediTerm 3.57 (core, ui) and pty4j 0.13.10 behind a terminal package: pure terminalLaunch (login shell, TERM set, zsh fallback on macOS, home directory when the folder is missing), a PtyTerminalProcessFactory with an idempotent destroy, and a TerminalSession that opens once, reuses its view, and returns Failed so a later open retries. reusable
- TerminalSessions keeps one session per window id, closes it when the window scope's job completes, and installs one shutdown hook so no shell outlives the app. TerminalSurface hosts the session component in SwingPanel, with a Retry message on failure and no header (the feature route owns the header).
- TerminalTheme maps design-system colours and the bundled mono font onto JediTerm settings, leaving the ANSI palette alone. core/designsystem exposes one public suspend monoFontBytes because its resources are internal. reusable
- JediTerm resolves from a group-filtered JetBrains intellij-dependencies repository in settings.gradle.kts; pty4j stays on Maven Central. A strict jetbrains-annotations 23.0.0 constraint keeps the desktop classpath equal to the runtime's.
- Limits: no tests for the settings provider or the SwingPanel; QA owns the packaged-app pty4j native extraction, scrollback surviving hide/show and the no-orphan-shell check. The pane is still not hosted in the window.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-25] SKILL-445 subtask 1 desktop terminal state module
Areas: desktop-tray (core/domain AppSettings, core/datastore StoredSettings, new feature/terminal module, composeApp WindowComponent and build, architecture guards, ARCHITECTURE.md, docs/architecture-parity.md)
- MEDIUM feature. AppSettings gains terminalPaneOpen (default closed) and terminalPaneHeightDp with a companion MIN 120, MAX 1200 and DEFAULT 240; the init block rejects out-of-range heights. StoredSettings keeps both as nullable fields, so old settings files load with defaults and no migration, and a stored height outside the range is clamped on read rather than rejected. reusable
- New :feature:terminal module (plugin skillbill.feature, same build shape as feature/specs) registered in settings.gradle.kts, composeApp dependencies, ModuleGraphRules.features and the ModuleGraphRulesTest baseline manifest.
- TerminalViewModel takes WorkDetailRepository and SettingsRepository and has no navigator. TogglePane, ClosePane and ResizeTo (clamped to the range) write settings; SelectedWorkChanged cancels the previous resolving job, then reads the work detail and takes its repository root, or null on a null identity or failed read, so a slow stale read never overwrites a newer selection. It uses no System, filesystem or process API.
- TerminalRoute takes a surface slot taking the working directory: returns early when closed, otherwise a 28dp header with a "Terminal" label and a close button (Button role, "Close terminal" description set through clearAndSetSemantics), then the slot filling the rest. It imports no AWT interop, JediTerm or pty4j; the real terminal surface is a later subtask. reusable
- Limits: no Compose UI test for the route; the pane is not yet hosted in the window and no shell runs. Validate ran clean (check, createDistributable, packagedDataSmoke, runtime check); npx agnix was unavailable.
Feature flag: N/A (terminalPaneOpen setting, default closed)
Acceptance criteria: 4/4 implemented

## [2026-10-24] SKILL-444 subtask 6 desktop spec pane run actions and purge
Areas: desktop-tray (feature/specs contract, ViewModel, route, toolbar and dialog composables; composeApp WindowComponent wiring)
- MEDIUM feature. SpecsViewModel now takes specs, details, controls and settings. A selected bundle loads its SpecBundleControl through one control job on selection, on every snapshot emission and after every action; stale answers are dropped by project and bundle path. A file selection resolves to its nearest bundle's key (issueKey, else name) by walking roots in runtime order with no sorting.
- Start routes by state: NOT_STARTED goes through specs.startGoal; PAUSED, BLOCKED and CANCELED read the work detail then call controls.launch. Pause and Cancel go through details then controls; Cancel has a request, keep-running and confirm flow. An agent the user picked wins over the control's agentId seed.
- One act helper is the single in-flight guard (flag, job, Ready control); it clears pending confirmations, drops messages if the selection moved and re-reads the control. Failures show the raw refusal summary, prefixed "Couldn't start" or "Couldn't purge". reusable
- SpecRunToolbar keeps Start, agent caret, Pause and Cancel always composed (enable/disable only) with an icon-only eraser Purge at the right end; the line below follows a fixed priority of confirmation, message, pause notice, selection hint, unavailable reason. Purge has a tooltip explaining why it is disabled.
- PurgeConfirmationDialog is a modal AlertDialog listing what is deleted and kept; "Keep run history" holds initial focus and Escape maps to it; the destructive button is a danger DesignButton. reusable
- Limits: the runtime-side purge reset lives in subtask 1 and the UI only renders it; "Pause requested" with Purge visible may overflow 320dp (not checked visually).
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-24] SKILL-444 subtask 5 desktop spec pane browse
Areas: desktop-tray (feature/specs new module, composeApp composition and ui, architecture guards, ARCHITECTURE.md, docs/architecture-parity.md)
- MEDIUM feature. New :feature:specs module (plugin skillbill.feature) with contract, ViewModel, route, tree and status-dot composables; registered in settings.gradle.kts, composeApp dependencies, ModuleGraphRules.features and the ModuleGraphRulesTest baseline manifest (the consumed-in-production guard needs it).
- SpecsViewModel takes SpecBrowserRepository, WorkDetailRepository and SettingsRepository and has no navigator. Project follows the selected work item's repository root, falls back to the first project, and PickProject pins the choice. One flatMapLatest snapshot subscription keyed on project, preferred root and a retry counter.
- visibleRows is a pure depth-first walk that keeps runtime order (no sorting) and never reads Lifecycle or Freshness. Filtering expands ancestors for display only; the saved expansion set (AppSettings.specExpandedPaths) is written only by toggle and collapse-all. reusable
- Pane states: loading, no specs, unreadable folder (Retry, Reveal folder), no match (Clear filters). Open file goes through LocalRepositoryNavigation.openFile and failures return as FileOpenFailed.
- Window wiring: EntryViewModels gains specs(); DesignWindow gains an accessory slot in the title bar; SpecsToggle and rememberSpecsViewModel hold the ViewModel in the window entry store so a database switch discards it; SkillBillNavHost hosts a fixed 320dp SpecsPane after the detail column and only reads navigator.selected. Created only while the session is Active; the settings window shows neither.
- Status dot encodes state by shape and pulse, not colour alone, with a content description. Rows support arrow, Enter and Space keyboard use. reusable
- Limits: a filter-expanded folder does not visibly collapse on Toggle or Left; icons are drawn glyphs since commonMain has no icon dependency; pause, cancel and run-again stay unwired. Spotless was not run (needs a plain clone); packagedDataSmoke passed only in a clean scratch clone.
Feature flag: N/A (specPaneOpen setting, default closed)
Acceptance criteria: 4/4 implemented

## [2026-10-24] SKILL-444 subtask 4 desktop spec data and settings
Areas: desktop-tray (core/domain, core/data, core/datastore, core/testing)
- MEDIUM feature. core:domain gains the spec-browser model (SpecProject, SpecSnapshot, SpecNode folder/bundle/file tree, SpecBundleControl carrying a WorkIdentity) and a six-method SpecBrowserRepository. AppSettings and StoredSettings gain specPaneOpen and specExpandedPaths as nullable stored fields with defaults, so old settings files load with no migration; empty path sets are not stored.
- core:data adds the RuntimeSpecGateway port and a jvmMain RuntimeSpecAdapter that maps runtime spec types to domain one-to-one with exhaustive enum mapping and no sorting. A null runtime specsRoot maps to a blank string; the repository and watcher treat blank as no directory.
- RuntimeSpecBrowserRepository runs every call through one guarded helper on the IO dispatcher: it rethrows cancellation and maps other exceptions to Failure. startGoal and purgeGoal map runtime outcomes (accepted, launch failed, rejected, purge refusal and leftovers); rejection reuses runRefusalMessage. reusable
- observeSnapshot is a channelFlow: one read first, then NIO directory triggers and goal-store commit signals, debounced 250ms and conflated. Each trigger source has its own catch so a failing watcher degrades to the other. A failed read emits Failure and keeps observing.
- NioSpecDirectoryChanges is a WatchService-backed Flow: it skips hidden directories and archived bundles under done, registers new directories as they appear, and closes the watch service on cancel. reusable
- Session wiring: SessionParts gains specs, DatabaseSessionComponent exposes it, and an app-scope SessionSpecBrowserRepository facade follows SessionWorkDetailRepository. FakeSpecBrowserRepository lives in core:testing.
- runRefusalMessage and WorkControlRejection.toRefusal became internal for reuse.
- Limits: no UI, :feature:specs or composeApp wiring yet (subtasks 5 and 6); pause, cancel and run-again are not wired. packagedDataSmoke was not verified locally (dirty tree needs SKILL_BILL_INSTALL_RUN_ID); CI runs it.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-23] SKILL-441 subtask 7 desktop bundle file links
Areas: desktop-tray (core/domain, core/data, core/ui, composeApp infrastructure and architecture test, feature/detail)
- MEDIUM feature. PlanRunReport carries bundleFiles (default empty, so an older runtime maps to no list). RuntimeWorkMapper passes bundleFiles, parentSpecPath and subtask specPaths through verbatim; the desktop does no repo-root path joining.
- RepositoryNavigation gains a suspend openFile returning Outcome, matching openRepository and openDirectory. NativeServicesImpl requires an existing file, then uses Desktop.open or the OS opener (xdg-open, open, cmd start); every failure becomes an Outcome.Failure plus a diagnostics record.
- feature/detail SpecBundleFileList renders a "Spec bundle" list from PlanReportSection. Absolute entries are Role.Button links with an "Open <name>" description and show failures inline; relative entries stay plain selectable text. It falls back to parentSpecPath plus subtask specPaths when bundleFiles is empty. (reusable)
- The absolute-path check is string-based because commonMain has no JVM file API; it carries a shortcut comment.
- DesktopArchitectureTest bans File( as a whole word, so the required openFile name no longer trips the presentation storage guard.
- Limits: goal activity shows a plan report only once the runtime's goal branch in WorkExecutionHistory attaches one (SKILL-441 subtask 6); NativeServicesImpl.openFile has no automated test; packagedDataSmoke was not run locally.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
## [2026-10-24] SKILL-443 subtask 3 QA could-not-complete notification and detail
Areas: desktop-tray core/domain model, core/data runtime mapper, composeApp application (TransitionNotifications), feature/detail (PhaseResults, DetailPresentation, DetailContent)
- MEDIUM feature. WorkItem.qaOutcome (WorkQaOutcome with derived couldNotComplete) comes from the IdeStatus workflow_qa_outcome field; GoalQaResult gains failureKind. An unparseable recordedAt maps to null, never a board failure.
- qa_incomplete joins allowedTransitions and defaultNotifications (on). TransitionTracker.Observation keeps a qaMarker (the recordedAt when could-not-complete); a changed marker against a prior observation emits one qa_incomplete and drops the generic blocked and failed. First-seen items stay a silent baseline. reusable
- notificationBody takes the transition and returns "QA could not complete: <reason>" or the bare phrase; the settings switch renders from allowedTransitions with no edit.
- Detail: QaRow adds environment error or runtime defect labels and shows the reason even with a report; WorkflowQaSection renders the outcome for non-goal items.
- Limits: the QA row and mapper timestamp fallback have no dedicated tests. Packaged-data smoke ran green on a clean tree.
Feature flag: N/A (qa_incomplete notification toggle, default on)
Acceptance criteria: 5/5 implemented

## [2026-10-21] SKILL-430 subtask 2 single runtime copy
Areas: desktop-tray, composeApp/bootstrap, core/data, build-logic/convention, packaged-data smoke, .github/workflows, install.sh, README.md
- MEDIUM feature. Desktop production dependencies on runtime modules are compileOnly; tests retain their provisioned runtime. Packaging removes Skill Bill runtime modules and duplicate shared libraries, retaining launcher stdlib.
- The bootstrap reuses BuildIdentityRecord and runtime-root resolution, refuses missing or mismatched installs before runtime loading, and offers a JDK-only Retry window. An isolated application loader delegates to the app with the installed runtime jars.
- Runtime-facing reads, controls, commit signals and launch adapters keep their implementations. The installed runtime alone supplies migrations and schema compatibility.
- reusable: verifyPackagedRuntimeFree combines module names from the runtime dependency graph with Skill Bill class detection. Smoke and release archive checks also inspect class contents, accepting Compose runtime jars and rejecting renamed Skill Bill code.
- Packaged smoke enters through the bootstrap with a provisioned runtime. CI and release packaging provision matching identities; architecture and install documentation describe the matched pair.
- The source guard admits method-handle lookup only in DesktopBootstrap.kt under the spec-approved deviation. Other production sources remain covered.
- Limits: desktop launch now requires a matching installed runtime. macOS and Windows execution remain for their CI hosts.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-21] SKILL-430 subtask 1 build identity gate
Areas: desktop-tray (core/domain, core/data, core/common, composeApp, feature/work, feature/detail, build-logic, gradle catalog)
- MEDIUM feature. WorkStoreSessionManager asks a RuntimeBuildGate before SessionFactory.create. A refused build opens no Room database and runs no migration.
- core/data identity/BuildIdentityRecord parses build-identity.properties and compares records with JDK and stdlib only, so subtask 2's bootstrap can reuse it (reusable).
- jvmMain InstalledRuntimeBuildGate rereads both records on every check. The installed one is at SKILL_BILL_RUNTIME_DIR, else ~/.skill-bill/runtime, plus runtime-cli/build-identity.properties.
- New ProblemKind runtime_build_mismatch carries a RuntimeBuildReason (mismatch, missing, unreadable, not installed), both identities and the reinstall command. StoreSessionCoordinator.retry reselects the database. The router Refresh and the ProblemBanner Retry call it; the tray popover Refresh does not.
- HostEnvironment.appResourcesDirectory supplies compose.application.resources.dir. A stageBuildIdentityResource Sync feeds appResourcesRootDir. The record sits under lib/app/resources on Linux/macOS and app/resources on Windows.
- verifySharedRuntimeVersions (SharedRuntimeVersions.kt) resolves dev.skillbill:runtime-cli and fails check and createDistributable when a shared group:module version differs from jvmRuntimeClasspath (reusable).
- Root build declares skillbill.build-identity apply false; without it a second KGP copy breaks clean. gradle.properties raises Metaspace to 1g after a kspKotlinJvm OOM.
- Limits: no re-check runs while a session is active. Releases tagged before SKILL-430 carry no record and are refused.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-15] SKILL-424 subtask 6 deletion and delivery proof
Areas: desktop-tray (composeApp architecture tests, docs, ARCHITECTURE.md), runtime-kotlin (runtime-cli repoTest installer fixtures, runtime-infra/sqlite worklist stores)

- Removed the desktop process-adapter rule from the governed-edge walk (process assertions, WorkRunLauncher start/stop cut-off, launchesCommand). The approved native display query, the five-file WorkRunLauncher ownership set and DeletionRules (a)-(e) stay.
- Walker fixtures: alias import and extension call now assert reach, with a negative twin for an alias to a missing declaration that must fail as an unresolved governed edge (reusable).
- docs/architecture-parity.md gained a Deviations subsection, CI runner and host list, and runtime touched-file notes; the Readian reference moved to current HEAD (old revision unresolvable). ARCHITECTURE.md now bans only installed CLI, PATH or login-shell discovery and command-output parsing.
- Validate repaired KDoc on a private extension in WorkGoalSubtaskStore, a Spotless violation in WorkGoalDependencyStore, and 14 installer repoTests broken on main by the mandatory desktop install step. Offline desktop fixtures (staged release asset, seeded desktop-tray gradlew, temp-home install dirs) are reusable.
- Packaged data smoke passed with no CLI and an empty PATH; desktop-tray and runtime-kotlin check passed on macOS arm64.
- Limits: only macOS arm64 ran locally; install_smoke_test.sh and runtime_package_parity_smoke_test.sh were denied headless, left to CI; featureTaskRuntimeRunEntry has no direct desktop reader but stays exported and pinned by RuntimeEngineInboundApiTest.
Feature flag: N/A
Acceptance criteria: not self-reported; audit owns the count (subtask 6 of SKILL-424)

## [2026-10-15] SKILL-424 subtask 5 navigation and host lifetimes
Areas: desktop-tray (composeApp, core/common, core/data, core/domain, core/testing, core/ui, feature/work, feature/detail, feature/history, feature/settings, gradle catalog)

- Replaced the single MainWindow with Navigation 3: each feature owns a serializable route; composeApp holds a RouteBackStack (root plus at most one detail), polymorphic RouteSnapshots, a per-window WindowNavigator (per-section filter, selection and scroll memory) and a NavDisplay host with central entry adapters.
- Added DatabaseGenerations (StateFlow, advances on every selection and same-path replacement); AppRoute entry keys carry the generation, and restore drops obsolete-generation or invalid routes and falls back to a safe root (reusable).
- New scopes AppScope, DatabaseSessionScope, WindowScope, EntryScope; WindowComponent builds per-window and per-entry subcomponents; EntryViewModels.detail takes typed DetailArgs validated into Valid or Rejected, with no Any maps or casts (reusable).
- Host lifetimes: WindowOwner, WindowRegistry, SessionTransitionCoordinator (serialized, cancel-safe database switches that clear window state first) and HostIntentRouter for tray, menu, popover and notification actions; AppOwner fixes quit order; Main opens multiple windows.
- Guards: serialization ban narrowed to kotlinx.serialization.json; new alias-aware NavigationTypingRules PSI scan; no baseline or exemption grew.
- Breaking or behavioral: DispatcherProvider is now (io, ui); sidebar split into Work and History roots with footer links; settings window disposes on close; Cmd/Ctrl+N opens a new window.
- Limits: nothing compiled or run when written; Nav3, savedstate, subcomponent and compose-ui-test signatures unverified; two-pane layout is a custom layout over two NavDisplays, not a SceneStrategy; Readian root unreadable, so no drift recorded.
Feature flag: N/A
Acceptance criteria: not self-reported by implement; audit owns the count (subtask 5 of SKILL-424)

## [2026-10-15] SKILL-424 subtask 4 feature presentation
Areas: desktop-tray (core/ui, feature/work, feature/detail, feature/history, feature/settings, composeApp, core/data, core/domain, core/testing, build-logic/convention)

- New core:ui plus feature:work, detail, history and settings modules, each with a contract, a ViewModel and its content in commonMain; features depend only on common, domain, designsystem and ui. A FeatureConventionPlugin supplies shared setup (reusable).
- DashboardController, the combined BoardSource, composeApp UI/presentation files and TrayStateKind are deleted; ViewModels own state, and composeApp keeps the shell (selection, default selection), notification and store-session coordinators, and ViewModelFactory over the generated component.
- Reusable in core:ui: SectionPager (paging, generation fencing, cursor re-anchoring), PagedListState, NavigationEffects and TransientEffects (bounded one-shot delivery), pendingFeedback in UiState (max 8, monotonic ids, acknowledged by id), shared rows, formatting, footer and tooltip.
- Typed combine everywhere; guards ban array or indexed-cast combine and retired types, sibling-feature imports and shared-UI forbidden references, and feature modules must be commonMain-only. No baseline or exemption grew.
- Fixed SectionPager.start leaving the footer at Loading when an in-flight append was restarted (appendCancelled).
- Tests are barrier-driven (CompletableDeferred hooks on core:testing fakes, setMain with StandardTestDispatcher); ported sidebar scroll, tray hover and transition tracker tests.
- Limits: nothing compiled or run when written; Readian source was unreachable, so the parent spec table stood in; the governed-edge walk may flag unresolved edges; compose-ui-test must suffice for ImageComposeScene in feature:history. Cancel gives no feedback of its own; historical docs left unchanged.
Feature flag: N/A
Acceptance criteria: not self-reported by implement; audit owns the count (subtask 4 of SKILL-424)

## [2026-10-15] SKILL-424 subtask 3 SKILL-423 data repair handoff
Areas: desktop-tray (composeApp, core/data, core/domain, docs), runtime-kotlin (runtime-domain, runtime-ports, runtime-engine, runtime-infra/sqlite)

- Pause eligibility is now a runtime policy fact (pauseRequested input, pauseEligible output) projected on board and detail models; desktop WorkItem.pauseEligible replaces its own pause rule. WorkControlService validates through shared eligibility helpers.
- Control results are typed: IntentRecorded for pause, ExitConfirmed for confirmed stop, Accepted for already-stopped or no-live-lease, plus a distinct incomplete-termination refusal. Desktop gateway, adapter and repositories return Outcome of ControlConfirmation; the dashboard shows action and confirmation notices.
- Exact runtime reason restoration flows through a ReasonRestoration domain model, mapper and detail-pane note.
- Tests added: runtime pause policy, control outcomes, forced-retry bounded paging, session barrier replacement and failed initialization, cross-list cursor refusal. The architecture walk gained an extracted governed-edge walker with a rejection fixture (reusable).
- desktop-tray/docs/skill-423-handoff.md holds one row per obligation (trace, verdict, owner, evidence) plus the Readian HEAD note; ARCHITECTURE.md and data-layer-validation.md link to it (reusable).
- Limits: nothing compiled or run when written; board still loads the full work list; desktop still checks freshness beside runtime facts; no StoreReplaced refusal, no virtual-time deadline tests, teardown runs under the mutex. No Room migration or schema version bump.
Feature flag: N/A
Acceptance criteria: not self-reported by implement; audit owns the count (subtask 3 of SKILL-424)

## [2026-10-15] SKILL-424 subtask 2 data, preferences and generated DI
Areas: desktop-tray (core/data, core/datastore, core/common, core/domain, core/testing, composeApp, build-logic/convention, scripts)

- Moved the Room store, read-only driver, commit-signal observer, runtime adapters, mapper and paging out of composeApp into new core:data (ports in commonMain, JVM adapters in jvmMain); RoomBoardSource became Room board, detail and runtime control repositories behind session facades.
- New core:datastore owns DataStore settings, the one-time legacy java.util.prefs migration and a diagnostics sink; Preferences.kt was split.
- composeApp now builds one generated Kotlin-Inject/Anvil ApplicationComponent; hand-built construction is gone. Per-database session components sit behind app-scoped facades with a DatabaseSelection holder (reusable).
- The component takes host environment and legacy-settings source as constructor arguments, so smoke tests use a disposable home and preferences node (reusable).
- Guards extended: new module edges, an api-edge rule, runtime/sql/prefs imports confined to data and datastore, no reflection or ServiceLoader, no @Inject defaults, no construction in composition sources.
- Tests: bounded paging, read-only store, observation lifecycle (child-JVM writer, counters), settings repository, component smoke; old test hooks recreated as port-seam wrappers.
- Limits: nothing compiled or run when written; Kotlin-Inject/Anvil, Room 3 and PSI call-graph shapes unverified. The api-edge rule is a line heuristic. Component smoke covers AC-005 only partly; zero-connection counts live in core:data tests. ViewModels, navigation and window scopes stay for subtasks 4-6.
Feature flag: N/A
Acceptance criteria: not self-reported by implement; audit owns the count (subtask 2 of SKILL-424)

## [2026-10-15] SKILL-424 subtask 1 build graph and core foundations
Areas: desktop-tray (root build, build-logic, composeApp, core/common, core/domain, core/designsystem, core/testing), .github/workflows, install.sh, scripts

- Split the single-module desktop build into JVM-only KMP modules: composeApp (executable, native tray, packaging) plus core:common, core:domain, core:designsystem, core:testing. Root build holds no production classes; check and packaging tasks delegate to subprojects.
- Added included build-logic with convention plugins for KMP library, Compose, kotlin-inject, quality and desktop application, all reading the desktop version catalog. Quality plugin writes a per-module architecture manifest that tests consume.
- Core domain is pure: DatabaseFileIdentity replaces java.nio, kotlin.time replaces java.time; java conversions stay at runtime edges (mapper, Room source). DispatcherProvider and Clock are injected into controller and board source.
- Fonts moved to compose resources with snake_case names. Packaged output paths moved to composeApp/build in install.sh, smoke scripts and both workflows.
- Architecture guards now read module manifests and parse sources: module-graph rules (targets, forbidden edges, cycles, test-only testing module) and PSI-based source rules (aliased, star and qualified references). Rejection fixtures cover each rule (reusable).
- Build-logic TestKit fixtures and core:testing fixtures (TestClock, work/board/detail builders) are reusable.
- Known limits: nothing compiled or run when written; several dependency pins (kotlin-inject, anvil, lifecycle, navigation3, savedstate, junit-bom) unverified. No scope annotations yet; some adapters still use Dispatchers.IO directly. Empty legacy src directories remain. Docs left to subtask 6.
Feature flag: N/A
Acceptance criteria: not self-reported by implement; audit owns the count (subtask 1 of SKILL-424)

## [2026-10-12] SKILL-423 desktop app data layer revamp
Areas: desktop-tray, .github/workflows, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-engine, runtime-kotlin/runtime-core, runtime-kotlin/runtime-infra/sqlite, runtime-kotlin/runtime-cli

- Replaced CLI work reads and controls with pinned Room 3, bundled SQLite, typed repository Flow, and shared runtime projection/control services. Runtime migrations remain the schema authority.
- Added committed-revision observation for external writers, read-only snapshots, typed storage/observation failures, and bounded candidate queries with separate live/history/detail paging.
- Fenced asynchronous appends, controls, and delayed notifications by store identity and epoch. New observation sessions establish silent transition baselines; stale detail freezes elapsed values.
- Kept initial loading, background refresh, and append states distinct. Trusted empty results retain "All quiet" through refresh; failed reads retain stale rows and detail.
- Migrated app preferences once to typed DataStore with atomic updates. Retired CLI and polling preferences never become database paths or duplicate workflow state.
- Added reusable RoomBoardSource, ExternalCommitObserver, read-only Room driver, runtime WorkControlService, and DataStore settings owner.
- Deleted desktop work-process adapters, executable discovery, JSON decoders, polling, and superseded tests. Composite dependencies and packaged smoke cover the in-process data path; shared CLI/MCP wire contracts remain compatible.
- Older incompatible stores require runtime-owned upgrades. SKILL-424 owns transferred query/paging refinements, final KMP/DI/ViewModel/navigation architecture, and native integration; macOS/Windows packaging and interactive UI proof remain host/CI coverage.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-10-11] SKILL-418 desktop tray application
Areas: desktop-tray, .github/workflows, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-engine, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra/contracts, runtime-kotlin/runtime-infra/sqlite
- Added an independent JDK 21 Compose Desktop build with inward domain, application, infrastructure, presentation, UI, and composition dependencies.
- Added tray/popover, goal and phase details, history, settings, transition notifications, and login-start adapters. Board/detail contract 1.0 remains authoritative; incompatible payloads have dedicated problem states.
- Followed identity-checked detail selection and settings generations. Pause and stop use independent bounded CLI runners; timed-out children drain and exit naturally.
- Added reusable typed board/detail decoder, work projections, transition tracker, and process runner. Stale clocks freeze and completion counts require completion evidence.
- Added Linux StatusNotifierItem and Windows tray integration, an app-owned macOS template/count status item, and a window fallback when no Linux tray watcher is available.
- Added path-filtered desktop CI, unsigned DMG/MSI/DEB/RPM distribution tasks, and AppImage staging with a pinned, operator-verified packaging tool.
- Changed the internal runtime work-payload validator port to accept JSON text, keeping parsing and typed schema failures in infrastructure; runtime wire versions remain unchanged.
- Native anchoring, icon/menu behavior, notification activation, login-start, and installer execution remain unverified on supported hosts. The Compose popup API is alpha; stock GNOME needs AppIndicator for tray visibility.
Feature flag: N/A
Acceptance criteria: 9/9 implemented
