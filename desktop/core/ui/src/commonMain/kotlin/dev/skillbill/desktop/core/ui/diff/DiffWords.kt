package dev.skillbill.desktop.core.ui.diff

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind

internal fun diffLinePrefix(kind: DiffLineKind): String = when (kind) {
  DiffLineKind.Added -> "+"
  DiffLineKind.Removed -> "−"
  DiffLineKind.Context -> " "
  DiffLineKind.NoNewlineMarker -> ""
}

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

internal sealed interface DiffWordHit {
  data class Word(val text: String, val start: Int) : DiffWordHit
  data object Ignored : DiffWordHit
  data object Blank : DiffWordHit
}

internal fun wordHitAt(line: DiffLine, offset: Int): DiffWordHit {
  val expanded = line.text.replace("\t", TAB)
  val prefixLength = diffLinePrefix(line.kind).length
  val index = offset - prefixLength
  return when {
    offset < 0 || offset >= prefixLength + displayText(line).length -> DiffWordHit.Blank
    line.kind == DiffLineKind.NoNewlineMarker || offset < prefixLength || index >= MAX_LINE_CHARS -> DiffWordHit.Ignored
    expanded[index].isWhitespace() -> DiffWordHit.Blank
    !isWordChar(expanded[index]) -> DiffWordHit.Ignored
    else -> {
      var start = index
      while (start > 0 && isWordChar(expanded[start - 1])) start--
      var end = index + 1
      while (end < expanded.length && isWordChar(expanded[end])) end++
      if (end > MAX_LINE_CHARS) DiffWordHit.Ignored else DiffWordHit.Word(expanded.substring(start, end), start + prefixLength)
    }
  }
}

internal fun wordMatchRanges(line: DiffLine, word: String): List<IntRange> {
  if (line.kind == DiffLineKind.NoNewlineMarker || word.isEmpty()) return emptyList()
  val expanded = line.text.replace("\t", TAB)
  val prefixLength = diffLinePrefix(line.kind).length
  val ranges = mutableListOf<IntRange>()
  var index = expanded.indexOf(word)
  while (index >= 0 && index + word.length <= MAX_LINE_CHARS) {
    val before = expanded.getOrNull(index - 1)
    val after = expanded.getOrNull(index + word.length)
    if ((before == null || !isWordChar(before)) && (after == null || !isWordChar(after))) {
      ranges += (index + prefixLength) until (index + word.length + prefixLength)
    }
    index = expanded.indexOf(word, index + 1)
  }
  return ranges
}

data class DiffWordSelection(val fileKey: String, val word: String, val address: DiffLineAddress, val start: Int)

@Stable
class DiffWordHighlight {
  var selection: DiffWordSelection? by mutableStateOf(null)
    private set

  fun clear() {
    selection = null
  }

  internal fun toggle(selection: DiffWordSelection) {
    val current = this.selection
    this.selection = selection.takeUnless { current?.fileKey == it.fileKey && current.word == it.word }
  }
}
