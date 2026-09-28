package skillbill.infrastructure.skills.install.plan

import skillbill.infrastructure.skills.install.identity.suppliedSkillContentIdentity
import skillbill.infrastructure.skills.scaffold.authoring.InternalSkillDeclaration
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.infrastructure.skills.scaffold.authoring.requireValidInternalSkillClassification
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPack
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.validation.review.ReviewSkillStructureValidator
import skillbill.infrastructure.skills.scaffold.validation.review.contentFiles
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.ListedSkillNames
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun discoverPlatformManifests(
  request: InstallPlanRequest,
  enforceContractVersion: Boolean = true,
  catalogLoader: PlatformPackCatalogLoader? = null,
  enforceGovernedReviewStructure: Boolean = true,
): List<PlatformManifest> {
  val loader =
    catalogLoader ?: return discoverPlatformPackManifests(
      request.repoRoot.toPath().resolve("platform-packs"),
      enforceContractVersion,
    )
  return loader.loadEffectiveManifests(
    PlatformPackDiscoveryContext(
      repoRoot = request.repoRoot.toPath(),
      userHome = request.home.toPath(),
      environment = request.environment,
      enforceContractVersion = enforceContractVersion,
      catalogLoader = loader,
      enforceGovernedReviewStructure = enforceGovernedReviewStructure,
    ),
  )
}

internal fun discoverBaseSkills(skillsRoot: Path): List<InstallPlanSkill> {
  if (!Files.isDirectory(skillsRoot)) {
    throw FileNotFoundException("Base skills root '$skillsRoot' does not exist or is not a directory.")
  }
  val candidateSkillDirs =
    Files.list(skillsRoot).use { stream ->
      stream
        .filter { skillDir -> Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS) }
        .filter { skillDir -> ListedSkillNames.isListed(skillDir.fileName.toString()) }
        .toList()
        .sortedBy { skillDir -> skillDir.fileName.toString() }
    }
  val missingContent =
    candidateSkillDirs
      .filterNot { skillDir -> Files.isRegularFile(skillDir.resolve("content.md"), LinkOption.NOFOLLOW_LINKS) }
  require(missingContent.isEmpty()) {
    "Base skills root '$skillsRoot' contains listed skill directories without content.md: " +
      missingContent.joinToString(", ") { skillDir -> skillDir.fileName.toString() }
  }
  val baseSkills =
    candidateSkillDirs
      .map { skillDir ->

        suppliedSkillContentIdentity(skillDir)
        InstallPlanSkill(
          name = skillDir.fileName.toString(),
          sourceDir = skillDir.toAbsolutePath().normalize().toFileLocation(),
          kind = InstallPlanSkillKind.BASE,
          internalFor = parseInternalForFrontmatter(skillDir.resolve("content.md")),
        )
      }
  require(baseSkills.isNotEmpty()) {
    "Base skills root '$skillsRoot' does not contain any bill-* skills with content.md."
  }
  return baseSkills
}

internal fun validateInstallPlanInternalSkills(skills: List<InstallPlanSkill>) {
  requireValidInternalSkillClassification(
    skills.map { skill ->
      InternalSkillDeclaration(
        skillName = skill.name,
        contentFile = skill.sourceDir.resolve("content.md").toPath(),
        declaredParent = skill.internalFor,
        isBaseSkill = skill.kind == InstallPlanSkillKind.BASE,
      )
    },
  )
}

internal fun platformSkills(
  manifest: PlatformManifest,
  enforceContractVersion: Boolean = true,
  packRootsBySlug: Map<String, Path> = emptyMap(),
  enforceGovernedReviewStructure: Boolean = true,
): List<InstallPlanSkill> {
  val contentFiles = listOfNotNull(manifest.declaredFiles.baseline) + manifest.declaredFiles.areas.values
  val skillDirs = contentFiles.map { contentFile -> platformSkillDir(manifest, contentFile.toPath()) }
  val duplicateSkillDir = skillDirs.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
  require(duplicateSkillDir == null) {
    "Platform pack '${manifest.slug}' produces duplicate skill name '${duplicateSkillDir?.fileName}'."
  }
  validatePlatformPack(
    manifest,
    SHELL_CONTRACT_VERSION,
    enforceContractVersion,
    enforceGovernedReviewStructure,
  )
  if (enforceGovernedReviewStructure) {
    ReviewSkillStructureValidator.validate(manifest.packRoot.toPath(), packRootsBySlug)
  }
  return skillDirs
    .sortedBy { skillDir -> skillDir.fileName.toString() }
    .map { skillDir ->

      suppliedSkillContentIdentity(skillDir)
      InstallPlanSkill(
        name = skillDir.fileName.toString(),
        sourceDir = skillDir.toFileLocation(),
        kind = InstallPlanSkillKind.PLATFORM_PACK,
        platformSlug = manifest.slug,
        internalFor = parseInternalForFrontmatter(skillDir.resolve("content.md")),
      )
    }
}

private fun platformSkillDir(
  manifest: PlatformManifest,
  contentFile: Path,
): Path {
  val resolvedPackRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
  val resolvedContentFile = contentFile.toAbsolutePath().normalize()
  require(resolvedContentFile.startsWith(resolvedPackRoot)) {
    "Platform pack '${manifest.slug}' declared content file '$resolvedContentFile' escapes packRoot " +
      "'$resolvedPackRoot'."
  }
  if (!Files.exists(resolvedContentFile, LinkOption.NOFOLLOW_LINKS)) {
    return resolvedContentFile.parent
  }
  val realPackRoot = manifest.packRoot.toPath().toRealPath()
  val realContentFile = contentFile.toRealPath()
  require(realContentFile.startsWith(realPackRoot)) {
    "Platform pack '${manifest.slug}' declared content file '$resolvedContentFile' escapes packRoot " +
      "'$resolvedPackRoot' through real path '$realContentFile'."
  }
  return resolvedContentFile.parent
}
