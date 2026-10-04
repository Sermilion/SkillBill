package skillbill.infrastructure.skills.install

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.skills.install.apply.InstallCleanupOperations
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.infrastructure.skills.install.nativeagent.link.InstallNativeAgentOperations
import skillbill.infrastructure.skills.install.plan.buildInstallStagingIntent
import skillbill.infrastructure.skills.install.plan.codexAgentsPath
import skillbill.infrastructure.skills.install.plan.collectInstallPlanningFacts
import skillbill.infrastructure.skills.install.plan.materializeSelectedPlatformSkills
import skillbill.infrastructure.skills.install.plan.resolveInstallEnvironment
import skillbill.infrastructure.skills.install.reconcile.ReconcileSourceRoots
import skillbill.infrastructure.skills.install.reconcile.applyReconciliation
import skillbill.infrastructure.skills.install.reconcile.computeReconciliationPlan
import skillbill.infrastructure.skills.install.runtime.InstallOperations
import skillbill.infrastructure.skills.install.runtime.linkInstalledSkill
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.SupportedAgent
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.ClaudeConfigRootsRequest
import skillbill.ports.install.agent.model.ClaudeConfigRootsResult
import skillbill.ports.install.agent.model.CodexConfigRootsRequest
import skillbill.ports.install.agent.model.CodexConfigRootsResult
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsResult
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryResult
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentPathResult
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupResult
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.apply.model.InstallApplyExecutionResult
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.link.model.InstallSkillLinkResult
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.model.NativeAgentSkippedLink
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationResult
import skillbill.ports.install.nativeagent.model.InstallNativeAgentUnlinkOperationResult
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsResult
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortResult
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.plan.model.InstallStagingIntentResult
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyResult
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.reconcile.model.InstallReconcileResult
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOverrides as FsNativeAgentLinkOverrides
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkRequest as FsNativeAgentLinkRequest

