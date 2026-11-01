package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.skills.agentaddon.AgentAddonPointer
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.SkillContentIdentity
import skillbill.infrastructure.skills.install.identity.agentAddonPointersForSkill
import skillbill.infrastructure.skills.install.identity.authoredStagingNames
import skillbill.infrastructure.skills.install.identity.routeInstalledSkillBody
import skillbill.infrastructure.skills.install.identity.suppliedSkillContentIdentity
import skillbill.infrastructure.skills.install.identity.validateAgentAddonPointerNamespace
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.install.model.RenderedSkill
import skillbill.review.parsing.requireMatch
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class PreparedStageInstalledSkill(
  val resolvedSource: Path,
  val resolvedRepoRoot: Path,
  val skillName: String,
  val agentAddonPointers: List<AgentAddonPointer>,
  val target: AuthoringTarget,
  val pointers: List<Pair<PlatformManifest, PointerSpec>>,
  val generatedSupportPointers: List<GeneratedSupportPointer>,
  val internal: PreparedInternalStaging,
  val authored: List<Path>,
  val contentHash: String,
  val contentIdentity: SkillContentIdentity,
  val finalStagingDir: Path,
  val expectedStagedNames: Set<String>,
)

internal data class StageInstalledSkillArtifacts(
  val contentHash: String,
  val contentIdentity: SkillContentIdentity,
  val finalStagingDir: Path,
  val expectedStagedNames: Set<String>,
)

private fun resolveStageInstalledSkillArtifacts(
  request: ResolveStageInstalledSkillArtifactsInput,
): StageInstalledSkillArtifacts {
  val contentHash =
    computeInstallContentHash(
      InstallContentHashInputs(
        sourceSkillDir = request.resolvedSource,
        authored = request.authored,
        applicablePointers = request.pointers,
        generatedSupportPointers = request.internal.supportPointers,
        internalChildren = request.internal.children,
        agentAddonPointers = request.agentAddonPointers,
        checkoutRepoRoot = request.input.repoRoot,
      ),
    )
  val contentIdentity = resolveStageContentIdentity(request.resolvedSource, request.suppliedCompactIdentity)
  val finalStagingDir = installedSkillStagingDir(request.input.home, request.resolvedSource, contentHash)
  val expectedStagedNames =
    request.internal.sidecarNames + request.pointers.map { (_, pointer) -> pointer.name } +
      request.internal.supportPointers.map { pointer -> pointer.name } + request.agentAddonPointers.map { it.name } +
      SKILL_CONTENT_IDENTITY_FILENAME
  return StageInstalledSkillArtifacts(contentHash, contentIdentity, finalStagingDir, expectedStagedNames)
}

internal data class StageInstallContext(
  val resolvedSource: Path,
  val resolvedRepoRoot: Path,
  val skillName: String,
  val agentAddonPointers: List<AgentAddonPointer>,
  val target: AuthoringTarget,
  val pointers: List<Pair<PlatformManifest, PointerSpec>>,
  val generatedSupportPointers: List<GeneratedSupportPointer>,
  val internal: PreparedInternalStaging,
  val authored: List<Path>,
)

private fun resolveStageInstallContext(input: StageInstalledSkillInput): StageInstallContext {
  val resolvedSource = input.sourceSkillDir.toAbsolutePath().normalize()
  val resolvedRepoRoot = input.repoRoot.toAbsolutePath().normalize()
  val skillName = resolvedSource.fileName.toString()
  val agentAddonPointers = agentAddonPointersForSkill(resolvedRepoRoot, skillName)
  val discovery =
    PlatformPackDiscoveryContext(
      repoRoot = resolvedRepoRoot,
      userHome = input.home,
      environment = input.environment,
      catalogLoader = input.catalogLoader,
    )
  val target = resolveTarget(resolvedRepoRoot, skillName, discovery)
  val selectedManifests = input.manifests.orEmpty().filter { manifest -> manifest.slug in input.selectedPlatformSlugs }
  val pointers = applicablePointers(resolvedRepoRoot, resolvedSource, input.manifests)
  val generatedSupportPointers =
    generatedSupportPointersFor(
      repoRoot = resolvedRepoRoot,
      sourceSkillDir = resolvedSource,
      skillName = skillName,
      selectedPlatformManifests = selectedManifests,
    )
  val resolvedSkillsRoot = (input.skillsRoot ?: resolvedRepoRoot.resolve("skills")).toAbsolutePath().normalize()
  val internal =
    prepareInternalStaging(
      InternalStagingPreparation(
        repoRoot = resolvedRepoRoot,
        parentSourceDir = resolvedSource,
        parentSkillName = skillName,
        skillsRoot = resolvedSkillsRoot,
        selectedPackSkills = input.selectedPackSkills,
        platformManifests = input.manifests,
        selectedPlatformManifests = selectedManifests,
        parentSupportPointers = generatedSupportPointers,
        parentPointerNames = pointers.map { (_, pointer) -> pointer.name }.toSet(),
        userHome = input.home,
        environment = input.environment,
        catalogLoader = input.catalogLoader,
      ),
    )
  val authored =
    authoredFilesFor(
      sourceSkillDir = resolvedSource,
      applicablePointers = pointers,
      generatedSupportPointers = internal.supportPointers,
      excludedSidecarNames = internal.sidecarNames,
    )
  return StageInstallContext(
    resolvedSource = resolvedSource,
    resolvedRepoRoot = resolvedRepoRoot,
    skillName = skillName,
    agentAddonPointers = agentAddonPointers,
    target = target,
    pointers = pointers,
    generatedSupportPointers = generatedSupportPointers,
    internal = internal,
    authored = authored,
  )
}

