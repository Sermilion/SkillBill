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
