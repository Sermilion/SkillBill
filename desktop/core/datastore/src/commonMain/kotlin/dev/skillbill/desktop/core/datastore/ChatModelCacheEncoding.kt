package dev.skillbill.desktop.core.datastore

import dev.skillbill.desktop.core.domain.model.ChatModel

/** One stored model list per agent profile. */
internal data class StoredChatModels(
  val agentId: String,
  val profile: String?,
  val models: List<ChatModel>,
)

internal object ChatModelCacheEncoding {
  fun encode(entry: StoredChatModels): String = buildString {
    append("v1;")
    appendPart(entry.agentId)
    appendPart(if (entry.profile == null) "0" else "1")
    appendPart(entry.profile.orEmpty())
    appendPart(entry.models.size.toString())
    entry.models.forEach { model ->
      appendPart(model.id)
      appendPart(model.label)
    }
  }

  fun decode(value: String): StoredChatModels? {
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
    val agentId = part()?.takeIf(String::isNotBlank) ?: return null
    val hasProfile = when (part()) {
      "1" -> true
      "0" -> false
      else -> return null
    }
    val rawProfile = part() ?: return null
    val profile = rawProfile.takeIf { hasProfile }
    val count = part()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val models = List(count) {
      val id = part() ?: return null
      val label = part() ?: return null
      ChatModel(id, label)
    }
    if (offset != value.length) return null
    return StoredChatModels(agentId, profile, models)
  }

  private fun StringBuilder.appendPart(value: String) {
    append(value.length)
    append(';')
    append(value)
  }
}