@Inject
class FileSystemInstallPlanningFacts(
  private val hostPlatform: HostPlatformPort,
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallPlanningFactsPort {
  override fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult =
    InstallPlanningFactsResult(
      facts =
        collectInstallPlanningFacts(
          request.installRequest.copy(
            environment = resolveInstallEnvironment(request.installRequest.environment, hostPlatform),
          ),
          catalogLoader,
        ),
    )
}

@Inject
class FileSystemInstallPlatformSkillMaterialization : InstallPlatformSkillMaterializationPort {
  override fun materializePlatformSkills(
    request: InstallPlatformSkillMaterializationPortRequest,
  ): InstallPlatformSkillMaterializationPortResult =
    InstallPlatformSkillMaterializationPortResult(
      platformPacks =
        materializeSelectedPlatformSkills(
          platformManifests = request.platformManifests,
          selectedPlatformSlugs = request.selectedPlatformSlugs,
        ),
    )
}

@Inject
class FileSystemInstallStagingIntent(
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallStagingIntentPort {
  override fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult =
    InstallStagingIntentResult(
      staging =
        buildInstallStagingIntent(
          request = request.installRequest,
          draftSkills = request.draft.skills,
          platformManifests = request.platformManifests,
          catalogLoader = catalogLoader,
        ),
    )
}

@Inject
class FileSystemInstallReconcile(
  private val baselineManifestPersistence: BaselineManifestPersistencePort,
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallReconcilePort {
  override fun reconcile(request: InstallReconcileRequest): InstallReconcileResult {
    val baseline =
      baselineManifestPersistence
        .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
        .manifest
    return InstallReconcileResult(
      plan =
        computeReconciliationPlan(
          upstream =
            ReconcileSourceRoots(
              repoRoot = request.upstreamRepoRoot,
              skillsRoot = request.upstreamSkillsRoot,
              platformPacksRoot = request.upstreamPlatformPacksRoot,
              catalogLoader = catalogLoader,
            ),
          local =
            ReconcileSourceRoots(
              repoRoot = request.localRepoRoot,
              skillsRoot = request.localSkillsRoot,
              platformPacksRoot = request.localPlatformPacksRoot,
              catalogLoader = catalogLoader,
            ),
          home = request.home,
          baseline = baseline,
          environment = request.environment,
        ),
    )
  }
}

@Inject
class FileSystemInstallReconcileApply(
  private val baselineManifestPersistence: BaselineManifestPersistencePort,
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallReconcileApplyPort {
  override fun apply(request: InstallReconcileApplyRequest): InstallReconcileApplyResult {
    val baseline =
      baselineManifestPersistence
        .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
        .manifest

    val output =
      applyReconciliation(
        upstream =
          ReconcileSourceRoots(
            repoRoot = request.upstreamRepoRoot,
            skillsRoot = request.upstreamSkillsRoot,
            platformPacksRoot = request.upstreamPlatformPacksRoot,
            catalogLoader = catalogLoader,
          ),
        local =
          ReconcileSourceRoots(
            repoRoot = request.localRepoRoot,
            skillsRoot = request.localSkillsRoot,
            platformPacksRoot = request.localPlatformPacksRoot,
            catalogLoader = catalogLoader,
          ),
        home = request.home,
        baseline = baseline,
        environment = request.environment,
      )
    return InstallReconcileApplyResult(
      plan = output.plan,
      installedPaths = output.installedPaths,
      prunedPaths = output.prunedPaths,
    )
  }
}

@Inject
class FileSystemInstallApplyExecution(
  private val telemetryConfigStore: TelemetryConfigStore,
  private val installMcpRegistrationPort: InstallMcpRegistrationPort,
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallApplyExecutionPort {
  override fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult =
    InstallApplyExecutionResult(
      result =
        InstallOperations.applyInstall(
          request.plan,
          request.telemetryLevelMutator,
          telemetryConfigStore,
          installMcpRegistrationPort,
          catalogLoader,
        ),
    )
}

@Inject
class FileSystemInstallSkillLink(
  private val hostPlatform: HostPlatformPort,
) : InstallSkillLinkPort {
  override fun linkSkill(request: InstallSkillLinkRequest): InstallSkillLinkResult =
    InstallSkillLinkResult(
      linkedPaths =
        linkInstalledSkill(
          request,
          hostPlatform,
        ),
    )
}

@Inject
class FileSystemInstallAgentTargets(
  private val hostPlatform: HostPlatformPort,
) : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult {
    val environment = resolveInstallEnvironment(request.environment, hostPlatform)
    return InstallAgentPathResult(
      InstallOperations.agentPath(request.agent, request.home, environment, hostPlatform),
    )
  }

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult {
    val environment = resolveInstallEnvironment(request.environment, hostPlatform)
    return DetectInstallAgentTargetsResult(
      InstallOperations.detectAgentTargets(request.home, environment, hostPlatform),
    )
  }

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest): ClaudeConfigRootsResult =
    ClaudeConfigRootsResult(
      InstallOperations.claudeRoots(
        request.home,
        resolveInstallEnvironment(request.environment, hostPlatform),
        hostPlatform,
      ),
    )

  override fun codexConfigRoots(request: CodexConfigRootsRequest): CodexConfigRootsResult =
    CodexConfigRootsResult(
      InstallOperations.codexRoots(
        request.home,
        resolveInstallEnvironment(request.environment, hostPlatform),
        hostPlatform,
      ),
    )

  override fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult {
    val environment = resolveInstallEnvironment(request.environment, hostPlatform)
    return InstallAgentDirectoryResult(
      when (request.agent) {
        SupportedAgent.CODEX.wireValue -> InstallOperations.codexAgentsPath(request.home, environment, hostPlatform)
        SupportedAgent.CLAUDE.wireValue -> InstallOperations.claudeAgentsPath(request.home, environment, hostPlatform)
        SupportedAgent.JUNIE.wireValue -> InstallOperations.junieAgentsPath(request.home, hostPlatform)
        SupportedAgent.CURSOR.wireValue -> InstallOperations.cursorAgentsPath(request.home, hostPlatform)
        else -> InstallOperations.agentPath(request.agent, request.home, environment, hostPlatform)
      },
    )
  }

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult {
    val (removed, skipped) =
      InstallCleanupOperations.cleanupAgentTarget(
        targetDir = request.targetDir,
        skillNames = request.skillNames,
        legacyNames = request.legacyNames,
        managedInstallMarker = request.managedInstallMarker,
        installedSkillsRoot = request.home?.let { installedSkillsCacheRoot(it) },
      )
    return InstallAgentTargetCleanupResult(
      cleanup = InstallCleanupResult(removed = removed, skipped = skipped),
    )
  }
}

@Inject
class FileSystemInstallNativeAgentLinks(
  private val catalogLoader: PlatformPackCatalogLoader,
) : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult {
    val fsRequest = request.linkRequest.toFsRequest(catalogLoader)
    val outcome =
      when (request.provider) {
        NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.linkClaudeAgents(fsRequest)
        NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.linkCodexAgents(fsRequest)
        NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.linkJunieAgents(fsRequest)
        NativeAgentLinkProvider.CURSOR -> InstallNativeAgentOperations.linkCursorAgents(fsRequest)
      }
    return InstallNativeAgentLinkOperationResult(
      outcome =
        NativeAgentLinkOutcome(
          linked = outcome.linked,
          skipped = outcome.skipped.map { skip -> NativeAgentSkippedLink(skip.path, skip.reason) },
        ),
    )
  }

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult {
    val fsRequest = request.linkRequest.toFsRequest(catalogLoader)
    return InstallNativeAgentUnlinkOperationResult(
      unlinked =
        when (request.provider) {
          NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.unlinkClaudeAgents(fsRequest)
          NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.unlinkCodexAgents(fsRequest)
          NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.unlinkJunieAgents(fsRequest)
          NativeAgentLinkProvider.CURSOR -> InstallNativeAgentOperations.unlinkCursorAgents(fsRequest)
        },
    )
  }
}

@Inject
class FileSystemInstallMcpRegistration(
  private val hostPlatform: HostPlatformPort,
) : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      outcome =
        McpRegistrationOperations.register(
          request.agent,
          request.runtimeMcpBin,
          request.home,
          environment = resolveInstallEnvironment(emptyMap(), hostPlatform),
        ),
    )

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      outcome =
        McpRegistrationOperations.unregister(
          request.agent,
          request.home,
          environment = resolveInstallEnvironment(emptyMap(), hostPlatform),
        ),
    )
}

private fun NativeAgentLinkRequest.toFsRequest(catalogLoader: PlatformPackCatalogLoader): FsNativeAgentLinkRequest =
  FsNativeAgentLinkRequest(
    platformPacksRoot = platformPacksRoot,
    skillsRoot = skillsRoot,
    home = home,
    selectedPlatforms = selectedPlatforms,
    environment = environment,
    catalogLoader = catalogLoader,
    overrides =
      FsNativeAgentLinkOverrides(
        installCacheRoot = overrides.installCacheRoot,
        sourceRoots = overrides.sourceRoots,
        legacyManagedRoot = overrides.legacyManagedRoot,
      ),
  )
