package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object DevinChatModelKeys {
  const val FAMILIES = "families"
  const val VARIANTS = "variants"
  const val MODEL_UID = "model_uid"
  const val LABEL = "label"
}

/** Flattens `devin models list --format json` variants in provider order; any malformed required row fails the read. */
internal fun devinChatModels(output: String): List<ChatModel>? {
  val body = runCatching { Json.parseToJsonElement(output) }.getOrNull() as? JsonObject ?: return null
  val families = body[DevinChatModelKeys.FAMILIES] as? JsonArray ?: return null
  val models = families.flatMap { family ->
    val variants = (family as? JsonObject)?.get(DevinChatModelKeys.VARIANTS) as? JsonArray ?: return null
    variants.map { variant ->
      val row = variant as? JsonObject ?: return null
      ChatModel(row.requiredText(DevinChatModelKeys.MODEL_UID) ?: return null, row.requiredText(DevinChatModelKeys.LABEL) ?: return null)
    }
  }
  return models.distinctBy { it.id }.takeIf { it.isNotEmpty() }
}

private fun JsonObject.requiredText(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
