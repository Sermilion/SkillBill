
package skillbill.infrastructure.skills.scaffold.manifest

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.infrastructure.skills.scaffold.runtime.service.areaFocus
import java.nio.file.Path

internal fun appendCodeReviewArea(
  manifestPath: Path,
  area: String,
  relativeContentPath: String,
  areaFocus: String,
) {
  val original = manifestPath.toFile().readText()
  var updated = original
  updated = appendAreaToList(updated, area)
  updated = appendAreaToDeclaredFiles(updated, area, relativeContentPath)
  updated = appendAreaMetadata(updated, area, areaFocus)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun setDeclaredQualityCheckFile(
  manifestPath: Path,
  relativeContentPath: String,
) {
  val original = manifestPath.toFile().readText()
  val updated = updateDeclaredQualityCheckFileText(original, relativeContentPath)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun appendGovernedAddonManifestRegistration(
  manifestPath: Path,
  platform: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
) {
  val original = manifestPath.toFile().readText()
  val updated = renderGovernedAddonManifestRegistration(original, platform, skillRelativeDirs, addonSlug)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun appendExternalAddonManifestRegistration(
  manifestPath: Path,
  skillRelativeDirs: List<String>,
  addonSlug: String,
) {
  val original = manifestPath.toFile().readText()
  val updated = renderExternalAddonManifestRegistration(original, skillRelativeDirs, addonSlug)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun renderGovernedAddonManifestRegistration(
  text: String,
  platform: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
): String {
  val pointerName = "$addonSlug.md"
  val target = "platform-packs/$platform/addons/$pointerName"
  return skillRelativeDirs.fold(text) { current, skillRelativeDir ->
    val withPointer = appendManifestPointer(current, skillRelativeDir, pointerName, target)
    appendAddonUsage(withPointer, skillRelativeDir, addonSlug, pointerName)
  }
}

internal fun renderExternalAddonManifestRegistration(
  text: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
): String {
  val pointerName = "$addonSlug.md"
  return skillRelativeDirs.fold(text) { current, skillRelativeDir ->
    val withPointer = appendManifestPointer(current, skillRelativeDir, pointerName, pointerName)
    appendAddonUsage(withPointer, skillRelativeDir, addonSlug, pointerName)
  }
}

internal fun appendReadmeCatalogRow(
  readmePath: Path,
  skillName: String,
  description: String,
) {
  val original = readmePath.toFile().readText()
  val updated = renderReadmeCatalogRow(original, skillName, description)
  if (updated != original) {
    readmePath.toFile().writeText(updated)
  }
}

internal fun renderReadmeCatalogRow(
  text: String,
  skillName: String,
  description: String,
): String {
  val rows = findReadmeCatalogRows(text)
  if (rows.isEmpty()) {
    throw invalidScaffoldPayloadError(
      "README.md does not contain a `/bill-*` catalog table; refusing to append a row for $skillName.",
    )
  }
  if (rows.any { match -> match.groupValues[1] == skillName }) {
    return text
  }
  val safeDescription = sanitizeCatalogDescription(description)
  val newRow = "| `/$skillName` | $safeDescription |"
  val insertAfter =
    rows
      .lastOrNull { match -> match.groupValues[1].compareTo(skillName) < 0 }
  return if (insertAfter != null) {
    val anchor = insertAfter.range.last + 1
    text.substring(0, anchor) + "\n" + newRow + text.substring(anchor)
  } else {
    val anchor = rows.first().range.first
    text.substring(0, anchor) + newRow + "\n" + text.substring(anchor)
  }
}
