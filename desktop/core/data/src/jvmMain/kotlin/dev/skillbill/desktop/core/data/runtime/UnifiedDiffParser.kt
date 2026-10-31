package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/**
 * Parses git's unified diff for one file. Lines outside a hunk body are file headers and are skipped. The body length comes
 * from the hunk header's counts, so a removed `--x` line (`---x`) or an added `++x` line is never mistaken for a header.
 */
internal fun parseUnifiedDiff(text: String): FileDiff {
  val hunks = mutableListOf<DiffHunk>()
  var header: String? = null
  var lines = mutableListOf<DiffLine>()
  var oldNext = 0
  var newNext = 0
  var oldLeft = 0
  var newLeft = 0
  var insertions = 0
  var deletions = 0

  fun closeHunk() {
    header?.let { hunks += DiffHunk(it, lines) }
    header = null
    lines = mutableListOf()
  }

  for (line in text.removeSuffix("\n").takeIf(String::isNotEmpty)?.split('\n').orEmpty()) {
    val inBody = oldLeft > 0 || newLeft > 0
    when {
      header != null && line.startsWith('\\') -> lines += DiffLine(DiffLineKind.NoNewlineMarker, null, null, line)
      inBody && line.startsWith('+') -> {
        lines += DiffLine(DiffLineKind.Added, null, newNext++, line.substring(1))
        newLeft--
        insertions++
      }
      inBody && line.startsWith('-') -> {
        lines += DiffLine(DiffLineKind.Removed, oldNext++, null, line.substring(1))
        oldLeft--
        deletions++
      }
      inBody -> {
        lines += DiffLine(DiffLineKind.Context, oldNext++, newNext++, line.drop(1))
        oldLeft--
        newLeft--
      }
      else -> HUNK_HEADER.find(line)?.let { match ->
        closeHunk()
        val (a, b, c, d) = match.destructured
        header = line
        oldNext = a.toInt()
        newNext = c.toInt()
        oldLeft = b.toIntOrNull() ?: 1
        newLeft = d.toIntOrNull() ?: 1
      }
    }
  }
  closeHunk()
  return FileDiff(hunks, insertions, deletions)
}
