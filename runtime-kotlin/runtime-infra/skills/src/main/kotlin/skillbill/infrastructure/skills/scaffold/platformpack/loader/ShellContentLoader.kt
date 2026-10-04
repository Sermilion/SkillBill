
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.error.shellcontent.missingManifest
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.validation.review.ReviewSkillStructureValidator
import skillbill.infrastructure.skills.scaffold.validation.review.validateReviewSkillStructure
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

internal fun loadPlatformManifest(
  packRoot: Path,
  enforceContractVersion: Boolean = true,
): PlatformManifest {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val slug = resolvedPackRoot.fileName?.toString().orEmpty()
  val manifestPath = resolvedPackRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw missingManifest("Platform pack '$slug': expected manifest at '$manifestPath' but it is missing.")
  }
  val raw = readManifest(manifestPath, slug)
  return buildPack(slug, resolvedPackRoot, manifestPath, raw, enforceContractVersion)
}

internal fun loadPlatformPack(
  packRoot: Path,
  packsBySlug: Map<String, PlatformManifest> = emptyMap(),
  enforceGovernedReviewStructure: Boolean = false,
): PlatformManifest {
  val pack = loadPlatformManifest(packRoot)
  val closure = loadCompositionClosure(pack, packsBySlug)
  val compositionCatalog = if (packsBySlug.isNotEmpty()) packsBySlug else closure.associateBy { it.slug }
  validatePlatformPackCompositions(closure, compositionCatalog)
  validatePlatformPackFallbacks(closure)
  validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
  if (enforceGovernedReviewStructure) {
    ReviewSkillStructureValidator.validate(
      pack.packRoot.toPath(),
      compositionCatalog.mapValues { (_, inherited) -> inherited.packRoot.toPath().toAbsolutePath().normalize() },
    )
  }
  return pack
}

internal fun discoverPlatformPacks(platformPacksRoot: Path): List<PlatformManifest> {
  val packs = childDirectories(platformPacksRoot).map(::loadPlatformManifest)
  validatePlatformPackCompositions(packs)
  validatePlatformPackFallbacks(packs)
  packs.forEach { pack ->
    validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
  }
  return packs
}

fun discoverPlatformPackManifests(
  platformPacksRoot: Path,
  enforceContractVersion: Boolean = true,
): List<PlatformManifest> {
  val packs =
    childDirectories(platformPacksRoot).map { packRoot ->
      loadPlatformManifest(packRoot, enforceContractVersion)
    }
  validatePlatformPackCompositions(packs)
  validatePlatformPackFallbacks(packs)
  return packs
}

internal fun validatePlatformPackFallbacks(packs: List<PlatformManifest>) {
  packs.flatMap { pack -> pack.fallbackCapabilities.map { it to pack } }
    .groupBy({ it.first }, { it.second })
    .forEach { (capability, owners) ->
      if (owners.size > 1) {
        invalidFallbackCapability(
          "Fallback capability '$capability' has multiple owners: ${owners.map { it.slug }.sorted()}.",
        )
      }
      val owner = owners.single()
      if (capability == CODE_REVIEW_FALLBACK_CAPABILITY && owner.declaredFiles.baseline == null) {
        invalidFallbackCapability(
          "Platform pack '${owner.slug}' declares fallback capability '$capability' without a code-review baseline.",
        )
      }
    }
}

internal fun discoverGovernedAddonFiles(repoRoot: Path): List<GovernedAddonFile> {
  val packsRoot = repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return emptyList()
  }
  return childDirectories(packsRoot).flatMap { packDir ->
    val addonsRoot = packDir.resolve("addons")
    if (!Files.isDirectory(addonsRoot)) {
      emptyList()
    } else {
      childMarkdownFiles(
        addonsRoot,
      ).map { addon -> GovernedAddonFile(packDir.fileName.toString(), addon.toFileLocation()) }
    }
  }
}

internal fun validatePlatformPack(
  pack: PlatformManifest,
  contractVersion: String,
  enforceContractVersion: Boolean = true,
  enforceGovernedReviewStructure: Boolean = true,
) {
  if (enforceContractVersion && pack.contractVersion != contractVersion) {
    contractVersionMismatch(
      buildString {
        append("Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' ")
        append("but the shell expects '$contractVersion'.")
      },
    )
  }

  val declaredAreaFiles = pack.declaredFiles.areas
  val missingAreaSlots = pack.declaredCodeReviewAreas.toSet() - declaredAreaFiles.keys
  if (missingAreaSlots.isNotEmpty()) {
    invalidManifestSchema(
      "Platform pack '${pack.slug}': declared_files.areas is missing entries for ${missingAreaSlots.sorted()}.",
    )
  }

  pack.declaredFiles.baseline?.let { baseline ->
    validateGovernedSkill(pack, "baseline", baseline.toPath(), "code-review")
  }
  pack.declaredCodeReviewAreas.forEach { area ->
    validateGovernedSkill(pack, "areas.$area", declaredAreaFiles.getValue(area).toPath(), "code-review")
  }
  if (enforceGovernedReviewStructure) {
    validateReviewSkillStructure(pack)
  }
}
