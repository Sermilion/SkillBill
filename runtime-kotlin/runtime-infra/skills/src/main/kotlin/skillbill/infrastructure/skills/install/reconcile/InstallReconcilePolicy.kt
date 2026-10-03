package skillbill.infrastructure.skills.install.reconcile

import skillbill.error.shellcontent.reconciliationConflictError
import skillbill.infrastructure.contracts.newSha256Digest
import skillbill.infrastructure.skills.agentaddon.discoverAgentAddons
import skillbill.infrastructure.skills.install.plan.discoverPlatformManifests
import skillbill.infrastructure.skills.install.plan.enumerateInstallPlanSkills
import skillbill.infrastructure.skills.install.staging.InternalStagingPreparation
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.authoredFilesFor
import skillbill.infrastructure.skills.install.staging.content.INSTALL_CACHE_KEY_BYTES
import skillbill.infrastructure.skills.install.staging.content.InstallContentHashInputs
import skillbill.infrastructure.skills.install.staging.content.agentAddonPointersForSkill
import skillbill.infrastructure.skills.install.staging.content.authoredStagingNames
import skillbill.infrastructure.skills.install.staging.content.computeReconciliationContentHash
import skillbill.infrastructure.skills.install.staging.content.validateAgentAddonPointerNamespace
import skillbill.infrastructure.skills.install.staging.generatedSupportPointersFor
import skillbill.infrastructure.skills.install.staging.prepareInternalStaging
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.install.model.BaselineManifest
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal const val SKILLS_PREFIX = "skills/"
internal const val PLATFORM_PACKS_PREFIX = "platform-packs/"
internal const val AGENT_ADDONS_PREFIX = "agent-addons/"

