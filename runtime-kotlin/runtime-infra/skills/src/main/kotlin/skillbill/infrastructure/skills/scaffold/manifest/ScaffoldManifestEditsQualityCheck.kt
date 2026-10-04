
package skillbill.infrastructure.skills.scaffold.manifest

import skillbill.error.shellcontent.invalidScaffoldPayloadError

private val QUALITY_CHECK_KEY_PATTERN =
  Regex("^declared_quality_check_file:\\s*(.+)$", RegexOption.MULTILINE)
private val DECLARED_FILES_BLOCK_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*))", RegexOption.MULTILINE)

internal fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

internal fun sanitizeCatalogDescription(description: String): String {
  val collapsed = description.replace(Regex("\\s+"), " ").trim()
  val escaped = collapsed.replace("|", "\\|")
  return escaped.ifBlank { "TODO: describe this skill." }
}

internal fun updateDeclaredQualityCheckFileText(
  text: String,
  relativeContentPath: String,
): String =
  QUALITY_CHECK_KEY_PATTERN.find(text)?.let { match ->
    text.replaceRange(match.range, "declared_quality_check_file: ${yamlScalar(relativeContentPath)}")
  } ?: run {
    val blockMatch =
      DECLARED_FILES_BLOCK_PATTERN.find(text)
        ?: throw invalidScaffoldPayloadError(
          "Manifest is missing 'declared_files:' block; refusing to append declared_quality_check_file.",
        )
    val insertion = "\ndeclared_quality_check_file: ${yamlScalar(relativeContentPath)}\n"
    text.replaceRange(blockMatch.range.last + 1, blockMatch.range.last + 1, insertion)
  }
