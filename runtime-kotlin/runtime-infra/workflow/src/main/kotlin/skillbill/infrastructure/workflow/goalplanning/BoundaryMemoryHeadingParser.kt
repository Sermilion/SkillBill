package skillbill.infrastructure.workflow.goalplanning

import skillbill.infrastructure.contracts.sha256Hex
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class BoundaryMemoryEntry(
  val headingId: String,
  val heading: String,
  val body: String,
)

object BoundaryMemoryHeadingParser {
  private val ENTRY_HEADING = Regex("^##\\s+\\[[^\\[\\]]+]\\s+\\S.*$")
  private val ENTRY_DATE = Regex("^##\\s+\\[([^\\[\\]]+)]")
  private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")
  private const val HEADING_ID_DIGEST_CHARS = 12
  private const val BYTE_ORDER_MARK = '\uFEFF'

  fun parse(
    sourcePath: String,
    content: String,
  ): List<BoundaryMemoryEntry> {
    val lines = content.trimStart(BYTE_ORDER_MARK).replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val fenced = scan(lines, honourFences = true)
    val parsed = if (fenced.unclosedFence) scan(lines, honourFences = false).entries else fenced.entries
    return withStableIds(sourcePath, parsed)
  }

  fun headingId(
    sourcePath: String,
    heading: String,
    occurrence: Int = 0,
  ): String = "$sourcePath#${digest(heading)}" + if (occurrence == 0) "" else "-$occurrence"

  fun sourcePathOf(headingId: String): String? = headingId.substringBeforeLast('#', "").takeIf(String::isNotEmpty)

  fun entryDate(heading: String): LocalDate? {
    val dateText = ENTRY_DATE.find(heading.trim())?.groupValues?.get(1) ?: return null
    return try {
      LocalDate.parse(dateText)
    } catch (_: DateTimeParseException) {
      null
    }
  }

  private data class Scan(val entries: List<Pair<String, String>>, val unclosedFence: Boolean)

  private fun scan(
    lines: List<String>,
    honourFences: Boolean,
  ): Scan {
    val entries = mutableListOf<Pair<String, String>>()
    var heading: String? = null
    val body = StringBuilder()
    var openFence: String? = null
    for (line in lines) {
      if (honourFences) openFence = fenceStateAfter(openFence, line)
      if (openFence != null || !ENTRY_HEADING.matches(line)) {
        if (heading != null) body.append(line).append('\n')
      } else {
        heading?.let { current -> entries.add(current to body.toString()) }
        heading = line.trim()
        body.setLength(0)
      }
    }
    heading?.let { current -> entries.add(current to body.toString()) }
    return Scan(entries, unclosedFence = openFence != null)
  }

  private fun fenceStateAfter(
    openFence: String?,
    line: String,
  ): String? {
    val marker = FENCE.find(line)?.groupValues?.get(1) ?: return openFence
    return when {
      openFence == null -> marker
      marker[0] == openFence[0] && marker.length >= openFence.length -> null
      else -> openFence
    }
  }

  private fun withStableIds(
    sourcePath: String,
    parsed: List<Pair<String, String>>,
  ): List<BoundaryMemoryEntry> {
    val occurrences = mutableMapOf<String, Int>()
    return parsed.map { (heading, body) ->
      val occurrence = occurrences.merge(heading, 1, Int::plus)!! - 1
      BoundaryMemoryEntry(
        headingId = headingId(sourcePath, heading, occurrence),
        heading = heading,
        body = body.trim(),
      )
    }
  }

  private fun digest(text: String): String = sha256Hex(text).take(HEADING_ID_DIGEST_CHARS)
}
