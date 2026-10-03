package skillbill.engine.featuretask.runner

import java.nio.file.Files

internal object SlotBaselineFixtureCompare {
  private const val MAX_HUNK_LINES = 40

  fun assertBytesEqual(
    resourcePath: String,
    actual: String,
  ) {
    val file = SlotBaselineTestResources.resolve(resourcePath)
    check(Files.isRegularFile(file)) { "missing committed fixture $resourcePath" }
    val expected = Files.readString(file)
    check(expected == actual) { "Fixture mismatch for $resourcePath\n${unifiedDiff(expected, actual)}" }
  }

  fun unifiedDiff(
    expected: String,
    actual: String,
  ): String {
    val left = expected.lines()
    val right = actual.lines()
    var prefix = 0
    while (prefix < left.size && prefix < right.size && left[prefix] == right[prefix]) prefix += 1
    var suffix = 0
    while (
      suffix < left.size - prefix &&
      suffix < right.size - prefix &&
      left[left.size - 1 - suffix] == right[right.size - 1 - suffix]
    ) {
      suffix += 1
    }
    val oldMiddle = left.subList(prefix, left.size - suffix)
    val newMiddle = right.subList(prefix, right.size - suffix)
    val lines = mutableListOf("--- expected", "+++ actual")
    var hunkOpen = false
    var hunkLines = 0
    lineEdits(oldMiddle, newMiddle).forEach { edit ->
      if (edit.text == null) {
        hunkOpen = false
        return@forEach
      }
      if (!hunkOpen) {
        lines += "@@ expected line ${prefix + edit.oldIndex + 1}, actual line ${prefix + edit.newIndex + 1} @@"
        hunkOpen = true
        hunkLines = 0
      }
      if (hunkLines < MAX_HUNK_LINES) lines += edit.text
      hunkLines += 1
    }
    return lines.joinToString("\n")
  }

  private fun lineEdits(
    old: List<String>,
    new: List<String>,
  ): List<LineEdit> {
    val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
      for (j in new.indices.reversed()) {
        lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
      }
    }
    val edits = mutableListOf<LineEdit>()
    var i = 0
    var j = 0
    while (i < old.size || j < new.size) {
      when {
        i < old.size && j < new.size && old[i] == new[j] -> {
          edits += LineEdit(i, j, null)
          i += 1
          j += 1
        }
        i < old.size && (j == new.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
          edits += LineEdit(i, j, "-${old[i]}")
          i += 1
        }
        else -> {
          edits += LineEdit(i, j, "+${new[j]}")
          j += 1
        }
      }
    }
    return edits
  }

  private data class LineEdit(val oldIndex: Int, val newIndex: Int, val text: String?)
}
