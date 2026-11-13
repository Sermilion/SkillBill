# Boundary history

## [2026-11-12] SONA-12 subtask 1: managed Devin installation
Areas: runtime-infra/skills install planning, MCP registration, native-agent rendering, linking and skill removal; runtime-domain SupportedAgent and launcher vocabulary; runtime-ports install and MCP models; runtime-contracts install failure codes; runtime-application uninstall; install.sh; install-plan and native-agent-link-inventory schemas
- Devin is a SupportedAgent (`devin`, `devin-agents`, `md`, JSON MCP). AGENT_LAUNCHER_CLIS lists the `devin` executable with a vendor setup link. Devin has no invoking-context signal yet.
- DevinConfigRoot.devinConfigRoot is the only Devin root resolver: `~/.config/devin` off Windows, `%APPDATA%\devin` on Windows. Skills go in `<root>/skills`, native agents in `<root>/agents`, MCP config in `<root>/mcp_config.json`. installConfigRoots, agentPaths, detectAgents, InstallPlanBuilder, InstallOperationsPaths, FileSystemInstallAdapters.agentDirectory, McpRegistrationOperations.configPathFor and NativeAgentProvider.Devin all use it.
- Detection finds a Devin root or skills directory without checking the CLI and creates one managed user target. Only launcher preflight decides whether the CLI can run.
- McpJsonConfig writes Devin's `mcpServers.skill-bill` as PLAIN_STDIO (`command`, `args`, `env`, no `type`). Other JSON providers keep TYPED_STDIO.
- The Devin renderer maps Read, Grep, Glob and Bash to read, grep, glob and exec as list-valued `allowed-tools`. It keeps exact `mcp__<server>__<tool>` names and rejects unknown tools or wildcards with INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA. An omitted toolset emits no field.
- Native link and unlink, skill removal and inventory decoding use the shared link engine and `<root>/agents`. NativeAgentProvider directory methods take a HostPlatformPort parameter that defaults to JdkHostPlatformPort.
- Uninstall lists the Devin skills target and unregisters MCP for every SupportedAgent id.
- Pattern followed: resolve a provider's host-specific root in one infrastructure function, and pass injected host facts as function parameters rather than on request DTOs.
- reusable: devinConfigRoot with devinSkillsDirectory, devinAgentsDirectory and devinMcpConfigPath; the McpJsonEntryShape selector; InstallMcpProfileEnvironment.appData.
- Compatibility: install-plan schema 0.3 and native-agent-link-inventory schema 0.2 gained only enum values. Malformed MCP JSON, or an `mcpServers` member that is not an object, now fails with MALFORMED_MCP_CONFIG and leaves the original bytes untouched for every JSON MCP provider. Before, it raised IllegalArgumentException or silently replaced the member.
- Limits: on Windows without an absolute APPDATA, any all-provider operation (detection, default targets, uninstall planning, cross-provider skill removal) fails with AGENT_DISCOVERY_FAILED. No link-devin-agents CLI subcommand. Headless launch is in the runtime-infra SONA-12 subtask 2 entry, desktop chat in desktop/core/data.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-05] SKILL-401 subtask 6: infra-skills install and authoring
Areas: runtime-infra/skills install staging and native-agent inventory, native-agent composition and rendering, scaffold authoring and validation; runtime-infra/host JVM file primitives; runtime-domain agent add-on model
- Staging input rejection emits the existing InvalidInstallStagingError directly. Native-agent bundle, toolset and composition input rejection uses INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA; review validation handles only that code.
- Inventory path parsing catches InvalidPathException. Stream-backed cleanup handles UncheckedIOException; atomic-move restoration no longer catches IllegalStateException. Existing I/O logging and error accumulation remain.
- Authoring and scaffold rollback, native-agent staging cleanup and host atomic-write cleanup use Closeable.use to retain the initiating failure and suppress cleanup failures. Staging backup restoration also retains deletion failures as suppressed.
- Pattern followed: reject external input through owned coded failures or nullable lookup; leave runtime invariant defects to propagate.
- reusable: AgentAddonConsumer.fromIdOrNull and unknownIdMessage share consumer lookup and rejection text across scaffold validation, authoring rendering and the existing throwing fromId wrapper.
- Compatibility: unknown-consumer and composition reason text remain unchanged. No wire schema, persisted format or generated output format changes.
- Limits: config stores and validateReleaseRef remain outside this subtask. Cleanup now covers cancellation and other failures previously skipped.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
