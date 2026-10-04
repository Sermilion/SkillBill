package skillbill.mcp.shared

import skillbill.contracts.JsonCodec

internal class McpToolArguments(
  private val toolName: String,
  private val values: Map<String, Any?>,
) {
  fun string(name: String): String = optionalString(name) ?: invalid(name, "is required")

  fun optionalString(name: String): String? =
    when (val value = values[name]) {
      null -> null
      is String -> value
      else -> invalid(name, "must be a string")
    }

  fun boolean(name: String): Boolean =
    when (val value = values[name]) {
      null -> false
      is Boolean -> value
      else -> invalid(name, "must be a boolean")
    }

  fun int(
    name: String,
    default: Int,
  ): Int =
    when (val value = values[name]) {
      null -> default
      is Int -> value
      is Long -> value.toInt()
      is Double -> value.toInt()
      else -> invalid(name, "must be an integer")
    }

  fun optionalInt(name: String): Int? =
    when (val value = values[name]) {
      null -> null
      is Int -> value
      is Long -> {
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
          invalid(name, "must fit in a 32-bit integer")
        }
        value.toInt()
      }
      is Double -> {
        if (value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
          invalid(name, "must be an integer")
        }
        value.toInt()
      }
      else -> invalid(name, "must be an integer")
    }

  fun stringList(name: String): List<String> =
    when (val value = values[name]) {
      null -> emptyList()
      is List<*> -> value.map { it as? String ?: invalid(name, "must contain strings") }
      else -> invalid(name, "must be an array")
    }

  fun map(name: String): Map<String, Any?> = optionalMap(name).orEmpty()

  fun optionalMap(name: String): Map<String, Any?>? =
    when {
      !values.containsKey(name) -> null
      else -> JsonCodec.anyToStringAnyMap(values[name]) ?: invalid(name, "must be an object")
    }

  fun optionalListMap(name: String): List<Map<String, Any?>>? =
    when (val value = values[name]) {
      null -> null
      is List<*> ->
        value.map { JsonCodec.anyToStringAnyMap(it) ?: invalid(name, "must contain objects") }
      else -> invalid(name, "must be an array")
    }

  fun invalid(
    name: String,
    detail: String,
  ): Nothing = throw InvalidMcpToolArgumentError(toolName, name, detail)
}
