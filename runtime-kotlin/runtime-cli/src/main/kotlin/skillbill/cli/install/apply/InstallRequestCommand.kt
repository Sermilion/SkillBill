package skillbill.cli.install.apply

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path

abstract class InstallRequestCommand(
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root containing skills/ and platform-packs/. Defaults to the invocation repository root.",
  )
  private val skillsRoot by option("--skills", help = "Base skills root. Defaults to <repo-root>/skills.")
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root. Defaults to <repo-root>/platform-packs.",
  )
  private val agentMode by option("--agent-mode", help = "Agent selection mode.")
    .choice("detected" to InstallAgentSelectionMode.DETECTED, "manual" to InstallAgentSelectionMode.MANUAL)
    .default(InstallAgentSelectionMode.DETECTED)
  private val agents by option(
    "--agent",
    help = "Manual agent to include. Repeat for ${SupportedAgent.supportedIds.joinToString(", ")}.",
  ).multiple()
  private val agentTargets by option(
    "--agent-target",
    help = "Manual target override in agent=path form. Repeat to override multiple agents.",
  ).multiple()
  private val platformMode by option("--platform-mode", help = "Platform pack selection mode.")
    .choice(
      "none" to PlatformPackSelectionMode.NONE,
      "selected" to PlatformPackSelectionMode.SELECTED,
      "all" to PlatformPackSelectionMode.ALL,
    ).default(PlatformPackSelectionMode.NONE)
  private val platforms by option("--platform", help = "Selected platform pack slug. Repeat for multiple packs.")
    .multiple()
  private val telemetry by option("--telemetry", help = "Telemetry level to configure during apply.")
    .choice(InstallTelemetryLevel.entries.associateBy(InstallTelemetryLevel::id))
    .default(InstallTelemetryLevel.ANONYMOUS)
  private val mcp by option("--mcp", help = "Whether apply should register the runtime MCP server.")
    .choice("register" to true, "skip" to false)
    .default(true)
  private val runtimeInstallRoot by option(
    "--runtime-install-root",
    help = "Runtime install root. Defaults to <home>/.skill-bill/runtime.",
  )
  private val runtimeCliBuildDir by option(
    "--runtime-cli-build-dir",
    help = "Optional runtime-cli build directory.",
  )
  private val runtimeMcpBuildDir by option(
    "--runtime-mcp-build-dir",
    help = "Optional runtime-mcp build directory.",
  )
  private val runtimeCliInstallDir by option(
    "--runtime-cli-install-dir",
    help = "Optional runtime-cli install directory.",
  )
  private val runtimeMcpInstallDir by option(
    "--runtime-mcp-install-dir",
    help = "Optional runtime-mcp install directory.",
  )
  private val runtimeLauncherBinDir by option(
    "--runtime-launcher-bin-dir",
    help = "Optional runtime launcher bin directory.",
  )
  private val runtimeMcpBin by option(
    "--runtime-mcp-bin",
    help = "Packaged runtime-mcp bin script for MCP registration.",
  )
  private val windowsSymlinkState by option(
    "--windows-symlink-state",
    help = "Structured Windows symlink preflight state.",
  )
    .choice(
      "not-windows" to WindowsSymlinkPreflightState.NOT_WINDOWS,
      "available" to WindowsSymlinkPreflightState.AVAILABLE,
      "requires-elevation-or-developer-mode" to WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
      "decision-required" to WindowsSymlinkPreflightState.DECISION_REQUIRED,
    ).default(WindowsSymlinkPreflightState.NOT_WINDOWS)
  private val windowsSymlinkDecision by option(
    "--windows-symlink-decision",
    help = "Structured Windows symlink decision.",
  )
    .choice(
      "not-required" to WindowsSymlinkDecision.NOT_REQUIRED,
      "proceed-with-symlinks" to WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
      "require-user-action" to WindowsSymlinkDecision.REQUIRE_USER_ACTION,
    ).default(WindowsSymlinkDecision.NOT_REQUIRED)
  private val windowsSymlinkMessage by option("--windows-symlink-message", help = "Structured Windows symlink message.")
    .default("")
  private val replaceExistingSkillBillLinks by option(
    "--replace-existing-skill-bill-links",
    help = "Remove existing Skill Bill skill links for selected agents before applying this install.",
  ).flag(default = false)
  protected val format by formatOption()

  protected fun toRequest(inputs: CliRunInputs): InstallPlanRequest {
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val explicitTargets = parseAgentTargets(agentTargets)
    val manualAgents = agents.map(SupportedAgent::fromId).toSet()
    return InstallPlanRequest(
      repoRoot = resolvedRepoRoot.toFileLocation(),
      home = inputs.userHome.toFileLocation(),
      agentSelection =
        InstallAgentSelection(
          mode = selectedAgentMode(manualAgents, explicitTargets),
          manualAgents = manualAgents,
        ),
      platformPackSelection =
        PlatformPackSelection(
          mode = selectedPlatformMode(),
          selectedSlugs = platforms.toSet(),
        ),
      telemetryLevel = telemetry,
      mcpRegistrationChoice =
        McpRegistrationChoice(
          register = mcp,
          runtimeMcpBin = runtimeMcpBin?.let(Path::of)?.toFileLocation(),
        ),
      runtimeDistributionInputs =
        RuntimeDistributionInputs(
          runtimeInstallRoot =
            runtimeInstallRoot?.let(Path::of)?.toFileLocation()
              ?: inputs.userHome.resolve(".skill-bill/runtime").toFileLocation(),
          runtimeCliBuildDir = runtimeCliBuildDir?.let(Path::of)?.toFileLocation(),
          runtimeMcpBuildDir = runtimeMcpBuildDir?.let(Path::of)?.toFileLocation(),
          runtimeCliInstallDir = runtimeCliInstallDir?.let(Path::of)?.toFileLocation(),
          runtimeMcpInstallDir = runtimeMcpInstallDir?.let(Path::of)?.toFileLocation(),
          runtimeLauncherBinDir = runtimeLauncherBinDir?.let(Path::of)?.toFileLocation(),
        ),
      targetPaths =
        InstallationTargetPaths(
          skillsRoot =
            skillsRoot?.let(Path::of)?.toFileLocation()
              ?: resolvedRepoRoot.resolve("skills").toFileLocation(),
          platformPacksRoot =
            platformPacksRoot?.let(Path::of)?.toFileLocation()
              ?: resolvedRepoRoot.resolve("platform-packs").toFileLocation(),
          agentTargets = explicitTargets,
        ),
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = windowsSymlinkState,
          decision = windowsSymlinkDecision,
          message = windowsSymlinkMessage,
        ),
      replaceExistingSkillBillLinks = replaceExistingSkillBillLinks,
      environment = inputs.environment,
    )
  }

  private fun selectedAgentMode(
    manualAgents: Set<SupportedAgent>,
    explicitTargets: List<InstallAgentTarget>,
  ): InstallAgentSelectionMode =
    if (
      agentMode == InstallAgentSelectionMode.MANUAL ||
      manualAgents.isNotEmpty() ||
      explicitTargets.isNotEmpty()
    ) {
      InstallAgentSelectionMode.MANUAL
    } else {
      InstallAgentSelectionMode.DETECTED
    }

  private fun selectedPlatformMode(): PlatformPackSelectionMode =
    if (platforms.isNotEmpty()) PlatformPackSelectionMode.SELECTED else platformMode
}

private fun parseAgentTargets(rawTargets: List<String>): List<InstallAgentTarget> =
  rawTargets.map { rawTarget ->
    val parts = rawTarget.split("=", limit = 2)
    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
      "--agent-target must use agent=path form."
    }
    InstallAgentTarget(
      agent = SupportedAgent.fromId(parts[0]),
      path = Path.of(parts[1]).toFileLocation(),
      source = InstallAgentTargetSource.MANUAL,
    )
  }
