package skillbill.infrastructure.workflow.git.changes

import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile

private const val NUL = '\u0000'
private const val NUMSTAT_FIELDS = 3
private const val BINARY_COUNT = "-"
private const val SINGLE_PATH_RECORD_TOKENS = 2
private const val PAIRED_PATH_RECORD_TOKENS = 3

private data class RawRecord(val status: WorkChangeStatus, val oldPath: String?)

private data class LineCounts(val insertions: Int?, val deletions: Int?)

internal fun parseTrackedChanges(output: String): List<WorkChangedFile>? {
  val tokens = output.split(NUL).dropLastWhile(String::isEmpty)
  val raws = linkedMapOf<String, RawRecord>()
  val counts = mutableMapOf<String, LineCounts>()
  var index = 0
  while (index < tokens.size) {
    val token = tokens[index]
    index =
      if (token.startsWith(":")) {
        parseRaw(token, tokens, index, raws)
      } else {
        parseNumstat(token, tokens, index, counts)
      } ?: return null
  }
  return raws.map { (path, raw) ->
    val lineCounts = counts[path] ?: LineCounts(0, 0)
    WorkChangedFile(path, raw.oldPath, raw.status, lineCounts.insertions, lineCounts.deletions)
  }
}

private fun parseRaw(
  header: String,
  tokens: List<String>,
  index: Int,
  raws: MutableMap<String, RawRecord>,
): Int? {
  val code = header.substringAfterLast(' ').firstOrNull() ?: return null
  val recordTokens = if (code == 'R' || code == 'C') PAIRED_PATH_RECORD_TOKENS else SINGLE_PATH_RECORD_TOKENS
  val paths = tokens.subList(index + 1, minOf(tokens.size, index + recordTokens))
  if (paths.size != recordTokens - 1) return null
  raws[paths.last()] =
    if (code == 'R') RawRecord(WorkChangeStatus.RENAMED, paths.first()) else RawRecord(rawStatus(code), null)
  return index + recordTokens
}

private fun rawStatus(code: Char): WorkChangeStatus =
  when (code) {
    'A', 'C' -> WorkChangeStatus.ADDED
    'D' -> WorkChangeStatus.DELETED
    else -> WorkChangeStatus.MODIFIED
  }

private fun parseNumstat(
  record: String,
  tokens: List<String>,
  index: Int,
  counts: MutableMap<String, LineCounts>,
): Int? {
  val fields = record.split('\t', limit = NUMSTAT_FIELDS)
  if (fields.size != NUMSTAT_FIELDS) return null
  val lineCounts = numstatCounts(fields[0], fields[1]) ?: return null
  return if (fields[2].isEmpty()) {
    val newPath = tokens.getOrNull(index + PAIRED_PATH_RECORD_TOKENS - 1) ?: return null
    counts[newPath] = lineCounts
    index + PAIRED_PATH_RECORD_TOKENS
  } else {
    counts[fields[2]] = lineCounts
    index + 1
  }
}

private fun numstatCounts(
  insertions: String,
  deletions: String,
): LineCounts? =
  if (insertions == BINARY_COUNT && deletions == BINARY_COUNT) {
    LineCounts(null, null)
  } else {
    val added = insertions.toIntOrNull()
    val removed = deletions.toIntOrNull()
    if (added == null || removed == null) null else LineCounts(added, removed)
  }