internal fun prepareStageInstalledSkill(input: StageInstalledSkillInput): PreparedStageInstalledSkill {
  val context = resolveStageInstallContext(input)
  validateAgentAddonPointerNamespace(
    context.skillName,
    authoredStagingNames(context.resolvedSource, context.authored).toSet() + context.internal.sidecarNames +
      context.pointers.map { it.second.name } + context.internal.supportPointers.map { it.name } +
      setOf("SKILL.md", ".content-hash", SKILL_CONTENT_IDENTITY_FILENAME),
    context.agentAddonPointers,
  )
  val artifacts =
    resolveStageInstalledSkillArtifacts(
      ResolveStageInstalledSkillArtifactsInput(
        resolvedSource = context.resolvedSource,
        input = input,
        authored = context.authored,
        pointers = context.pointers,
        internal = context.internal,
        agentAddonPointers = context.agentAddonPointers,
        suppliedCompactIdentity = input.suppliedCompactIdentity,
      ),
    )
  return PreparedStageInstalledSkill(
    resolvedSource = context.resolvedSource,
    resolvedRepoRoot = context.resolvedRepoRoot,
    skillName = context.skillName,
    agentAddonPointers = context.agentAddonPointers,
    target = context.target,
    pointers = context.pointers,
    generatedSupportPointers = context.internal.supportPointers,
    internal = context.internal,
    authored = context.authored,
    contentHash = artifacts.contentHash,
    contentIdentity = artifacts.contentIdentity,
    finalStagingDir = artifacts.finalStagingDir,
    expectedStagedNames = artifacts.expectedStagedNames,
  )
}

private fun resolveStageContentIdentity(
  resolvedSource: Path,
  suppliedCompactIdentity: String?,
): SkillContentIdentity {
  val suppliedIdentity =
    suppliedCompactIdentity?.let { compact ->
      val supplied = SkillContentIdentity.fromCompact(compact, "supplied session skill")
      SkillContentIdentity.requireMatch(
        suppliedSkillContentIdentity(resolvedSource),
        supplied,
      )
      supplied
    }
  return suppliedIdentity ?: suppliedSkillContentIdentity(resolvedSource)
}

internal fun tryReusePreparedStageInstalledSkill(
  prepared: PreparedStageInstalledSkill,
  suppliedCompactIdentity: String?,
): RenderedSkill? {
  val markerPath = prepared.finalStagingDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME)
  if (Files.isDirectory(prepared.finalStagingDir) && Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
    routeInstalledSkillBody(
      suppliedCompactIdentity ?: prepared.contentIdentity.compact(),
      prepared.finalStagingDir,
    )
  }
  if (!isReusableInstallStaging(prepared.finalStagingDir, prepared.contentHash, prepared.expectedStagedNames)) {
    return null
  }
  return reuseInstallStaging(
    ReuseInstallStagingInput(
      sourceSkillDir = prepared.resolvedSource,
      finalStagingDir = prepared.finalStagingDir,
      contentHash = prepared.contentHash,
      applicablePointers = prepared.pointers,
      generatedSupportPointers = prepared.internal.supportPointers,
      internalSidecarNames = prepared.internal.sidecarNames,
      agentAddonPointerNames = prepared.agentAddonPointers.map { it.name },
    ),
  )
}
