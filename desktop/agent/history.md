## [2026-10-31] SKILL-453 execution agent chat panel
Areas: desktop/core/domain, desktop/core/data, desktop/core/ui, desktop/core/designsystem, desktop/core/testing, desktop/feature/chat (new), desktop/feature/detail, desktop/app
- :core:domain adds the ExecutionChatRepository port and chat models: one ChatTurnRequest per turn, replies as a Flow of ChatEvent (TextDelta, ToolUse, Completed, Failed with a ChatFailure). reusable
- :core:data chat adds the per-agent ChatTransport seam (@IntoSet); AgentExecutionChatRepository picks it by agent id. A new agent adds a transport, not a repository branch. ClaudeChatTransport runs one claude -p stream-json process per turn, --session-id first and --resume after, prompt on stdin. ClaudeStreamJson decodes lines as JsonElement with no @Serializable types. reusable
- ChatAgentResolver takes the execution's agent, else the launch default (chat.agent_fallback), with model and effort from the machine config's REASONING tier or left to the CLI (chat.matrix_fallback). ChatPermissionProfiles holds the Act and ReadOnly tool limits and the scope system prompt.
- ChatProcessLauncher is the only chat file that starts processes and is on the ProcessLauncherRules allowlist. Cancelling a turn destroys its process, one JVM shutdown hook destroys live ones, and the stderr drain is bounded at 2 seconds.
- New :feature:chat holds the window-scoped ChatWorkspace with one in-memory session per WorkItem.id. It shows a templated state line before any process starts, sends the full execution snapshot first and a snapshot diff after, keys the opening instruction on state, and owns pause-and-act through WorkControlRepository. modeFor(header) is the single ReadOnly/Act rule. reusable
- ChatWorkspace names no dispatcher, so tests drive it on runTest's backgroundScope with runCurrent() and advanceTimeBy, not advanceUntilIdle(). reusable
- The chat pane is PaneSlot.Chat in the shell's right slot, exclusive with Specs via ShellPanes. Entry points are the detail header bot toggle, mod+L (null while a terminal has focus) and a shortcut sheet row. ChatCommands and CHAT_SHORTCUT in :core:ui are the only chat API other features import. reusable
- TerminalPaneChrome gains icon, noun, shortcut labels and nullable new-tab, resize and rename callbacks; SbInput gains multiline (Enter submits, Shift+Enter adds a newline). Defaults keep existing callers unchanged. FakeExecutionChatRepository in :core:testing serves chat tests. reusable
- Also on this branch: the launcher puts the app jar first on the packaged class path so the patched Linux tray library wins (AppClassLoaderTest), and build-tray.sh maps tray right-click to the click callback.
- Limits: Claude is the only transport; codex, cursor and junie show "Chat is not available for agent <id> yet" with Send disabled. Chats persist nothing and end on tab close, window dispose or quit. The details pane stays hidden while chat is open. No Bedrock or Vertex model remapping. CHAT_SHORTCUT reads os.name in :core:ui commonMain, which only works because desktop has a single jvm target.
- Validation reported by the run: desktop clean check (133 tests), createDistributable, packagedDataSmoke, agnix, validate_agent_configs and install and parity smoke tests passed on Linux. macOS and Windows jobs did not run, and QA reported unsupported_target.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

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

