package skillbill.infrastructure.skills.install.apply

import skillbill.infrastructure.skills.agentaddon.AgentAddonPointer
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.agentAddonPointersForSkill
import skillbill.infrastructure.skills.install.identity.authoredStagingNames
import skillbill.infrastructure.skills.install.identity.installedSkillContentIdentity
import skillbill.infrastructure.skills.install.identity.requireMatchingSkillContentIdentity
import skillbill.infrastructure.skills.install.identity.suppliedSkillContentIdentity
import skillbill.infrastructure.skills.install.identity.validateAgentAddonPointerNamespace
import skillbill.infrastructure.skills.install.staging.GeneratedSupportPointer
import skillbill.infrastructure.skills.install.staging.InstallContentHashInputs
import skillbill.infrastructure.skills.install.staging.InternalStagingPreparation
import skillbill.infrastructure.skills.install.staging.PreparedInternalStaging
import skillbill.infrastructure.skills.install.staging.ReuseInstallStagingInput
import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.authoredFilesFor
import skillbill.infrastructure.skills.install.staging.computeInstallContentHash
import skillbill.infrastructure.skills.install.staging.generatedSupportPointersFor
import skillbill.infrastructure.skills.install.staging.installedSkillStagingDir
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.isReusableInstallStaging
import skillbill.infrastructure.skills.install.staging.prepareInternalStaging
import skillbill.infrastructure.skills.install.staging.reuseInstallStaging
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformSlugs
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.RenderedSkill
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private val plannedContentHashRegex = Regex("[0-9a-f]{16}")

internal fun plannedStagingIntent(
  plan: InstallPlan,
  skill: InstallPlanSkill,
): InstallStagingPathIntent =
  plan.staging.skillPaths.singleOrNull { intent ->
    intent.skillName == skill.name &&
      intent.sourceDir.toPath().toAbsolutePath().normalize() == skill.sourceDir.toPath().toAbsolutePath().normalize()
  } ?: error("Install plan is missing planned staging intent for skill '${skill.name}' at '${skill.sourceDir}'.")

internal fun validatedPlannedStaging(
  plan: InstallPlan,
  skill: InstallPlanSkill,
  intent: InstallStagingPathIntent,
  platformManifests: List<PlatformManifest>,
  catalogLoader: PlatformPackCatalogLoader? = null,
): RenderedSkill {
  val expectedRoot = installedSkillsCacheRoot(plan.request.home.toPath())
  val resolvedSource = skill.sourceDir.toPath().toAbsolutePath().normalize()
  val resolvedIntentSource = intent.sourceDir.toPath().toAbsolutePath().normalize()
  require(resolvedIntentSource == resolvedSource) {
    "Install plan staging source '${intent.sourceDir}' does not match skill source '${skill.sourceDir}'."
  }
  require(intent.stagingRoot.toPath().toAbsolutePath().normalize() == expectedRoot) {
    "Install plan staging root '${intent.stagingRoot}' does not match expected installed-skills root '$expectedRoot'."
  }
  require(intent.contentHash.matches(plannedContentHashRegex)) {
    "Install plan staging hash '${intent.contentHash}' for '${skill.name}' is not a valid content hash."
  }
  val expectedStagingDir = installedSkillStagingDir(plan.request.home.toPath(), resolvedSource, intent.contentHash)
  require(intent.stagingDir.toPath().toAbsolutePath().normalize() == expectedStagingDir) {
    "Install plan staging dir '${intent.stagingDir}' does not match expected dir '$expectedStagingDir'."
  }
  return materializeValidatedPlannedStaging(
    PlannedStagingMaterialization(
      plan = plan,
      skill = skill,
      intent = intent,
      platformManifests = platformManifests,
      resolvedSource = resolvedSource,
      expectedStagingDir = expectedStagingDir,
      catalogLoader = catalogLoader,
    ),
  )
}

private fun materializeValidatedPlannedStaging(inputs: PlannedStagingMaterialization): RenderedSkill {
  val plan = inputs.plan
  val skill = inputs.skill
  val intent = inputs.intent
  val pointers = applicablePointers(plan.request.repoRoot.toPath(), inputs.resolvedSource, inputs.platformManifests)
  val supportPointers = plannedSupportPointers(inputs)
  val selectedPackSkills = selectedInternalPackSkills(plan)
  val internal = plannedInternalStaging(inputs, pointers, supportPointers, selectedPackSkills)
  val authored = authoredFilesFor(inputs.resolvedSource, pointers, internal.supportPointers, internal.sidecarNames)
  val agentAddonPointers = agentAddonPointersForSkill(plan.request.repoRoot.toPath(), skill.name)
  validateAgentAddonPointerNamespace(
    skill.name,
    authoredStagingNames(inputs.resolvedSource, authored) + internal.sidecarNames + pointers.map { it.second.name } +
      internal.supportPointers.map { it.name } +
      listOf("SKILL.md", ".content-hash", SKILL_CONTENT_IDENTITY_FILENAME),
    agentAddonPointers,
  )
  val currentHash =
    computeInstallContentHash(
      InstallContentHashInputs(
        sourceSkillDir = inputs.resolvedSource,
        authored = authored,
        applicablePointers = pointers,
        generatedSupportPointers = internal.supportPointers,
        internalChildren = internal.children,
        agentAddonPointers = agentAddonPointers,
        checkoutRepoRoot = plan.request.repoRoot.toPath(),
      ),
    )
  validatePlannedStagingSource(inputs, currentHash)
  return reuseOrFreshPlannedStaging(inputs, pointers, internal, agentAddonPointers, selectedPackSkills)
}

