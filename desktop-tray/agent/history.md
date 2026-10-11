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