internal data class ReconcileSourceRoots(
  val repoRoot: Path,
  val skillsRoot: Path,
  val platformPacksRoot: Path,
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

internal enum class ReconcileSourceSide {
  UPSTREAM,
  LOCAL,
}

private fun ReconcileSourceSide.enforcesPlatformPackContractVersion(): Boolean = this == ReconcileSourceSide.UPSTREAM

internal data class ReconcileSkillEntry(
  val hash: String,
  val sourceDir: Path,
)

internal data class ReconcileApplyOutput(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val prunedPaths: List<String>,
)

internal fun computeReconciliationPlan(
  upstream: ReconcileSourceRoots,
  local: ReconcileSourceRoots,
  home: Path,
  baseline: BaselineManifest,
  environment: Map<String, String> = emptyMap(),
): ReconciliationPlan {
  val upstreamSkills = enumerateSkills(upstream, home, ReconcileSourceSide.UPSTREAM, environment)
  val localSkills = enumerateSkills(local, home, ReconcileSourceSide.LOCAL, environment)
  return classifyReconciliation(upstreamSkills, localSkills, baseline)
}

internal fun classifyReconciliation(
  upstreamSkills: Map<String, ReconcileSkillEntry>,
  localSkills: Map<String, ReconcileSkillEntry>,
  baseline: BaselineManifest,
): ReconciliationPlan {
  val skillPaths = (upstreamSkills.keys + localSkills.keys).toSortedSet()
  val outcomes =
    skillPaths.map { skillRelativePath ->
      classifySkill(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamSkills[skillRelativePath]?.hash,
        localHash = localSkills[skillRelativePath]?.hash,
        baselineHash = baseline.hashFor(skillRelativePath),
      )
    }
  return ReconciliationPlan(outcomes = outcomes)
}

private fun classifySkill(
  skillRelativePath: String,
  upstreamHash: String?,
  localHash: String?,
  baselineHash: String?,
): SkillReconciliationOutcome =
  when {
    upstreamHash == null && localHash == null -> throw reconciliationConflictError(
      skillRelativePath = skillRelativePath,
      reason = "skill is present in neither the upstream nor the local source tree.",
    )
    upstreamHash == null && localHash != null && skillRelativePath.startsWith(AGENT_ADDONS_PREFIX) ->
      SkillReconciliationOutcome.LocallyAuthored(
        skillRelativePath = skillRelativePath,
        localHash = localHash,
        baselineHash = baselineHash,
      )
    upstreamHash == null && localHash != null ->
      SkillReconciliationOutcome.Prune(
        skillRelativePath = skillRelativePath,
        localHash = localHash,
        baselineHash = baselineHash,
      )
    upstreamHash != null && localHash == upstreamHash ->
      SkillReconciliationOutcome.Unchanged(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        baselineHash = baselineHash,
      )
    upstreamHash != null ->
      SkillReconciliationOutcome.Adopt(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        localHash = localHash,
        baselineHash = baselineHash,
      )
    else -> throw reconciliationConflictError(
      skillRelativePath = skillRelativePath,
      reason = "skill reconciliation reached an unreachable state.",
    )
  }

internal fun enumerateSkills(
  roots: ReconcileSourceRoots,
  home: Path,
  sourceSide: ReconcileSourceSide,
  environment: Map<String, String> = emptyMap(),
): Map<String, ReconcileSkillEntry> {
  val enforceContractVersion = sourceSide.enforcesPlatformPackContractVersion()
  val skillEntries =
    if (Files.isDirectory(roots.skillsRoot)) {
      val request = reconcileEnumerationRequest(roots, home, environment)
      val enforceGovernedReviewStructure = sourceSide == ReconcileSourceSide.UPSTREAM
      val platformManifests =
        discoverPlatformManifests(
          request,
          enforceContractVersion,
          roots.catalogLoader,
          enforceGovernedReviewStructure,
        )

      val skills =
        enumerateInstallPlanSkills(
          request,
          enforceContractVersion,
          roots.catalogLoader,
          enforceGovernedReviewStructure,
        )
      val selectedPackSkills =
        skills.filter { candidate ->
          candidate.kind == InstallPlanSkillKind.PLATFORM_PACK && candidate.internalFor != null
        }
      skills.associate { skill ->
        skillRelativePath(roots, skill) to
          ReconcileSkillEntry(
            hash =
              reconcileSkillHash(
                roots,
                skill,
                ReconcileHashRequest(
                  platformManifests = platformManifests,
                  selectedPackSkills = selectedPackSkills,
                  enforceContractVersion = enforceContractVersion,
                  home = home,
                  environment = environment,
                ),
              ),
            sourceDir = skill.sourceDir.toPath().toAbsolutePath().normalize(),
          )
      }
    } else {
      emptyMap()
    }
  return skillEntries + agentAddonEntries(roots)
}

private fun agentAddonEntries(roots: ReconcileSourceRoots): Map<String, ReconcileSkillEntry> =
  discoverAgentAddons(roots.repoRoot).associate { declaration ->
    "agent-addons/${declaration.slug}" to
      ReconcileSkillEntry(
        hash = hashAgentAddonSource(declaration.manifestPath.toPath(), declaration.contentPath.toPath()),
        sourceDir = declaration.addonRoot.toPath().toAbsolutePath().normalize(),
      )
  }

private const val AGENT_ADDON_HASH_FIELD_SEPARATOR: Byte = 0

private fun hashAgentAddonSource(
  manifestPath: Path,
  contentPath: Path,
): String {
  val digest = newSha256Digest()
  listOf("agent-addon.yaml" to manifestPath, "content.md" to contentPath).forEach { (name, path) ->
    digest.update(name.toByteArray(Charsets.UTF_8))
    digest.update(AGENT_ADDON_HASH_FIELD_SEPARATOR)
    digest.update(Files.readAllBytes(path))
    digest.update(AGENT_ADDON_HASH_FIELD_SEPARATOR)
  }
  return digest.digest().take(INSTALL_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
}

private data class ReconcileHashRequest(
  val platformManifests: List<PlatformManifest>,
  val selectedPackSkills: List<InstallPlanSkill>,
  val enforceContractVersion: Boolean,
  val home: Path,
  val environment: Map<String, String>,
)

private fun reconcileSkillHash(
  roots: ReconcileSourceRoots,
  skill: InstallPlanSkill,
  request: ReconcileHashRequest,
): String {
  val platformManifests = request.platformManifests
  val applicablePointers = applicablePointers(roots.repoRoot, skill.sourceDir.toPath(), platformManifests)
  val supportPointers =
    generatedSupportPointersFor(
      repoRoot = roots.repoRoot,
      sourceSkillDir = skill.sourceDir.toPath(),
      skillName = skill.name,
      skillsRoot = roots.skillsRoot,
      selectedPlatformManifests = platformManifests,
    )
  val internal =
    prepareInternalStaging(
      InternalStagingPreparation(
        repoRoot = roots.repoRoot,
        parentSourceDir = skill.sourceDir.toPath(),
        parentSkillName = skill.name,
        skillsRoot = roots.skillsRoot,
        selectedPackSkills = request.selectedPackSkills,
        platformManifests = platformManifests,
        selectedPlatformManifests = platformManifests,
        parentSupportPointers = supportPointers,
        parentPointerNames = applicablePointers.map { it.second.name }.toSet(),
        enforceContractVersion = request.enforceContractVersion,
        userHome = request.home,
        environment = request.environment,
      ),
    )
  val authored =
    authoredFilesFor(
      skill.sourceDir.toPath(),
      applicablePointers,
      internal.supportPointers,
      internal.sidecarNames,
    )
  val agentAddonPointers = agentAddonPointersForSkill(roots.repoRoot, skill.name)
  validateAgentAddonPointerNamespace(
    skill.name,
    authoredStagingNames(skill.sourceDir.toPath(), authored) + internal.sidecarNames +
      applicablePointers.map { it.second.name } + internal.supportPointers.map { it.name } +
      listOf("SKILL.md", ".content-hash"),
    agentAddonPointers,
  )
  return computeReconciliationContentHash(
    InstallContentHashInputs(
      sourceSkillDir = skill.sourceDir.toPath(),
      authored = authored,
      applicablePointers = applicablePointers,
      generatedSupportPointers = internal.supportPointers,
      internalChildren = internal.children,
      agentAddonPointers = agentAddonPointers,
      checkoutRepoRoot = roots.repoRoot,
    ),
  )
}

internal fun skillRelativePath(
  roots: ReconcileSourceRoots,
  skill: InstallPlanSkill,
): String {
  val resolvedSource = skill.sourceDir.toPath().toAbsolutePath().normalize()
  return when (skill.kind) {
    InstallPlanSkillKind.BASE -> {
      val root = roots.skillsRoot.toAbsolutePath().normalize()
      "skills/" + root.relativize(resolvedSource).toString().replace(File.separatorChar, '/')
    }
    InstallPlanSkillKind.PLATFORM_PACK -> {
      val root = roots.platformPacksRoot.toAbsolutePath().normalize()
      if (resolvedSource.startsWith(root)) {
        "platform-packs/" + root.relativize(resolvedSource).toString().replace(File.separatorChar, '/')
      } else {
        val packRoot = platformPackRootContaining(resolvedSource)
        val slug = skill.platformSlug ?: packRoot.fileName.toString()
        "platform-packs/$slug/" +
          packRoot.relativize(resolvedSource).toString().replace(File.separatorChar, '/')
      }
    }
  }
}

private fun platformPackRootContaining(skillDir: Path): Path =
  generateSequence(skillDir) { path -> path.parent }
    .firstOrNull { candidate -> Files.isRegularFile(candidate.resolve("platform.yaml")) }
    ?: skillDir.parent?.parent
    ?: skillDir

internal fun reconcileEnumerationRequest(
  roots: ReconcileSourceRoots,
  home: Path,
  environment: Map<String, String> = emptyMap(),
): InstallPlanRequest =
  InstallPlanRequest(
    repoRoot = roots.repoRoot.toAbsolutePath().normalize().toFileLocation(),
    home = home.toFileLocation(),
    agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
    platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = McpRegistrationChoice(register = false),
    runtimeDistributionInputs =
      RuntimeDistributionInputs(
        runtimeInstallRoot = home.resolve(".skill-bill/runtime").toFileLocation(),
      ),
    targetPaths =
      InstallationTargetPaths(
        skillsRoot = roots.skillsRoot.toFileLocation(),
        platformPacksRoot = roots.platformPacksRoot.toFileLocation(),
      ),
    windowsSymlinkPreflight =
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
    environment = environment,
  )