private fun reuseOrFreshPlannedStaging(
  inputs: PlannedStagingMaterialization,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
  internal: PreparedInternalStaging,
  agentAddonPointers: List<AgentAddonPointer>,
  selectedPackSkills: List<InstallPlanSkill>,
): RenderedSkill {
  val plan = inputs.plan
  val skill = inputs.skill
  val intent = inputs.intent
  val expectedStagedNames =
    internal.sidecarNames + pointers.map { (_, pointer) -> pointer.name } +
      internal.supportPointers.map { pointer -> pointer.name } + agentAddonPointers.map { it.name } +
      SKILL_CONTENT_IDENTITY_FILENAME
  if (isReusableInstallStaging(inputs.expectedStagingDir, intent.contentHash, expectedStagedNames)) {
    return reuseInstallStaging(
      ReuseInstallStagingInput(
        sourceSkillDir = inputs.resolvedSource,
        finalStagingDir = inputs.expectedStagingDir,
        contentHash = intent.contentHash,
        applicablePointers = pointers,
        generatedSupportPointers = internal.supportPointers,
        internalSidecarNames = internal.sidecarNames,
        agentAddonPointerNames = agentAddonPointers.map { it.name },
      ),
    )
  }
  val staged =
    stageInstalledSkill(
      StageInstalledSkillInput(
        repoRoot = plan.request.repoRoot.toPath(),
        sourceSkillDir = inputs.resolvedSource,
        home = plan.request.home.toPath(),
        environment = plan.request.environment,
        catalogLoader = inputs.catalogLoader,
        manifests = inputs.platformManifests,
        skillsRoot = plan.request.targetPaths.skillsRoot.toPath(),
        selectedPackSkills = selectedPackSkills,
        selectedPlatformSlugs = selectedPlatformSlugs(plan.skills, inputs.platformManifests),
      ),
    )
  val stagedDir = staged.stagingDir.toPath().toAbsolutePath().normalize()
  require(staged.contentHash == intent.contentHash && stagedDir == inputs.expectedStagingDir) {
    "Staged '${skill.name}' at '${staged.stagingDir}' with hash '${staged.contentHash}', but plan expected " +
      "'${inputs.expectedStagingDir}' with hash '${intent.contentHash}'."
  }
  return staged
}

private fun validatePlannedStagingSource(
  inputs: PlannedStagingMaterialization,
  currentHash: String,
) {
  val marker = inputs.expectedStagingDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME)
  if (Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
    requireMatchingSkillContentIdentity(
      suppliedSkillContentIdentity(inputs.resolvedSource),
      installedSkillContentIdentity(inputs.expectedStagingDir),
    )
  }
  require(currentHash == inputs.intent.contentHash) {
    "Planned staging for '${inputs.skill.name}' expected hash '${inputs.intent.contentHash}' but current source " +
      "resolves to '$currentHash'. Re-run planInstall before applyInstall."
  }
}

private fun plannedSupportPointers(inputs: PlannedStagingMaterialization) =
  generatedSupportPointersFor(
    repoRoot = inputs.plan.request.repoRoot.toPath(),
    sourceSkillDir = inputs.resolvedSource,
    skillName = inputs.skill.name,
    skillsRoot = inputs.plan.request.targetPaths.skillsRoot.toPath(),
    selectedPlatformManifests = selectedPlatformManifests(inputs.plan, inputs.platformManifests),
  )

private fun plannedInternalStaging(
  inputs: PlannedStagingMaterialization,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
  supportPointers: List<GeneratedSupportPointer>,
  selectedPackSkills: List<InstallPlanSkill>,
) = prepareInternalStaging(
  InternalStagingPreparation(
    repoRoot = inputs.plan.request.repoRoot.toPath(),
    parentSourceDir = inputs.resolvedSource,
    parentSkillName = inputs.skill.name,
    skillsRoot = inputs.plan.request.targetPaths.skillsRoot.toPath(),
    selectedPackSkills = selectedPackSkills,
    platformManifests = inputs.platformManifests,
    selectedPlatformManifests = selectedPlatformManifests(inputs.plan, inputs.platformManifests),
    parentSupportPointers = supportPointers,
    parentPointerNames = pointers.map { (_, pointer) -> pointer.name }.toSet(),
    userHome = inputs.plan.request.home.toPath(),
    environment = inputs.plan.request.environment,
    catalogLoader = inputs.catalogLoader,
  ),
)

private fun selectedInternalPackSkills(plan: InstallPlan): List<InstallPlanSkill> =
  plan.skills.filter { skill ->
    skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null
  }

private data class PlannedStagingMaterialization(
  val plan: InstallPlan,
  val skill: InstallPlanSkill,
  val intent: InstallStagingPathIntent,
  val platformManifests: List<PlatformManifest>,
  val resolvedSource: Path,
  val expectedStagingDir: Path,
  val catalogLoader: PlatformPackCatalogLoader? = null,
)
