package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import skillbill.infrastructure.host.jvm.rollbackRestoreBytes
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.addonUsageFor
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.displayNameFromSlug
import skillbill.install.model.ListedSkillNames
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val PRE_SHELL_PLATFORM_PATH_PARTS = 3

internal fun selectedTargets(
  repoRoot: Path,
  skillNames: List<String>,
  externalDiscovery: PlatformPackDiscoveryContext? = null,
): List<AuthoringTarget> {
  val targets = discoverTargets(repoRoot, externalDiscovery = externalDiscovery)
  if (skillNames.isEmpty()) {
    return targets.values.sortedBy { target -> target.skillName }
  }
  val missing = skillNames.toSet() - targets.keys
  if (missing.isNotEmpty()) {
    throw SkillBillRuntimeException("Unknown content-managed skill(s): ${missing.sorted().joinToString(", ")}.")
  }
  return skillNames.map { skillName -> targets.getValue(skillName) }
}

internal fun <T> runWithUpgradeRollback(
  originalBytes: Map<Path, ByteArray>,
  createdPaths: List<Path> = emptyList(),
  block: () -> T,
): T =
  try {
    block()
  } catch (error: SkillBillRuntimeException) {
    rollbackUpgrade(originalBytes, createdPaths)
    throw error
  } catch (error: IOException) {
    rollbackUpgrade(originalBytes, createdPaths)
    throw error
  } catch (error: IllegalArgumentException) {
    rollbackUpgrade(originalBytes, createdPaths)
    throw error
  }

private fun rollbackUpgrade(
  originalBytes: Map<Path, ByteArray>,
  createdPaths: List<Path>,
) {
  restoreFiles(originalBytes)
  createdPaths.asReversed().forEach { path ->
    if (path !in originalBytes) {
      rollbackDeleteIfExists(path)
    }
  }
}

internal fun resolveTarget(
  repoRoot: Path,
  skillName: String,
  externalDiscovery: PlatformPackDiscoveryContext? = null,
): AuthoringTarget =
  discoverTargets(repoRoot, externalDiscovery = externalDiscovery)[skillName]
    ?: throw SkillBillRuntimeException(
      "Skill '$skillName' is not a content-managed skill with a sibling content.md file.",
    )

internal fun discoverTargets(
  repoRoot: Path,
  enforceContractVersion: Boolean = true,
  externalDiscovery: PlatformPackDiscoveryContext? = null,
): Map<String, AuthoringTarget> {
  val discovered = linkedMapOf<String, AuthoringTarget>()
  platformPacksForAuthoring(repoRoot, enforceContractVersion, externalDiscovery).forEach { pack ->
    recordPackTargets(discovered, pack)
  }

  val skillsRoot = repoRoot.resolve("skills")
  if (!Files.isDirectory(skillsRoot)) {
    validateInternalSkillClassification(discovered)
    return discovered
  }
  Files.walk(skillsRoot).use { stream ->
    stream
      .filter { path -> path.name == "content.md" }
      .sorted()
      .forEach { contentFile -> recordSkillTarget(repoRoot, discovered, contentFile) }
  }
  validateInternalSkillClassification(discovered)
  return discovered
}

private fun platformPacksForAuthoring(
  repoRoot: Path,
  enforceContractVersion: Boolean,
  externalDiscovery: PlatformPackDiscoveryContext?,
): List<PlatformManifest> {
  val checkoutPacks = repoRoot.resolve("platform-packs")
  if (externalDiscovery == null) {
    return discoverPlatformPackManifests(checkoutPacks, enforceContractVersion)
  }
  val loader =
    externalDiscovery.catalogLoader ?: return discoverPlatformPackManifests(
      checkoutPacks,
      enforceContractVersion,
    )
  return loader.loadEffectiveManifests(
    externalDiscovery.copy(
      repoRoot = repoRoot.toAbsolutePath().normalize(),
      enforceContractVersion = enforceContractVersion,
    ),
  )
}

private fun restoreFiles(originalBytes: Map<Path, ByteArray>) {
  originalBytes.forEach { (path, bytes) -> rollbackRestoreBytes(path, bytes) }
}

private fun recordPackTargets(
  discovered: MutableMap<String, AuthoringTarget>,
  pack: PlatformManifest,
) {
  val displayName = pack.displayName ?: displayNameFromSlug(pack.slug)
  pack.declaredFiles.baseline?.let { baseline ->
    val baselineContent = declaredContentFile(baseline.toPath())
    discovered[baselineContent.parent.name] =
      AuthoringTarget(
        baselineContent.parent.name,
        pack.slug,
        pack.slug,
        displayName,
        "code-review",
        "",
        baselineContent.resolveSibling("SKILL.md"),
        baselineContent,
        pack.codeReviewComposition,
        pack.addonUsageFor(baselineContent),
        internalFor = parseInternalForFrontmatter(baselineContent),
      )
  }
  pack.declaredFiles.areas.forEach { (area, declaredFile) ->
    val contentFile = declaredContentFile(declaredFile.toPath())
    discovered[contentFile.parent.name] =
      AuthoringTarget(
        contentFile.parent.name,
        pack.slug,
        pack.slug,
        displayName,
        "code-review",
        area,
        contentFile.resolveSibling("SKILL.md"),
        contentFile,
        addonUsage = pack.addonUsageFor(contentFile),
        internalFor = parseInternalForFrontmatter(contentFile),
      )
  }
}

private fun recordSkillTarget(
  repoRoot: Path,
  discovered: MutableMap<String, AuthoringTarget>,
  contentFile: Path,
) {
  val skillFile = contentFile.resolveSibling("SKILL.md")
  val skillName = contentFile.parent.name
  if (skillName in discovered) {
    return
  }
  val platform = platformFromSkillPath(repoRoot.relativize(contentFile))
  val packageName = platform.ifBlank { "base" }
  val family = inferFamily(skillName)
  val displayName =
    platform.takeIf { it.isNotBlank() }?.let(::displayNameFromSlug)
      ?: displayNameFromSlug(skillName.removePrefix("bill-"))

  val internalFor = parseInternalForFrontmatter(contentFile)
  discovered[skillName] =
    AuthoringTarget(
      skillName,
      packageName,
      platform,
      displayName,
      family,
      inferArea(skillName, family),
      skillFile,
      contentFile,
      internalFor = internalFor,
    )
}

private fun declaredContentFile(declaredFile: Path): Path = declaredFile

private fun platformFromSkillPath(relative: Path): String =
  if (
    relative.nameCount >= PRE_SHELL_PLATFORM_PATH_PARTS &&
    relative.getName(0).toString() == "skills" &&
    !ListedSkillNames.isListed(relative.getName(1).toString())
  ) {
    relative.getName(1).toString()
  } else {
    ""
  }
