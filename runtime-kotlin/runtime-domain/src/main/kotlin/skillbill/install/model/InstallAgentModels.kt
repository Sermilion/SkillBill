package skillbill.install.model

import skillbill.model.FileLocation

data class AgentTarget(
  val name: String,
  val path: FileLocation,
)

data class AgentLauncherCli(
  val executables: List<String>,
  val installHint: String,
) {
  init {
    require(executables.isNotEmpty()) { "An agent launcher must declare at least one executable." }
  }
}

val AGENT_LAUNCHER_CLIS: Map<SupportedAgent, AgentLauncherCli> =
  mapOf(
    SupportedAgent.CLAUDE to
      AgentLauncherCli(
        executables = listOf(SupportedAgent.CLAUDE.wireValue),
        installHint = "install Claude Code (https://docs.claude.com/en/docs/claude-code/setup)",
      ),
    SupportedAgent.CODEX to
      AgentLauncherCli(
        executables = listOf(SupportedAgent.CODEX.wireValue),
        installHint = "install the Codex CLI (npm install -g @openai/codex)",
      ),
    SupportedAgent.JUNIE to
      AgentLauncherCli(
        executables = listOf(SupportedAgent.JUNIE.wireValue),
        installHint = "install the Junie CLI from JetBrains",
      ),
    SupportedAgent.CURSOR to
      AgentLauncherCli(
        executables = listOf("agent", "${SupportedAgent.CURSOR.wireValue}-agent"),
        installHint = "install the Cursor Agent CLI (curl https://cursor.com/install -fsS | bash)",
      ),
    SupportedAgent.DEVIN to
      AgentLauncherCli(
        executables = listOf(SupportedAgent.DEVIN.wireValue),
        installHint = "install the Devin CLI (https://docs.devin.ai/cli)",
      ),
  )

fun unavailableAgentLauncherReason(
  agentId: String?,
  onPath: (String) -> Boolean,
): String? {
  val normalized = agentId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
  val agent = SupportedAgent.entries.firstOrNull { candidate -> candidate.wireValue == normalized }
  val launcher = agent?.let(AGENT_LAUNCHER_CLIS::get) ?: return null
  if (launcher.executables.any(onPath)) return null
  return agentLauncherUnavailableMessage(agent, launcher.executables.first(), launcher.installHint)
}

fun agentLauncherUnavailableMessage(
  agent: SupportedAgent,
  executable: String,
  installHint: String,
): String =
  "Agent '${agent.wireValue}' cannot run in runtime mode here: its headless CLI '$executable' is not on PATH. " +
    "Having the ${agent.wireValue} editor or its home directory installed is not enough — the headless CLI is a " +
    "separate install. Either $installHint, or relaunch with a different --agent."

val MODEL_DIRECTIVE_CAPABLE_AGENTS: Set<SupportedAgent> =
  setOf(
    SupportedAgent.CLAUDE,
    SupportedAgent.CODEX,
    SupportedAgent.CURSOR,
    SupportedAgent.DEVIN,
  )

fun supportsModelDirective(agentId: String?): Boolean {
  if (agentId == null) return false
  val normalized = agentId.trim().lowercase()
  return MODEL_DIRECTIVE_CAPABLE_AGENTS.any { capable -> capable.wireValue == normalized }
}

object InvokingAgentContextResolver {
  val INVOKING_AGENT_CONTEXT_SIGNALS: List<InvokingAgentContextSignal> =
    listOf(
      InvokingAgentContextSignal(SupportedAgent.CLAUDE, listOf("CLAUDECODE", "CLAUDE_CODE", "CLAUDE_CODE_ENTRYPOINT")),
      InvokingAgentContextSignal(SupportedAgent.CODEX, listOf("CODEX_SANDBOX", "CODEX_SANDBOX_ENV")),
      InvokingAgentContextSignal(SupportedAgent.CURSOR, listOf("CURSOR_AGENT", "CURSOR_INVOKED_AS")),
    )

  fun detect(environment: Map<String, String>): SupportedAgent? =
    INVOKING_AGENT_CONTEXT_SIGNALS
      .firstOrNull { signal -> signal.markerKeys.any { key -> environment[key]?.isNotBlank() == true } }
      ?.agent
}

data class InvokingAgentContextSignal(
  val agent: SupportedAgent,
  val markerKeys: List<String>,
) {
  init {
    require(markerKeys.isNotEmpty()) { "Invoking-agent context signal requires at least one marker key." }
    require(markerKeys.all(String::isNotBlank)) { "Invoking-agent context marker keys must not be blank." }
  }
}

enum class InstallAgentSelectionMode {
  DETECTED,
  MANUAL,
}

enum class InstallAgentTargetSource {
  DETECTED,
  MANUAL,
}

data class InstallAgentSelection(
  val mode: InstallAgentSelectionMode,
  val manualAgents: Set<SupportedAgent> = emptySet(),
  val detectedTargets: List<InstallAgentTarget> = emptyList(),
)

data class InstallAgentTarget(
  val agent: SupportedAgent,
  val path: FileLocation,
  val source: InstallAgentTargetSource,
)

enum class PlatformPackSelectionMode {
  NONE,
  SELECTED,
  ALL,
}

data class PlatformPackSelection(
  val mode: PlatformPackSelectionMode,
  val selectedSlugs: Set<String> = emptySet(),
)

enum class InstallTelemetryLevel(
  val id: String,
) {
  ANONYMOUS("anonymous"),
  FULL("full"),
  OFF("off"),
}

data class RuntimeDistributionInputs(
  val runtimeInstallRoot: FileLocation,
  val runtimeCliBuildDir: FileLocation? = null,
  val runtimeMcpBuildDir: FileLocation? = null,
  val runtimeCliInstallDir: FileLocation? = null,
  val runtimeMcpInstallDir: FileLocation? = null,
  val runtimeLauncherBinDir: FileLocation? = null,
)

data class McpRegistrationChoice(
  val register: Boolean,
  val runtimeMcpBin: FileLocation? = null,
)

data class InstallationTargetPaths(
  val skillsRoot: FileLocation,
  val platformPacksRoot: FileLocation,
  val agentTargets: List<InstallAgentTarget> = emptyList(),
)

enum class WindowsSymlinkPreflightState {
  NOT_WINDOWS,
  AVAILABLE,
  REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
  DECISION_REQUIRED,
}

enum class WindowsSymlinkDecision {
  NOT_REQUIRED,
  PROCEED_WITH_SYMLINKS,
  REQUIRE_USER_ACTION,
}

data class WindowsSymlinkPreflight(
  val state: WindowsSymlinkPreflightState,
  val decision: WindowsSymlinkDecision,
  val message: String = "",
)

data class InstallPlanRequest(
  val repoRoot: FileLocation,
  val home: FileLocation,
  val agentSelection: InstallAgentSelection,
  val platformPackSelection: PlatformPackSelection,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationChoice: McpRegistrationChoice,
  val runtimeDistributionInputs: RuntimeDistributionInputs,
  val targetPaths: InstallationTargetPaths,
  val windowsSymlinkPreflight: WindowsSymlinkPreflight,
  val replaceExistingSkillBillLinks: Boolean = false,
  val environment: Map<String, String> = emptyMap(),
)
