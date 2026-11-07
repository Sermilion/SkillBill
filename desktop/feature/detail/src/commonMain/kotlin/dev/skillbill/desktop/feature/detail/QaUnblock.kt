package dev.skillbill.desktop.feature.detail

internal data class QaUnblockEntry(
  val capability: String,
  val target: String?,
  val tried: String?,
  val evidenceDirectory: String?,
  val evidencePaths: List<String>,
  val remedy: String?,
  val snippet: String?,
)

private val UNBLOCK_HEADING = Regex("""^#{1,2}\s+unblock\s*$""", RegexOption.IGNORE_CASE)
private val SECTION_END_HEADING = Regex("""^#{1,3}\s""")
private val ENTRY_HEADING = Regex("""^####\s+(.*)$""")
private val ENTRY_FIELD = Regex("""^\s*-\s*(target|tried|evidence|remedy)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

internal fun unblockEntries(reportText: String?, directory: String?): List<QaUnblockEntry> {
  if (reportText.isNullOrBlank()) return emptyList()
  val lines = reportText.split('\n').map { it.removeSuffix("\r") }
  val section = unblockSection(lines) ?: return emptyList()
  val entries = mutableListOf<List<String>>()
  var current: MutableList<String>? = null
  var fence: String? = null
  for (line in section) {
    val marker = fenceMarker(line)
    if (fence != null) {
      current?.add(line)
      if (closesFence(line, fence)) fence = null
    } else if (marker != null) {
      current?.add(line)
      fence = marker
    } else {
      val heading = ENTRY_HEADING.matchEntire(line)
      if (heading != null) {
        current?.let(entries::add)
        current = mutableListOf(heading.groupValues[1])
      } else current?.add(line)
    }
  }
  current?.let(entries::add)
  return entries.mapNotNull { parseUnblockEntry(it, directory) }
}

private fun unblockSection(lines: List<String>): List<String>? {
  var start = -1
  var fence: String? = null
  for (index in lines.indices) {
    val line = lines[index]
    val marker = fenceMarker(line)
    if (fence != null) {
      if (closesFence(line, fence)) fence = null
    } else if (marker != null) fence = marker
    else if (UNBLOCK_HEADING.matches(line.trimEnd()) && start == -1) start = index + 1
    else if (start >= 0 && SECTION_END_HEADING.containsMatchIn(line)) return lines.subList(start, index)
  }
  return if (start >= 0) lines.subList(start, lines.size) else null
}

private fun fenceMarker(line: String): String? {
  val trimmed = line.trimStart()
  val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
  return trimmed.takeWhile { it == marker }.takeIf { it.length >= 3 }
}

private fun closesFence(line: String, fence: String): Boolean {
  val trimmed = line.trim()
  return trimmed.length >= fence.length && trimmed.all { it == fence.first() }
}

private fun parseUnblockEntry(lines: List<String>, directory: String?): QaUnblockEntry? {
  val capability = lines.firstOrNull()?.trim().orEmpty()
  if (capability.isBlank()) return null
  var target: String? = null
  var tried: String? = null
  var evidence: String? = null
  var remedy: String? = null
  var snippet: String? = null
  var fence: String? = null
  var snippetLines: MutableList<String>? = null
  for (line in lines.drop(1)) {
    val marker = fenceMarker(line)
    if (fence != null) {
      if (closesFence(line, fence)) {
        if (snippet == null) snippet = snippetLines?.joinToString("\n").orEmpty()
        fence = null
        snippetLines = null
      } else if (snippet == null) snippetLines?.add(line)
    } else if (marker != null) {
      fence = marker
      if (snippet == null) snippetLines = mutableListOf()
    } else {
      val field = ENTRY_FIELD.matchEntire(line) ?: continue
      val value = field.groupValues[2].trim().takeIf { it.isNotEmpty() }
      when (field.groupValues[1].lowercase()) {
        "target" -> target = value
        "tried" -> tried = value
        "evidence" -> evidence = value
        "remedy" -> remedy = value
      }
    }
  }
  if (fence != null && snippet == null) snippet = snippetLines?.joinToString("\n").orEmpty()
  return QaUnblockEntry(
    capability = capability,
    target = target,
    tried = tried?.let { shortenPaths(it, directory) },
    evidenceDirectory = directory,
    evidencePaths = evidence?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
    remedy = remedy?.let { shortenPaths(it, directory) },
    snippet = snippet,
  )
}
