package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.nativeagent.link.InstallNativeAgentOperations
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOutcome
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOverrides
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkRequest
import skillbill.infrastructure.skills.install.nativeagent.link.effectivePackRootsForInstall
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path

internal fun applyNativeAgents(
  plan: InstallPlan,
  failures: MutableList<InstallApplyIssue>,
  catalogLoader: PlatformPackCatalogLoader? = null,
): List<NativeAgentApplyOutcome> {
  val selectedAgents = plan.agents.map { target -> target.agent }.toSet()
  val context =
    NativeAgentApplyContext(
      plan = plan,
      failures = failures,
      installCacheRoot = nativeAgentApplyCacheRoot(plan),
      legacyManagedRoot = nativeAgentLegacyCacheRoot(plan),
      sourceRoots =
        nativeAgentSourceRoots(
          NativeAgentSourceRootsRequest(
            skills = plan.skills,
            selectedPlatformSlugs = plan.selectedPlatformSlugs.toSet(),
            platformPacksRoot = plan.request.repoRoot.toPath().resolve("platform-packs"),
            home = plan.request.home.toPath(),
            environment = plan.request.environment,
            catalogLoader = catalogLoader,
          ),
        ),
      catalogLoader = catalogLoader,
    )
  return nativeAgentInstallers
    .filter { installer -> installer.agent in selectedAgents }
    .flatMap { installer ->
      applyNativeAgentProvider(
        installer = installer,
        context = context,
      )
    }
}

private fun applyNativeAgentProvider(
  installer: NativeAgentInstaller,
  context: NativeAgentApplyContext,
): List<NativeAgentApplyOutcome> =
  runCatching {
    installer.link(nativeAgentLinkRequest(context))
  }.fold(
    onSuccess = { outcome -> nativeAgentProviderOutcomes(installer, outcome) },
    onFailure = { error ->
      listOf(
        failedNativeAgentOutcome(installer, error).also { nativeOutcome ->
          nativeOutcome.issue?.let(context.failures::add)
        },
      )
    },
  )

