package skillbill.workflow.taskruntime.phase

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys

internal object ProsePhaseOutputRecover {
  private val LEGACY_VALUE_KEYS: List<String> =
    listOf(
      "implementation_receipt",
      "simplification_receipt",
      "executable_plan",
      "preplanning_digest",
      "gaps",
    )
  private const val SUMMARY_MAX_CHARS: Int = 240
  private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237

  fun directValue(parsed: Map<String, Any?>): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed[SharedPayloadKeys.PRODUCED_OUTPUTS])
    return produced?.get(SharedPayloadKeys.VALUE)?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
      ?: (parsed[SharedPayloadKeys.VALUE] as? String)?.takeIf { it.any { ch -> !ch.isWhitespace() } }
  }

  fun recoverLegacyValue(parsed: Map<String, Any?>): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed[SharedPayloadKeys.PRODUCED_OUTPUTS])
    if (produced != null) {
      for (key in LEGACY_VALUE_KEYS) {
        val stuffed = stuffSibling(produced[key])
        if (stuffed != null) return stuffed
      }
    }
    listValue(parsed[SharedPayloadKeys.PRODUCED_OUTPUTS])?.let { return it }
    return LEGACY_VALUE_KEYS.firstNotNullOfOrNull { key -> stuffSibling(parsed[key]) }
  }

  private fun listValue(producedOutputs: Any?): String? {
    val entries = (producedOutputs as? List<*>)?.takeIf { it.isNotEmpty() } ?: return null
    val singleValue =
      entries.singleOrNull()
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(SharedPayloadKeys.VALUE)
        ?.toString()
        ?.takeIf { it.any { ch -> !ch.isWhitespace() } }
    return singleValue ?: stuffSibling(entries)
  }

  fun recoverPrompt(parsed: Map<String, Any?>?): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
    return produced?.get(SharedPayloadKeys.PROMPT)?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
      ?: parsed?.get(SharedPayloadKeys.PROMPT)?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
  }

  fun recoverSummary(
    parsed: Map<String, Any?>?,
    value: String,
  ): String {
    val fromField =
      parsed
        ?.get(SharedPayloadKeys.SUMMARY)
        ?.toString()
        ?.trim()
        ?.takeIf { it.any { ch -> !ch.isWhitespace() } }
    if (fromField != null) return fromField
    val compact = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      compact.length <= SUMMARY_MAX_CHARS -> compact.ifBlank { "Prose phase completed." }
      else -> compact.take(SUMMARY_ELLIPSIS_PREFIX) + "..."
    }
  }

  fun recoverVerdict(parsed: Map<String, Any?>?): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
    return listOf(parsed?.get(SharedPayloadKeys.VERDICT), produced?.get(SharedPayloadKeys.VERDICT))
      .firstNotNullOfOrNull { candidate -> (candidate as? String)?.trim()?.takeIf(String::isNotEmpty) }
  }

  fun recoverFailureDisposition(parsed: Map<String, Any?>?): String? =
    parsed
      ?.get(SharedPayloadKeys.FAILURE_DISPOSITION)
      ?.toString()
      ?.trim()
      ?.takeIf { it.any { ch -> !ch.isWhitespace() } }
}

private fun stuffSibling(sibling: Any?): String? =
  when (sibling) {
    null -> null
    is String -> sibling.takeIf { it.any { ch -> !ch.isWhitespace() } }
    is Map<*, *> -> JsonCodec.anyToStringAnyMap(sibling)?.let(JsonCodec::mapToJsonString)
    is List<*> -> JsonCodec.mapToJsonString(linkedMapOf("entries" to sibling))
    else -> sibling.toString().takeIf { it.any { ch -> !ch.isWhitespace() } }
  }
