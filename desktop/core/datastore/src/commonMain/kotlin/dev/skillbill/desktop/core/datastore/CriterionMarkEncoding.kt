package dev.skillbill.desktop.core.datastore

import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey

internal object CriterionMarkEncoding {
  fun encode(key: CriterionMarkKey, mark: CriterionMark): String = buildString {
    append("v1;")
    appendPart(key.workflowId)
    appendPart(key.criterionNumber.toString())
    appendPart(mark.wireValue)
  }

  fun decode(value: String): Pair<CriterionMarkKey, CriterionMark>? {
    if (!value.startsWith("v1;")) return null
    var offset = 3
    fun part(): String? {
      val separator = value.indexOf(';', offset)
      if (separator < 0) return null
      val length = value.substring(offset, separator).toIntOrNull() ?: return null
      if (length < 0) return null
      val start = separator + 1
      if (length > value.length - start) return null
      val end = start + length
      return value.substring(start, end).also { offset = end }
    }
    val workflowId = part() ?: return null
    val number = part()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val mark = part()?.let { raw -> CriterionMark.entries.firstOrNull { it.wireValue == raw } } ?: return null
    if (offset != value.length) return null
    return CriterionMarkKey(workflowId, number) to mark
  }

  private fun StringBuilder.appendPart(value: String) {
    append(value.length)
    append(';')
    append(value)
  }
}