private data class NativeAgentApplyContext(
  val plan: InstallPlan,
  val failures: MutableList<InstallApplyIssue>,
  val installCacheRoot: Path,
  val legacyManagedRoot: Path,
  val sourceRoots: List<Path>,
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

private fun nativeAgentLinkRequest(context: NativeAgentApplyContext): NativeAgentLinkRequest {
  val plan = context.plan
  return NativeAgentLinkRequest(
    platformPacksRoot = plan.installationTargetPaths.platformPacksRoot.toPath(),
    skillsRoot = plan.installationTargetPaths.skillsRoot.toPath(),
    home = plan.request.home.toPath(),
    selectedPlatforms = plan.selectedPlatformSlugs,
    environment = plan.request.environment,
    catalogLoader = context.catalogLoader,
    overrides =
      NativeAgentLinkOverrides(
        installCacheRoot = context.installCacheRoot,
        sourceRoots = context.sourceRoots,
        legacyManagedRoot = context.legacyManagedRoot,
      ),
  )
}

private fun nativeAgentProviderOutcomes(
  installer: NativeAgentInstaller,
  outcome: NativeAgentLinkOutcome,
): List<NativeAgentApplyOutcome> {
  val linked =
    outcome.linked.map { link ->
      NativeAgentApplyOutcome(
        provider = installer.provider,
        agent = installer.agent,
        status = NativeAgentApplyStatus.LINKED,
        path = link.toFileLocation(),
        message = "linked",
      )
    }
  val skipped =
    outcome.skipped.map { skipped ->
      NativeAgentApplyOutcome(
        provider = installer.provider,
        agent = installer.agent,
        status = NativeAgentApplyStatus.SKIPPED,
        path = skipped.path.toFileLocation(),
        message = skipped.reason,
      )
    }
  return (linked + skipped).ifEmpty {
    listOf(
      NativeAgentApplyOutcome(
        provider = installer.provider,
        agent = installer.agent,
        status = NativeAgentApplyStatus.SKIPPED,
        message = "no native-agent target or artifacts available for selected plan",
      ),
    )
  }
}

private fun failedNativeAgentOutcome(
  installer: NativeAgentInstaller,
  error: Throwable,
): NativeAgentApplyOutcome {
  val symlinkError = error as? InstallSymlinkException
  val issue =
    InstallApplyIssue(
      kind = InstallApplyIssueKind.NATIVE_AGENT_LINK_FAILED,
      message = error.message.orEmpty(),
      agent = installer.agent,
      path = symlinkError?.linkPath?.toFileLocation(),
      guidance = symlinkError?.guidance,
      causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
    )
  return NativeAgentApplyOutcome(
    provider = installer.provider,
    agent = installer.agent,
    status = NativeAgentApplyStatus.FAILED,
    path = symlinkError?.linkPath?.toFileLocation(),
    message = error.message.orEmpty(),
    issue = issue,
  )
}

private data class NativeAgentInstaller(
  val agent: SupportedAgent,
  val provider: NativeAgentProviderId,
  val link: (NativeAgentLinkRequest) -> NativeAgentLinkOutcome,
  val unlink: (NativeAgentLinkRequest) -> List<Path>,
)

private fun nativeAgentApplyCacheRoot(plan: InstallPlan): Path =
  currentNativeAgentApplyCacheRoot(
    plan.request.home.toPath(),
    plan.installationTargetPaths.platformPacksRoot.toPath(),
    plan.installationTargetPaths.skillsRoot.toPath(),
  )

fun currentNativeAgentApplyCacheRoot(
  home: Path,
  platformPacksRoot: Path,
  skillsRoot: Path?,
): Path {
  val cacheLeaf = NativeAgentOperations.installCacheRoot(home, platformPacksRoot, skillsRoot).fileName.toString()
  return installedSkillsCacheRoot(home).resolve("native-agents-$cacheLeaf").toAbsolutePath().normalize()
}

private fun nativeAgentLegacyCacheRoot(plan: InstallPlan): Path =
  NativeAgentOperations.installCacheRoot(
    home = plan.request.home.toPath(),
    platformPacksRoot = plan.installationTargetPaths.platformPacksRoot.toPath(),
    skillsRoot = plan.installationTargetPaths.skillsRoot.toPath(),
  )

internal data class NativeAgentSourceRootsRequest(
  val skills: List<InstallPlanSkill>,
  val selectedPlatformSlugs: Set<String>,
  val platformPacksRoot: Path? = null,
  val home: Path? = null,
  val environment: Map<String, String> = emptyMap(),
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

internal fun nativeAgentSourceRoots(request: NativeAgentSourceRootsRequest): List<Path> {
  val skillRoots =
    request.skills
      .filter { skill -> skill.platformSlug == null || skill.platformSlug in request.selectedPlatformSlugs }
      .map { skill -> skill.sourceDir.toPath() }
  val packRoots =
    if (request.platformPacksRoot != null && request.home != null && request.catalogLoader != null) {
      effectivePackRootsForInstall(
        platformPacksRoot = request.platformPacksRoot,
        userHome = request.home,
        environment = request.environment,
        selectedPlatforms = request.selectedPlatformSlugs.toList(),
        catalogLoader = request.catalogLoader,
      )
    } else {
      emptyList()
    }
  return (skillRoots + packRoots).distinct()
}

private val nativeAgentInstallers: List<NativeAgentInstaller> =
  NativeAgentProvider.entries.map { provider ->
    when (provider) {
      NativeAgentProvider.Claude ->
        NativeAgentInstaller(
          agent = SupportedAgent.CLAUDE,
          provider = NativeAgentProviderId.CLAUDE,
          link = InstallNativeAgentOperations::linkClaudeAgents,
          unlink = InstallNativeAgentOperations::unlinkClaudeAgents,
        )
      NativeAgentProvider.Codex ->
        NativeAgentInstaller(
          agent = SupportedAgent.CODEX,
          provider = NativeAgentProviderId.CODEX,
          link = InstallNativeAgentOperations::linkCodexAgents,
          unlink = InstallNativeAgentOperations::unlinkCodexAgents,
        )
      NativeAgentProvider.Junie ->
        NativeAgentInstaller(
          agent = SupportedAgent.JUNIE,
          provider = NativeAgentProviderId.JUNIE,
          link = InstallNativeAgentOperations::linkJunieAgents,
          unlink = InstallNativeAgentOperations::unlinkJunieAgents,
        )
      NativeAgentProvider.Cursor ->
        NativeAgentInstaller(
          agent = SupportedAgent.CURSOR,
          provider = NativeAgentProviderId.CURSOR,
          link = InstallNativeAgentOperations::linkCursorAgents,
          unlink = InstallNativeAgentOperations::unlinkCursorAgents,
        )
    }
  }
