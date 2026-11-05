package skillbill.contracts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CanonicalJson {
  fun encode(element: JsonElement): String =
    when (element) {
      is JsonObject ->
        element.entries.sortedBy { entry -> entry.key }.joinToString(separator = ",", prefix = "{", postfix = "}") {
          "${JsonPrimitive(it.key)}:${encode(it.value)}"
        }
      is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { value -> encode(value) }
      is JsonPrimitive -> element.toString()
    }
}
