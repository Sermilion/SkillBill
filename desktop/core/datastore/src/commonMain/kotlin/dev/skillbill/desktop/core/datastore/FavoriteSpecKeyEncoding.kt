package dev.skillbill.desktop.core.datastore

import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey

internal object FavoriteSpecKeyEncoding {
  fun encode(key: FavoriteSpecKey): String = buildString {
    append("v1;")
    appendPart(key.projectId)
    appendPart(key.path)
  }

  fun decode(value: String): FavoriteSpecKey? {
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
    val projectId = part() ?: return null
    val path = part() ?: return null
    if (offset != value.length) return null
    return FavoriteSpecKey(projectId, path)
  }

  private fun StringBuilder.appendPart(value: String) {
    append(value.length)
    append(';')
    append(value)
  }
}
