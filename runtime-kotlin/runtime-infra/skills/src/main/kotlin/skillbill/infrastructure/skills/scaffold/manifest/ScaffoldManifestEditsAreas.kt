
package skillbill.infrastructure.skills.scaffold.manifest

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.infrastructure.skills.scaffold.runtime.service.areaFocus

private val AREAS_EMPTY_INLINE_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\[\\s*\\]\\s*$", RegexOption.MULTILINE)
private val AREAS_LIST_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\n((?:[ \\t]+-[^\\n]*\\n)*)", RegexOption.MULTILINE)
private val DECLARED_FILES_EMPTY_INLINE_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\s*\\{\\s*\\}\\s*)", RegexOption.MULTILINE)
private val AREAS_FILES_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\n)((?:    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val AREA_METADATA_EMPTY_INLINE_PATTERN =
  Regex("^area_metadata:\\s*\\{\\s*\\}\\s*$", RegexOption.MULTILINE)
private val AREA_METADATA_BLOCK_PATTERN =
  Regex("^(area_metadata:\\n)((?:  [^\\n]+\\n|    [^\\n]+\\n)*)", RegexOption.MULTILINE)

internal fun appendAreaToList(
  text: String,
  area: String,
): String {
  if (AREAS_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    return text.replace(
      AREAS_EMPTY_INLINE_PATTERN,
      "declared_code_review_areas:\n  - ${yamlScalar(area)}",
    )
  }
  val match =
    AREAS_LIST_PATTERN.find(text)
      ?: throw invalidScaffoldPayloadError(
        "Manifest is missing required 'declared_code_review_areas:' block; refusing to edit.",
      )
  val body = match.groupValues[1]
  if (Regex("^[ \\t]+-\\s*(?:\"|')?${Regex.escape(area)}(?:\"|')?\\s*$", RegexOption.MULTILINE).containsMatchIn(body)) {
    return text
  }
  val indent = detectListIndent(body).ifBlank { "  " }
  val insertion = "$indent- ${yamlScalar(area)}\n"
  return text.replaceRange(match.range, "declared_code_review_areas:\n$body$insertion")
}

internal fun appendAreaToDeclaredFiles(
  text: String,
  area: String,
  relativePath: String,
): String {
  if (DECLARED_FILES_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    val match =
      DECLARED_FILES_EMPTY_INLINE_PATTERN.find(text)
        ?: return text
    val prefix = match.groupValues[1]
    return text.replaceRange(match.range, prefix + "  areas:\n    $area: ${yamlScalar(relativePath)}\n")
  }
  val match =
    AREAS_FILES_PATTERN.find(text)
      ?: throw invalidScaffoldPayloadError("Manifest is missing 'declared_files.areas:' block; refusing to edit.")
  val prefix = match.groupValues[1]
  val header = match.groupValues[2]
  val body = match.groupValues[MANIFEST_AREAS_BODY_GROUP_INDEX]
  if (
    Regex("^$MANIFEST_AREAS_ENTRY_INDENT${Regex.escape(area)}:\\s", RegexOption.MULTILINE)
      .containsMatchIn(body)
  ) {
    return text
  }
  val insertion = "$MANIFEST_AREAS_ENTRY_INDENT$area: ${yamlScalar(relativePath)}\n"
  return text.replaceRange(match.range, prefix + header + body + insertion)
}

internal fun appendAreaMetadata(
  text: String,
  area: String,
  areaFocus: String,
): String {
  if (Regex("^  ${Regex.escape(area)}:\\s*$", RegexOption.MULTILINE).containsMatchIn(text)) {
    return text
  }
  if (AREA_METADATA_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    return text.replace(
      AREA_METADATA_EMPTY_INLINE_PATTERN,
      "area_metadata:\n  $area:\n    focus: ${yamlScalar(areaFocus)}",
    )
  }
  val match =
    AREA_METADATA_BLOCK_PATTERN.find(text)
      ?: throw invalidScaffoldPayloadError("Manifest is missing 'area_metadata:' block; refusing to edit.")
  val header = match.groupValues[1]
  val body = match.groupValues[2]
  val insertion = "  $area:\n    focus: ${yamlScalar(areaFocus)}\n"
  return text.replaceRange(match.range, header + body + insertion)
}

internal fun detectListIndent(listBody: String): String =
  listBody.lineSequence().firstOrNull { it.trimStart().startsWith("- ") }?.let { line ->
    line.substring(0, line.indexOf('-'))
  }.orEmpty()
