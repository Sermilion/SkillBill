package skillbill.infrastructure.skills.install.plan

import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.agentAddonPointersForSkill
import skillbill.infrastructure.skills.install.identity.authoredStagingNames
import skillbill.infrastructure.skills.install.identity.validateAgentAddonPointerNamespace
import skillbill.infrastructure.skills.install.staging.GeneratedSupportPointer
import skillbill.infrastructure.skills.install.staging.InstallContentHashInputs
import skillbill.infrastructure.skills.install.staging.InternalStagingPreparation
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.authoredFilesFor
import skillbill.infrastructure.skills.install.staging.computeInstallContentHash
import skillbill.infrastructure.skills.install.staging.generatedSupportPointersFor
import skillbill.infrastructure.skills.install.staging.installedSkillStagingDir
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.prepareInternalStaging
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformSlugs
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private data class StagingIntentContext(
  val request: InstallPlanRequest,
  val platformManifests: List<PlatformManifest>,
  val selectedPackSkills: List<InstallPlanSkill>,
  val selectedPlatformManifests: List<PlatformManifest>,
  val catalogLoader: PlatformPackCatalogLoader?,
)

internal fun buildInstallStagingIntent(
  request: InstallPlanRequest,
  draftSkills: List<InstallPlanSkill>,
  platformManifests: List<PlatformManifest>,
  catalogLoader: PlatformPackCatalogLoader? = null,
): InstallStagingIntent {
  val stagingRoot = installedSkillsCacheRoot(request.home.toPath())
  val selectedPackSkills =
    draftSkills.filter { skill ->
      skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null
    }
  val selectedSlugs = selectedPlatformSlugs(draftSkills, platformManifests)
  val selectedManifests = platformManifests.filter { manifest -> manifest.slug in selectedSlugs }
  val context =
    StagingIntentContext(
      request,
      platformManifests,
      selectedPackSkills,
      selectedManifests,
      catalogLoader,
    )
  return InstallStagingIntent(
    root = stagingRoot.toFileLocation(),
    skillPaths =
      draftSkills.filter { skill -> skill.internalFor == null }
        .map { skill -> buildSkillStagingPathIntent(context, skill, stagingRoot) },
  )
}

private fun buildSkillStagingPathIntent(
  context: StagingIntentContext,
  skill: InstallPlanSkill,
  stagingRoot: Path,
): InstallStagingPathIntent {
  val request = context.request
  val pointers = applicablePointers(request.repoRoot.toPath(), skill.sourceDir.toPath(), context.platformManifests)
  val supportPointers =
    generatedSupportPointersFor(
      repoRoot = request.repoRoot.toPath(),
      sourceSkillDir = skill.sourceDir.toPath(),
      skillName = skill.name,
      skillsRoot = request.targetPaths.skillsRoot.toPath(),
      selectedPlatformManifests = context.selectedPlatformManifests,
    )
  val internal =
    prepareInternalStaging(
      InternalStagingPreparation(
        repoRoot = request.repoRoot.toPath(),
        parentSourceDir = skill.sourceDir.toPath(),
        parentSkillName = skill.name,
        skillsRoot = request.targetPaths.skillsRoot.toPath(),
        selectedPackSkills = context.selectedPackSkills,
        platformManifests = context.platformManifests,
        selectedPlatformManifests = context.selectedPlatformManifests,
        parentSupportPointers = supportPointers,
        parentPointerNames = pointers.map { (_, pointer) -> pointer.name }.toSet(),
        userHome = request.home.toPath(),
        environment = request.environment,
        catalogLoader = context.catalogLoader,
      ),
    )
  validatePointerInputs(request.repoRoot.toPath(), skill.sourceDir.toPath(), pointers, internal.supportPointers)
  val authored = authoredFilesFor(skill.sourceDir.toPath(), pointers, internal.supportPointers, internal.sidecarNames)
  val addonPointers = agentAddonPointersForSkill(request.repoRoot.toPath(), skill.name)
  validateAgentAddonPointerNamespace(
    skill.name,
    authoredStagingNames(skill.sourceDir.toPath(), authored) + internal.sidecarNames + pointers.map { it.second.name } +
      internal.supportPointers.map { it.name } +
      listOf("SKILL.md", ".content-hash", SKILL_CONTENT_IDENTITY_FILENAME),
    addonPointers,
  )
  val contentHash =
    computeInstallContentHash(
      InstallContentHashInputs(
        sourceSkillDir = skill.sourceDir.toPath(),
        authored = authored,
        applicablePointers = pointers,
        generatedSupportPointers = internal.supportPointers,
        internalChildren = internal.children,
        agentAddonPointers = addonPointers,
        checkoutRepoRoot = request.repoRoot.toPath(),
      ),
    )
  return InstallStagingPathIntent(
    skillName = skill.name,
    sourceDir = skill.sourceDir,
    stagingRoot = stagingRoot.toFileLocation(),
    stagingDir =
      installedSkillStagingDir(
        request.home.toPath(),
        skill.sourceDir.toPath(),
        contentHash,
      ).toFileLocation(),
    contentHash = contentHash,
  )
}

private fun validatePointerInputs(
  repoRoot: Path,
  sourceSkillDir: Path,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
  supportPointers: List<GeneratedSupportPointer>,
) {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val realRepoRoot = repoRoot.toRealPath()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  pointers.forEach { (manifest, spec) ->
    val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
    val pointerFile = packRoot.resolve(spec.skillRelativeDir).normalize().resolve(spec.name).normalize()
    val targetFile = resolvedRepoRoot.resolve(spec.target).normalize()
    validatePointerTarget(
      name = spec.name,
      targetFile = targetFile,
      pointerFile = pointerFile,
      roots = PointerValidationRoots(resolvedRepoRoot, realRepoRoot, packRoot),
    )
  }
  supportPointers.forEach { pointer ->
    val targetFile = pointer.target.toAbsolutePath().normalize()
    val pointerFile = resolvedSource.resolve(pointer.name).normalize()
    require(pointerFile.startsWith(resolvedSource)) {
      "Supporting pointer '${pointer.name}' staging path '$pointerFile' escapes source skill dir '$resolvedSource'."
    }
    require(targetFile.startsWith(resolvedRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot'."
    }
    require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      "Supporting pointer '${pointer.name}' targets '$targetFile' which does not exist."
    }
    require(targetFile.toRealPath().startsWith(realRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot' " +
        "through its real path."
    }
    require(pointerFile != targetFile) {
      "Supporting pointer '${pointer.name}' resolves to itself at '$targetFile'."
    }
  }
}

private data class PointerValidationRoots(
  val repoRoot: Path,
  val realRepoRoot: Path,
  val packRoot: Path,
)

private fun validatePointerTarget(
  name: String,
  targetFile: Path,
  pointerFile: Path,
  roots: PointerValidationRoots,
) {
  require(targetFile.startsWith(roots.repoRoot)) {
    "Pointer '$name' target '$targetFile' escapes repoRoot '${roots.repoRoot}'."
  }
  require(pointerFile.startsWith(roots.packRoot)) {
    "Pointer '$name' path '$pointerFile' escapes pack root '${roots.packRoot}'."
  }
  require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
    "Pointer '$name' target '$targetFile' does not exist as a regular file."
  }
  require(targetFile.toRealPath().startsWith(roots.realRepoRoot)) {
    "Pointer '$name' target '$targetFile' escapes repoRoot '${roots.repoRoot}' through its real path."
  }
  require(pointerFile != targetFile) { "Pointer '$name' resolves to itself at '$targetFile'." }
}
