
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.infrastructure.skills.scaffold.runtime.service.contract.CONTENT_BODY_FILENAME
import skillbill.infrastructure.skills.scaffold.validation.shape.parseSkillFrontmatter
import skillbill.infrastructure.skills.scaffold.validation.shape.validateAuthoredContent
import skillbill.infrastructure.skills.scaffold.validation.shape.validateSkillMdShape
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

internal fun requireMappingField(
  manifest: Map<*, *>,
  slug: String,
  key: String,
): Map<*, *> =
  manifest[key] as? Map<*, *>
    ?: invalidManifestSchema("Platform pack '$slug': manifest field '$key' must be a mapping.")

internal fun requireField(
  manifest: Map<*, *>,
  slug: String,
  key: String,
): Any = manifest[key] ?: invalidManifestSchema("Platform pack '$slug': manifest is missing required field '$key'.")

internal fun requireStringField(
  manifest: Map<*, *>,
  slug: String,
  key: String,
): String {
  val value =
    requireField(manifest, slug, key) as? String
      ?: invalidManifestSchema("Platform pack '$slug': '$key' must be a string.")
  if (value.isBlank()) {
    invalidManifestSchema("Platform pack '$slug': '$key' must be a non-empty string.")
  }
  return value
}

internal fun parseStringList(
  slug: String,
  value: Any?,
  fieldLabel: String,
  required: Boolean,
): List<String> {
  if (value == null) {
    return emptyList()
  }
  if (value !is List<*>) {
    invalidManifestSchema("Platform pack '$slug': '$fieldLabel' must be a list of strings.")
  }
  val parsed =
    value.map { entry ->
      entry as? String
        ?: invalidManifestSchema("Platform pack '$slug': every entry in '$fieldLabel' must be a string.")
    }
  if (required && parsed.isEmpty()) {
    invalidManifestSchema("Platform pack '$slug': '$fieldLabel' must contain at least one routing signal.")
  }
  return parsed
}

internal fun validateGovernedSkill(
  pack: PlatformManifest,
  slot: String,
  skillPath: Path,
  family: String,
) {
  if (skillPath.fileName?.toString() != CONTENT_BODY_FILENAME) {
    invalidManifestSchema(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' must end in " +
        "'$CONTENT_BODY_FILENAME' but was '${displayPackPath(pack, skillPath)}'.",
    )
  }
  if (!Files.isRegularFile(skillPath)) {
    missingManifestContent(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' is missing at '$skillPath'.",
    )
  }
  val text = Files.readString(skillPath)
  validateSkillMdShape(skillPath, validateBodyShape = false)
  if (family == "quality-check") {
    val internalFor = parseSkillFrontmatter(text)["internal-for"]
    if (internalFor != PACK_SIDECAR_PARENT_SKILL) {
      invalidManifestSchema(
        "Platform pack '${pack.slug}': declared content file for slot '$slot' must declare " +
          "'internal-for: $PACK_SIDECAR_PARENT_SKILL' so stack-specific quality-check overrides install as " +
          "sidecars of $PACK_SIDECAR_PARENT_SKILL.",
      )
    }
  }
  ensureValidAuthoredContent(pack.slug, skillPath, text)
}

internal fun ensureValidAuthoredContent(
  slug: String,
  skillPath: Path,
  text: String,
) {
  val authoredIssues = validateAuthoredContent(skillPath, text)
  if (authoredIssues.isNotEmpty()) {
    missingManifestSection("Platform pack '$slug': ${authoredIssues.first()}")
  }
}

internal fun displayPackPath(
  pack: PlatformManifest,
  path: Path,
): String =
  runCatching {
    pack.packRoot.toPath().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
      .toString()
      .replace('\\', '/')
  }.getOrDefault(path.toString())

internal fun childDirectories(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}

internal fun childMarkdownFiles(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}
