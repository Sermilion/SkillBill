package skillbill.review.spec

object GovernedSpecSectionParser {
  const val ACCEPTANCE_CRITERIA_PREFIX: String = "acceptance criteria"
  val MANDATES_HEADINGS: Set<String> = setOf("mandates", "mandates and overrides", "mandates & overrides")

  fun parseListSection(
    specText: String,
    headingMatches: (String) -> Boolean,
  ): List<String> {
    val body = sectionBody(specText, headingMatches) ?: return emptyList()
    val items = mutableListOf<StringBuilder>()
    var topLevelIndent: Int? = null
    body.lineSequence().forEach { rawLine ->
      val line = rawLine.trimEnd()
      val item = LIST_ITEM.find(line)
      when {
        item != null -> {
          val indent = item.groupValues[1].length
          val text = CHECKBOX_PREFIX.replaceFirst(item.groupValues[2].trim(), "").trim()
          val baseIndent = topLevelIndent ?: indent.also { topLevelIndent = it }
          if (indent <= baseIndent) {
            items += StringBuilder(text)
          } else if (items.isNotEmpty()) {
            items.last().append(" Subcriterion: ").append(text)
          }
        }
        line.isBlank() -> Unit
        items.isNotEmpty() -> items.last().append(' ').append(line.trim())
      }
    }
    return items.map { it.toString().trim() }.filter(String::isNotBlank)
  }

  fun parseProseSection(
    specText: String,
    headingMatches: (String) -> Boolean,
  ): String {
    val body = sectionBody(specText, headingMatches) ?: return ""
    return body.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .joinToString(" ")
  }

  fun sectionBody(
    specText: String,
    headingMatches: (String) -> Boolean,
  ): String? {
    val lines = specText.lines()
    val outsideFence = MarkdownFences.outsideFenceMask(lines)
    val startIndex =
      lines.indices.firstOrNull { index ->
        val title = lines[index].takeIf { outsideFence[index] }?.headingTitle()?.lowercase()
        title != null && headingMatches(title)
      } ?: return null
    val endIndex =
      (startIndex + 1..lines.lastIndex).firstOrNull { index ->
        outsideFence[index] && lines[index].headingTitle() != null
      } ?: lines.size
    return lines.subList(startIndex + 1, endIndex).joinToString(separator = "\n")
  }

  fun String.headingTitle(): String? = HEADING.find(this)?.groupValues?.get(1)?.trim()

  private val HEADING = Regex("""^#{2,6}\s+(.+)$""")
  private val LIST_ITEM = Regex("""^(\s*)(?:\d+\.|[-*])\s+(.*)$""")
  private val CHECKBOX_PREFIX = Regex("""^\[[ xX]]\s*""")
}
