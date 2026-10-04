package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.ScaffoldAuthoringFailureCode
import skillbill.infrastructure.skills.scaffold.validation.shape.markdownBodyAfterFrontmatter
import java.nio.file.Files
import java.nio.file.Path

private const val FRONTMATTER_PREFIX_LENGTH = 4
private val MARKDOWN_HEADING_PATTERN = Regex("""^(#{1,6})(\s+.*)$""")
private val TITLE_HEADING_PATTERN = Regex("""^#\s+\S.*$""")
private val FENCE_START_PATTERN = Regex("""^\s*(?:```|~~~)""")

internal fun authoredContentFrontmatterBlock(
  contentText: String,
  contentFile: Path,
  skillName: String,
): String {
  val normalized = normalizeMarkdownLineEndings(contentText)
  if (!normalized.startsWith("---\n")) {
    throw SkillBillRuntimeException(
      ScaffoldAuthoringFailureCode.AUTHORING_FAILURE,
      "$contentFile: content.md for skill '$skillName' is missing YAML frontmatter.",
    )
  }
  val end = normalized.indexOf("\n---", startIndex = FRONTMATTER_PREFIX_LENGTH)
  if (end < 0) {
    throw SkillBillRuntimeException(
      ScaffoldAuthoringFailureCode.AUTHORING_FAILURE,
      "$contentFile: content.md for skill '$skillName' is missing YAML frontmatter.",
    )
  }
  return normalized.substring(0, end + "\n---".length)
}

internal fun renderAuthoredContentBody(
  contentFile: Path,
  skillName: String,
): String =
  renderedAuthoredExecutionBody(
    contentText = Files.readString(contentFile),
    contentFile = contentFile,
    skillName = skillName,
  )

internal fun renderedAuthoredExecutionBody(
  contentText: String,
  contentFile: Path,
  skillName: String,
): String {
  val normalized = normalizeMarkdownLineEndings(contentText)
  authoredContentFrontmatterBlock(normalized, contentFile, skillName)
  val authoredBody = stripAuthoredTitle(markdownBodyAfterFrontmatter(normalized)).trim('\r', '\n')
  return demoteMarkdownHeadings(authoredBody).trimEnd() + "\n"
}

internal fun normalizeMarkdownLineEndings(text: String): String =
  text
    .replace("\r\n", "\n")
    .replace('\r', '\n')

private fun stripAuthoredTitle(body: String): String {
  val lines = body.lines().toMutableList()
  val titleIndex = lines.indexOfFirst { it.isNotBlank() }
  if (titleIndex >= 0 && TITLE_HEADING_PATTERN.matches(lines[titleIndex].trim())) {
    lines.removeAt(titleIndex)
    if (titleIndex < lines.size && lines[titleIndex].isBlank()) {
      lines.removeAt(titleIndex)
    }
  }
  return lines.joinToString("\n")
}

private fun demoteMarkdownHeadings(body: String): String {
  var inFence = false
  return body.lineSequence().joinToString("\n") { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      line
    } else if (!inFence) {
      MARKDOWN_HEADING_PATTERN.replace(line) { match -> "#${match.value}" }
    } else {
      line
    }
  }
}
