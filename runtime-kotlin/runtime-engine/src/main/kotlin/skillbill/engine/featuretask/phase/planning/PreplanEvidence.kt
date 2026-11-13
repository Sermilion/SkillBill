package skillbill.engine.featuretask.phase.planning

import skillbill.ports.taskruntime.model.PreplanEvidenceReference

internal object PreplanEvidence {
  fun references(digest: String): List<PreplanEvidenceReference> {
    val lines = digest.lines()
    val heading = lines.indexOfLast { line -> EVIDENCE_HEADING.matches(line.trim()) }
    if (heading < 0) return emptyList()
    return lines
      .drop(heading + 1)
      .takeWhile { line -> !MARKDOWN_HEADING.containsMatchIn(line) }
      .mapNotNull(::reference)
      .distinct()
  }

  private fun reference(line: String): PreplanEvidenceReference? {
    val match = REFERENCE_LINE.matchEntire(line.trim()) ?: return null
    val path = match.groupValues[PATH_GROUP]
    if ('/' !in path && '.' !in path) return null
    val start = match.groupValues[START_GROUP].toIntOrNull()?.takeIf { it >= 1 }
    val end = match.groupValues[END_GROUP].toIntOrNull()?.takeIf { start != null && it >= start }
    return PreplanEvidenceReference(path, start, end)
  }

  private const val PATH_GROUP: Int = 1
  private const val START_GROUP: Int = 2
  private const val END_GROUP: Int = 3

  private val EVIDENCE_HEADING: Regex =
    Regex("""^(?:#{1,6}\s*)?(?:\*\*)?(?:EVIDENCE|Evidence)(?:\*\*)?\s*:?(?:\*\*)?$""")

  private val MARKDOWN_HEADING: Regex = Regex("""^\s*#{1,6}\s""")

  private val REFERENCE_LINE: Regex =
    Regex("""^(?:[-*]|\d+[.)])?\s*`?([^\s`:]+)(?::(\d+)(?:\s*[-–]\s*(\d+))?)?`?(?:\s+.*)?$""")
}
