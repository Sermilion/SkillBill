package dev.skillbill.desktop.core.datastore

import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey

internal object FavoriteWorkKeyEncoding {
  fun encode(key: FavoriteWorkKey): String = buildString {
    append("v1;")
    appendPart(key.databaseFile.path)
    appendPart(key.databaseFile.fileKey)
    appendPart(key.workItemId)
  }

  fun decode(value: String): FavoriteWorkKey? {
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
    val path = part() ?: return null
    val fileKey = part() ?: return null
    val workItemId = part() ?: return null
    if (offset != value.length) return null
    return FavoriteWorkKey(DatabaseFileIdentity(path, fileKey), workItemId)
  }

  private fun StringBuilder.appendPart(value: String) {
    append(value.length)
    append(';')
    append(value)
  }
}
