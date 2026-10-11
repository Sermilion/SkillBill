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
