package skillbill.infrastructure.sqlite.approval

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalMatchFacts
import skillbill.contracts.JsonCodec

internal object ApprovalMatchFactsColumnKeys {
  const val TYPE = "type"
  const val TEXT = "text"
  const val PATHS = "paths"
  const val COMMAND = "command"
}

internal object ApprovalMatchFactsColumn {
  fun encode(facts: ApprovalMatchFacts?): String? =
    when (facts) {
      null -> null
      is ApprovalMatchFacts.Command ->
        JsonCodec.json.encodeToString(
          buildJsonObject {
            put(ApprovalMatchFactsColumnKeys.TYPE, ApprovalMatchFactsColumnKeys.COMMAND)
            put(ApprovalMatchFactsColumnKeys.TEXT, facts.text)
          },
        )
      is ApprovalMatchFacts.Paths ->
        JsonCodec.json.encodeToString(
          buildJsonObject {
            put(ApprovalMatchFactsColumnKeys.TYPE, ApprovalMatchFactsColumnKeys.PATHS)
            put(
              ApprovalMatchFactsColumnKeys.PATHS,
              JsonArray(facts.relative.map(::JsonPrimitive)),
            )
          },
        )
    }

  fun decode(raw: String?): ApprovalMatchFacts? {
    val obj = parseObject(raw) ?: return null
    return when (stringContent(obj[ApprovalMatchFactsColumnKeys.TYPE])) {
      ApprovalMatchFactsColumnKeys.COMMAND ->
        stringContent(obj[ApprovalMatchFactsColumnKeys.TEXT])?.let(ApprovalMatchFacts::Command)
      ApprovalMatchFactsColumnKeys.PATHS -> decodePaths(obj[ApprovalMatchFactsColumnKeys.PATHS])
      else -> null
    }
  }

  private fun parseObject(raw: String?): JsonObject? {
    if (raw == null) return null
    val element =
      try {
        JsonCodec.json.parseToJsonElement(raw)
      } catch (_: SerializationException) {
        return null
      }
    return element as? JsonObject
  }

  private fun stringContent(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun decodePaths(value: JsonElement?): ApprovalMatchFacts.Paths? {
    val pathsArray = value as? JsonArray ?: return null
    val paths = pathsArray.mapNotNull(::stringContent)
    return paths.takeIf { it.size == pathsArray.size }?.let(ApprovalMatchFacts::Paths)
  }
}
