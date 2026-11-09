package skillbill.application.install.selection

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.install.model.InstallSelectionResolutionOutcome
import skillbill.application.install.model.ResolveInstallSelectionRequest
import skillbill.application.install.model.ResolvedInstallSelection
import skillbill.application.scaffold.InstallAgentService
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationProfile
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.model.toPath
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.TelemetryConfigSnapshotRead
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.telemetry.model.TelemetryConfigDocumentParse
import skillbill.telemetry.parseTelemetryConfigDocument
import java.nio.file.Path

@Inject
class InstallSelectionResolutionService(
  private val installService: InstallService,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
  private val installAgentService: InstallAgentService,
  private val telemetryConfigStore: TelemetryConfigStore,
  private val executableLookup: ExecutableLookup,
) {
  fun resolve(request: ResolveInstallSelectionRequest): InstallSelectionResolutionOutcome {
    val discoveryRequest = discoveryRequest(request)
    val discoveredSlugs = installService.discoverPlatformPackSlugs(discoveryRequest)
    val saved =
      installSelectionPersistencePort
        .readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(request.home))
        .selection

    val profile = resolveProfile(request, saved)
    val platformPackSelection = resolvePlatformPackSelection(request, saved)
    unknownSelectedSlugMessage(platformPackSelection, discoveredSlugs)?.let { message ->
      return InstallSelectionResolutionOutcome.Refused(message)
    }
    val telemetryLevel =
      when (val telemetry = resolveTelemetryLevel(request)) {
        is TelemetryResolution.Ready -> telemetry.level
        is TelemetryResolution.Refused -> return InstallSelectionResolutionOutcome.Refused(telemetry.message)
      }
    val agents =
      when (val resolvedAgents = resolveAgents(request, saved, profile)) {
        is AgentResolution.Ready -> resolvedAgents.agents
        is AgentResolution.Refused -> return InstallSelectionResolutionOutcome.Refused(resolvedAgents.message)
      }

    return InstallSelectionResolutionOutcome.Ready(
      ResolvedInstallSelection(
        installationProfile = profile,
        platformPackSelection = platformPackSelection,
        telemetryLevel = telemetryLevel,
        agentSelectionMode = InstallAgentSelectionMode.MANUAL,
        agentIds = agents.map { it.first },
        agentPaths = agents.map { it.second },
        mcpRegister =
          when {
            request.reuseLastSelection && saved != null -> saved.mcpRegistrationChoice.register
            else -> true
          },
      ),
    )
  }

  fun toReplayText(resolved: ResolvedInstallSelection): String =
    buildString {
      resolved.agentIds.zip(resolved.agentPaths).forEach { (agentId, path) ->
        append("agent\t")
        append(agentId)
        append('\t')
        append(path)
        append('\n')
      }
      append("platform-mode\t")
      append(resolved.platformPackSelection.mode.name.lowercase())
      append('\n')
      resolved.platformPackSelection.selectedSlugs.sorted().forEach { slug ->
        append("platform\t")
        append(slug)
        append('\n')
      }
      append("telemetry\t")
      append(resolved.telemetryLevel.id)
      append('\n')
      append("installation-profile\t")
      append(resolved.installationProfile.wireValue)
      append('\n')
      append("mcp\t")
      append(if (resolved.mcpRegister) "register" else "skip")
      append('\n')
    }

  private fun discoveryRequest(request: ResolveInstallSelectionRequest): InstallPlanRequest =
    InstallPlanRequest(
      repoRoot =
        (request.platformPacksRoot.toAbsolutePath().normalize().parent ?: Path.of(".").toAbsolutePath())
          .toFileLocation(),
      home = request.home.toFileLocation(),
      agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
      platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationChoice = McpRegistrationChoice(register = false),
      runtimeDistributionInputs =
        RuntimeDistributionInputs(
          runtimeInstallRoot = request.home.resolve(".skill-bill/runtime").toFileLocation(),
        ),
      targetPaths =
        InstallationTargetPaths(
          skillsRoot = request.skillsRoot.toFileLocation(),
          platformPacksRoot = request.platformPacksRoot.toFileLocation(),
        ),
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      environment = request.environment,
    )

  private fun resolveProfile(
    request: ResolveInstallSelectionRequest,
    saved: SharedInstallSelection?,
  ): InstallationProfile =
    when {
      request.githubRequested -> InstallationProfile.GITHUB
      saved != null -> saved.installationProfile
      else -> InstallationProfile.ORDINARY
    }

  private fun resolvePlatformPackSelection(
    request: ResolveInstallSelectionRequest,
    saved: SharedInstallSelection?,
  ): PlatformPackSelection =
    when {
      request.explicitPlatformSlugs.isNotEmpty() ->
        PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = request.explicitPlatformSlugs,
        )
      request.reuseLastSelection && saved != null -> saved.platformPackSelection
      else -> PlatformPackSelection(mode = PlatformPackSelectionMode.ALL)
    }

  private fun unknownSelectedSlugMessage(
    selection: PlatformPackSelection,
    discoveredSlugs: Set<String>,
  ): String? {
    if (selection.mode != PlatformPackSelectionMode.SELECTED) {
      return null
    }
    val unknown = selection.selectedSlugs - discoveredSlugs
    if (unknown.isEmpty()) {
      return null
    }
    return "Unknown platform pack selection: ${unknown.sorted().joinToString(", ")}. " +
      "Discovered platform packs: ${discoveredSlugs.sorted().joinToString(", ")}."
  }

  private fun resolveTelemetryLevel(request: ResolveInstallSelectionRequest): TelemetryResolution {
    request.explicitTelemetry?.let { return TelemetryResolution.Ready(it) }
    return when (val snapshot = telemetryConfigStore.readSnapshot()) {
      is TelemetryConfigSnapshotRead.Absent -> TelemetryResolution.Ready(InstallTelemetryLevel.ANONYMOUS)
      is TelemetryConfigSnapshotRead.Malformed ->
        TelemetryResolution.Refused(
          "Current telemetry configuration could not be read or validated at '${snapshot.path}': ${snapshot.reason}",
        )
      is TelemetryConfigSnapshotRead.Present ->
        when (val parsed = parseTelemetryConfigDocument(snapshot.path.toString(), snapshot.document)) {
          is TelemetryConfigDocumentParse.Invalid ->
            TelemetryResolution.Refused(
              "Current telemetry configuration could not be read or validated at '${snapshot.path}': " +
                parsed.reason,
            )
          is TelemetryConfigDocumentParse.Valid -> {
            val level = InstallTelemetryLevel.entries.firstOrNull { candidate -> candidate.id == parsed.settings.level }
            if (level == null) {
              TelemetryResolution.Refused(
                "Current telemetry configuration could not be read or validated at '${snapshot.path}': " +
                  "telemetry level must be one of: anonymous, full, off.",
              )
            } else {
              TelemetryResolution.Ready(level)
            }
          }
        }
    }
  }

  private fun resolveAgents(
    request: ResolveInstallSelectionRequest,
    saved: SharedInstallSelection?,
    profile: InstallationProfile,
  ): AgentResolution {
    if (profile != InstallationProfile.GITHUB && !request.reuseLastSelection) {
      return AgentResolution.Ready(emptyList())
    }
    val candidates =
      when {
        request.reuseLastSelection && saved != null ->
          saved.selectedAgents.sortedBy(SupportedAgent::wireValue).map { agent ->
            agent.wireValue to
              installAgentService.agentPath(agent.wireValue, request.home, request.environment)
          }
        profile == InstallationProfile.GITHUB ->
          installAgentService.detectAgentTargets(request.home, request.environment).map { target ->
            target.name to target.path.toPath()
          }
        else -> emptyList()
      }
    val usableGithubAgents =
      candidates.filter { (agentId, _) ->
        unavailableAgentLauncherReason(agentId, executableLookup::onPath) == null
      }
    return when {
      profile == InstallationProfile.GITHUB && usableGithubAgents.isEmpty() ->
        AgentResolution.Refused(
          "No usable agent found for unattended install. Install a supported agent CLI on PATH " +
            "(claude, codex, junie, or cursor), then retry.",
        )
      profile == InstallationProfile.GITHUB -> AgentResolution.Ready(usableGithubAgents)
      request.reuseLastSelection && candidates.isEmpty() ->
        AgentResolution.Refused("Saved install selection has no agents.")
      else -> AgentResolution.Ready(candidates)
    }
  }
}

private sealed interface TelemetryResolution {
  data class Ready(
    val level: InstallTelemetryLevel,
  ) : TelemetryResolution

  data class Refused(
    val message: String,
  ) : TelemetryResolution
}

private sealed interface AgentResolution {
  data class Ready(
    val agents: List<Pair<String, Path>>,
  ) : AgentResolution

  data class Refused(
    val message: String,
  ) : AgentResolution
}
