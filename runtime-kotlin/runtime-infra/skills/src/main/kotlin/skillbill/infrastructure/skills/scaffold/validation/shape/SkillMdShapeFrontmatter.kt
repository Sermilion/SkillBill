package skillbill.infrastructure.skills.scaffold.validation.shape

import java.nio.file.Path
import skillbill.error.shellcontent.invalidSkillMdShape

internal val SKILL_MD_FRONTMATTER_PATTERN = Regex("""(?s)\A---\n(.*?)\n---\n""")
internal val SKILL_MD_ALLOWED_FRONTMATTER_KEYS = setOf("name", "description", "internal-for")
internal val SKILL_MD_FENCE_PATTERN = Regex("""^\s*(?:```|~~~)""")

internal fun parseSkillMdFrontmatter(frontmatter: String): Map<String, String> =
  frontmatter.lineSequence()
    .mapNotNull { line ->
      val separator = line.indexOf(':')
      if (separator < 0) {
        null
      } else {
        line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"', '\'')
      }
    }
    .toMap()

internal fun validateSkillMdBodyLine(
  path: Path,
  fileName: String,
  fileLine: Int,
  line: String,
) {
  val matchedLabel =
    when {
      TABLE_PATTERN.containsMatchIn(line) -> "markdown table"
      STEP_HEADING_PATTERN.containsMatchIn(line) -> "'## Step N:' heading"
      MCP_INSTALL_PATTERN.containsMatchIn(line) -> "MCP install gate"
      TELEMETRY_PATTERN.containsMatchIn(line) -> "telemetry instructions"
      ROUTING_RULE_PATTERN.containsMatchIn(line) -> "routing rule"
      RUN_CONTEXT_PATTERN.containsMatchIn(line) -> "run-context placeholder line"
      line.startsWith("# ") -> "H1 heading"
      Regex("""^#{3,}\s+""").containsMatchIn(line) -> "H3+ heading"
      else -> null
    }
  if (matchedLabel != null) {
    skillShapeFailure(
      "$path:$fileLine: $fileName body must not contain $matchedLabel; matched '${line.trimEnd()}'.",
    )
  }
}

internal fun skillShapeFailure(message: String): Nothing = throw invalidSkillMdShape(message)

private val TABLE_PATTERN = Regex("""^\s*\|.*\|\s*$""")
private val STEP_HEADING_PATTERN = Regex("""^##\s+Step\s+\d+[a-z]?\b""", RegexOption.IGNORE_CASE)
private val MCP_INSTALL_PATTERN = Regex("""npm install -g|readian-mcp""", RegexOption.IGNORE_CASE)
private val TELEMETRY_PATTERN =
  Regex("""\b(?:_started|_finished)\b\s*MCP|telemetry_proxy_capabilities|skillbill_[a-z_]+_(?:started|finished)""")
private val ROUTING_RULE_PATTERN = Regex("""^\s*(?:Route|Routing rule|Routing rules):""", RegexOption.IGNORE_CASE)
private val RUN_CONTEXT_PATTERN = Regex("""^\s*`(?:Review session ID|Review run ID|Applied learnings):""")
