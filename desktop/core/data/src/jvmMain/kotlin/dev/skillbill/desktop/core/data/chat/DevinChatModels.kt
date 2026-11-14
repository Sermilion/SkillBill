package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private object DevinChatModelKeys {
  const val FAMILIES = "families"
  const val VARIANTS = "variants"
  const val MODEL_UID = "model_uid"
  const val LABEL = "label"
}

/**
 * The `devin models list --format json` object: families carry variants whose model_uid is the complete selectable model
 * ID. A malformed container, wrong field type or invalid ID fails the whole decode rather than dropping bad entries, and
 * family slugs or aliases never stand in for a variant's own UID.
 */
internal fun devinChatModels(output: String): List<ChatModel>? {
  val families = (parseStreamLine(output.trim())
    ?.get(DevinChatModelKeys.FAMILIES) as? JsonArray) ?: return null
  val models = mutableListOf<ChatModel>()
  for (family in families) {
    val variants = (family as? JsonObject)?.get(DevinChatModelKeys.VARIANTS) as? JsonArray ?: return null
    for (variant in variants) {
      val row = variant as? JsonObject ?: return null
      val id = row.string(DevinChatModelKeys.MODEL_UID)?.takeIf(String::isNotBlank) ?: return null
      val label = when (val raw = row[DevinChatModelKeys.LABEL]) {
        null, JsonNull -> id
        is JsonPrimitive -> if (raw.isString) raw.content.takeIf(String::isNotBlank) ?: id else return null
        else -> return null
      }
      models += ChatModel(id, label)
    }
  }
  return models.distinctBy { it.id }
}
