package skillbill.engine.featuretask.slotbaseline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import skillbill.contracts.JsonCodec

internal object SlotBaselineJson {
  private val prettyJson = Json { prettyPrint = true }

  fun encode(value: Any?): String =
    when (value) {
      is String -> SlotBaselineNormalizer.normalizeText(value).trimEnd() + "\n"
      else ->
        prettyJson.encodeToString(
          JsonElement.serializer(),
          JsonCodec.valueToJsonElement(SlotBaselineNormalizer.normalize(value)),
        ) + "\n"
    }

  fun parseEmbedded(raw: String): Any? =
    raw.trimStart().takeIf { it.startsWith("{") || it.startsWith("[") }
      ?.let { runCatching { JsonCodec.parseValue(raw) }.getOrNull() }
      ?: raw
}
