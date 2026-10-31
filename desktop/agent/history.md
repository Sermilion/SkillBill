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

