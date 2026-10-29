package skillbill.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import skillbill.error.core.JsonWrongRootTypeError
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.UnsupportedJsonValueError
import java.math.BigDecimal
import java.math.BigInteger

object JsonCodec {
  val json: Json =
    Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

  fun parseObjectOrNull(rawValue: String): JsonObject? =
    try {
      parseJsonElementStrict(rawValue) as? JsonObject
    } catch (_: MalformedJsonTextError) {
      null
    }

  fun duplicateObjectKeyPath(rawValue: String): String? = JsonDuplicateKeyScanner(rawValue).firstDuplicatePath()

  fun parseJsonArrayStrict(rawValue: String): List<Any?> {
    val parsed = parseJsonElementStrict(rawValue)
    if (parsed !is JsonArray) {
      throw JsonWrongRootTypeError("a JSON array")
    }
    return parsed.map(::jsonElementToValue)
  }

  fun jsonElementToValue(element: JsonElement): Any? =
    when (element) {
      JsonNull -> null
      is JsonObject -> element.entries.associate { (key, value) -> key to jsonElementToValue(value) }
      is JsonArray -> element.map(::jsonElementToValue)
      is JsonPrimitive -> jsonPrimitiveToValue(element)
    }

  fun machineConfigJsonElementToValue(element: JsonElement): Any? =
    configJsonElementToValue(element, JsonExponentCompatibility.MACHINE_CONFIG)

  fun telemetryConfigJsonElementToValue(element: JsonElement): Any? =
    configJsonElementToValue(element, JsonExponentCompatibility.TELEMETRY_CONFIG)

  fun anyToStringAnyMap(value: Any?): Map<String, Any?>? {
    val entries = (value as? Map<*, *>)?.entries ?: return null
    val converted = LinkedHashMap<String, Any?>()
    entries.forEach { (entryKey, entryValue) ->
      if (entryKey !is String) {
        throw UnsupportedJsonValueError("JSON map keys must be strings")
      }
      converted[entryKey] = entryValue
    }
    return converted
  }

  fun anyToStringList(value: Any?): List<String>? =
    when (value) {
      null -> null
      is List<*> -> value.map { entry -> entry as? String ?: return null }
      else -> null
    }

  fun anyToStringAnyMapList(value: Any?): List<Map<String, Any?>>? =
    when (value) {
      null -> null
      is List<*> ->
        value.map { entry ->
          anyToStringAnyMap(entry) ?: return null
        }
      else -> null
    }

  fun mapToJsonString(map: Map<String, Any?>): String =
    json.encodeToString(JsonObject.serializer(), mapToJsonObject(map))

  fun mapToJsonObject(map: Map<String, Any?>): JsonObject =
    buildJsonObject {
      map.forEach { (key, value) ->
        put(key, valueToJsonElement(value))
      }
    }

  @OptIn(ExperimentalSerializationApi::class)
  fun valueToJsonElement(value: Any?): JsonElement =
    if (value is LosslessJsonNumberLiteral) {
      JsonUnquotedLiteral(value.sourceToken)
    } else {
      jsonPrimitiveElement(value)
        ?: collectionJsonElement(value)
        ?: throw UnsupportedJsonValueError(
          "JSON value type ${value?.let { it::class.simpleName } ?: "null"} is not supported",
        )
    }

  fun parseValue(rawValue: String): Any? = jsonElementToValue(parseJsonElementStrict(rawValue))

  fun valueToJsonString(value: Any?): String = json.encodeToString(JsonElement.serializer(), valueToJsonElement(value))
}

private fun JsonCodec.parseJsonElementStrict(rawValue: String): JsonElement =
  try {
    json.parseToJsonElement(rawValue)
  } catch (error: SerializationException) {
    throw MalformedJsonTextError(error)
  }

private fun configJsonElementToValue(
  element: JsonElement,
  compatibility: JsonExponentCompatibility,
): Any? =
  when (element) {
    JsonNull -> null
    is JsonObject -> element.entries.associate { (key, value) -> key to configJsonElementToValue(value, compatibility) }
    is JsonArray -> element.map { value -> configJsonElementToValue(value, compatibility) }
    is JsonPrimitive ->
      LosslessJsonNumberLiteral.fromValidatedJsonPrimitive(element, compatibility) ?: jsonPrimitiveToValue(element)
  }

private enum class JsonExponentCompatibility {
  MACHINE_CONFIG,
  TELEMETRY_CONFIG,
}

private class LosslessJsonNumberLiteral private constructor(
  val sourceToken: String,
  private val compatibility: JsonExponentCompatibility,
) : Number() {
  private val numericValue: Number by lazy {
    when (compatibility) {
      JsonExponentCompatibility.MACHINE_CONFIG -> decimalOrBoundedDouble(sourceToken)
      JsonExponentCompatibility.TELEMETRY_CONFIG -> telemetryNumber(sourceToken)
    }
  }

  override fun toByte(): Byte =
    when (val number = numericValue) {
      is BigDecimal -> number.integerLowBits(Byte.SIZE_BITS).toByte()
      else -> number.toByte()
    }

  override fun toDouble(): Double = numericValue.toDouble()

  override fun toFloat(): Float = numericValue.toFloat()

  override fun toInt(): Int =
    when (val number = numericValue) {
      is BigDecimal -> number.integerLowBits(Int.SIZE_BITS).toInt()
      else -> number.toInt()
    }

  override fun toLong(): Long =
    when (val number = numericValue) {
      is BigDecimal -> number.integerLowBits(Long.SIZE_BITS)
      else -> number.toLong()
    }

  override fun toShort(): Short =
    when (val number = numericValue) {
      is BigDecimal -> number.integerLowBits(Short.SIZE_BITS).toShort()
      else -> number.toShort()
    }

  override fun equals(other: Any?): Boolean =
    other is LosslessJsonNumberLiteral &&
      sourceToken == other.sourceToken &&
      compatibility == other.compatibility

  override fun hashCode(): Int = 31 * sourceToken.hashCode() + compatibility.hashCode()

  override fun toString(): String = identityText(sourceToken)

  companion object {
    fun fromValidatedJsonPrimitive(
      primitive: JsonPrimitive,
      compatibility: JsonExponentCompatibility,
    ): LosslessJsonNumberLiteral? {
      if (primitive.isString || primitive.booleanOrNull != null) return null
      val token = primitive.content
      if (!token.contains('e', ignoreCase = true) && !token.isNegativeZeroToken()) return null
      return LosslessJsonNumberLiteral(token, compatibility)
    }
  }
}

private fun identityText(sourceToken: String): String {
  if (sourceToken.isNegativeZeroToken() || !sourceToken.contains('e', ignoreCase = true)) return sourceToken
  val authoredValue = sourceToken.toBigDecimalOrNull() ?: return sourceToken
  val telemetryText = telemetryNumber(sourceToken).toString()
  val telemetryValue = telemetryText.toBigDecimalOrNull() ?: return sourceToken
  return if (telemetryValue.compareTo(authoredValue) == 0) telemetryText else sourceToken
}

private fun String.isNegativeZeroToken(): Boolean {
  if (!startsWith('-')) return false
  val significand = substring(1).substringBefore('e').substringBefore('E')
  return significand.replace(".", "").all { it == '0' }
}

private fun decimalOrBoundedDouble(token: String): Number =
  try {
    BigDecimal(token)
  } catch (_: NumberFormatException) {
    token.toDoubleOrNull() ?: Double.NaN
  }

private fun telemetryNumber(token: String): Number =
  if (!token.contains('.')) {
    token.toDoubleOrNull() ?: Double.NaN
  } else {
    try {
      BigDecimal(token)
    } catch (_: NumberFormatException) {
      token.toDoubleOrNull() ?: Double.NaN
    }
  }

private fun BigDecimal.integerLowBits(bitCount: Int): Long {
  val modulus = BigInteger.ONE.shiftLeft(bitCount)
  val unscaled = unscaledValue()
  val integerResidue =
    if (scale() <= 0) {
      val exponent = -scale().toLong()
      unscaled.mod(modulus)
        .multiply(BigInteger.TEN.modPow(BigInteger.valueOf(exponent), modulus))
        .mod(modulus)
    } else {
      val digits = unscaled.abs().toString()
      val digitCount = digits.length
      if (scale() >= digitCount) {
        BigInteger.ZERO
      } else {
        val wholeDigits = digitCount - scale()
        val whole = BigInteger(digits.substring(0, wholeDigits))
        (if (unscaled.signum() < 0) whole.negate() else whole).mod(modulus)
      }
    }
  val signBit = integerResidue.testBit(bitCount - 1)
  return if (signBit) integerResidue.subtract(modulus).toLong() else integerResidue.toLong()
}

private fun jsonPrimitiveToValue(primitive: JsonPrimitive): Any? =
  if (primitive.isString) {
    primitive.contentOrNull
  } else {
    primitive.booleanOrNull
      ?: primitive.intOrNull
      ?: primitive.longOrNull
      ?: decodeIntegralPrimitive(primitive)
      ?: decodeDecimalPrimitive(primitive)
      ?: primitive.doubleOrNull
      ?: primitive.contentOrNull
  }

private fun decodeDecimalPrimitive(primitive: JsonPrimitive): BigDecimal? {
  val content = primitive.contentOrNull ?: return null
  if (!content.contains('.')) {
    return null
  }
  return runCatching { BigDecimal(content) }.getOrNull()
}

private fun decodeIntegralPrimitive(primitive: JsonPrimitive): Any? {
  val content = primitive.contentOrNull ?: return null
  if (content.contains('.') || content.contains('e', ignoreCase = true)) {
    return null
  }
  return runCatching { BigInteger(content) }.getOrNull()?.let { integral ->
    if (integral >= BigInteger.valueOf(Long.MIN_VALUE) &&
      integral <= BigInteger.valueOf(Long.MAX_VALUE)
    ) {
      integral.longValueExact()
    } else {
      integral
    }
  }
}

@OptIn(ExperimentalSerializationApi::class)
private fun jsonPrimitiveElement(value: Any?): JsonElement? =
  when {
    value == null -> JsonNull
    value is JsonElement -> value
    value is String -> JsonPrimitive(value)
    value is Boolean -> JsonPrimitive(value)
    value is Int -> JsonPrimitive(value)
    value is Long -> JsonPrimitive(value)
    value is Number -> numberJsonElement(value)
    else -> null
  }

@OptIn(ExperimentalSerializationApi::class)
private fun numberJsonElement(value: Number): JsonElement =
  when (value) {
    is BigInteger -> JsonUnquotedLiteral(value.toString())
    is BigDecimal -> JsonUnquotedLiteral(value.toPlainString())
    is Float -> finiteJsonPrimitive(value)
    is Double -> finiteJsonPrimitive(value)
    else -> finiteJsonPrimitive(value.toDouble())
  }

private fun finiteJsonPrimitive(value: Number): JsonPrimitive {
  if (!value.toDouble().isFinite()) {
    throw UnsupportedJsonValueError("JSON number must be finite")
  }
  return JsonPrimitive(value)
}

private fun JsonCodec.collectionJsonElement(value: Any?): JsonElement? =
  mapJsonElement(value) ?: iterableJsonElement(value) ?: arrayJsonElement(value)

private fun JsonCodec.mapJsonElement(value: Any?): JsonElement? =
  (value as? Map<*, *>)?.let { entries ->
    buildJsonObject {
      entries.forEach { (entryKey, entryValue) ->
        if (entryKey !is String) {
          throw UnsupportedJsonValueError("JSON map keys must be strings")
        }
        put(entryKey, valueToJsonElement(entryValue))
      }
    }
  }

private fun JsonCodec.iterableJsonElement(value: Any?): JsonElement? =
  (value as? Iterable<*>)?.let { entries ->
    buildJsonArray {
      entries.forEach { add(valueToJsonElement(it)) }
    }
  }

private fun JsonCodec.arrayJsonElement(value: Any?): JsonElement? =
  (value as? Array<*>)?.let { entries ->
    buildJsonArray {
      entries.forEach { add(valueToJsonElement(it)) }
    }
  }

private class JsonDuplicateKeyScanner(private val text: String) {
  private sealed interface Frame {
    val path: String

    class Object(override val path: String) : Frame {
      val keys = HashSet<String>()
      var pendingKey: String? = null
      var expectingKey = true
    }

    class Array(override val path: String) : Frame {
      var index = 0
    }
  }

  private val frames = ArrayDeque<Frame>()
  private var position = 0

  fun firstDuplicatePath(): String? {
    while (position < text.length) {
      when (val char = text[position]) {
        '{' -> {
          frames.addLast(Frame.Object(childPath()))
          position++
        }
        '[' -> {
          frames.addLast(Frame.Array(childPath()))
          position++
        }
        '}', ']' -> {
          frames.removeLastOrNull()
          position++
        }
        ',' -> {
          advanceFrame()
          position++
        }
        '"' -> {
          duplicateKey()?.let { return it }
        }
        else -> {
          if (char == ':') (frames.lastOrNull() as? Frame.Object)?.expectingKey = false
          position++
        }
      }
    }
    return null
  }

  private fun advanceFrame() {
    when (val frame = frames.lastOrNull()) {
      is Frame.Object -> frame.expectingKey = true
      is Frame.Array -> frame.index++
      null -> Unit
    }
  }

  private fun duplicateKey(): String? {
    val value = readString()
    val frame = frames.lastOrNull()
    if (frame !is Frame.Object || !frame.expectingKey) return null
    frame.expectingKey = false
    frame.pendingKey = value
    return if (frame.keys.add(value)) null else joinPath(frame.path, value)
  }

  private fun childPath(): String =
    when (val frame = frames.lastOrNull()) {
      null -> ""
      is Frame.Object -> joinPath(frame.path, frame.pendingKey.orEmpty())
      is Frame.Array -> "${frame.path}[${frame.index}]"
    }

  private fun joinPath(
    parent: String,
    key: String,
  ): String = if (parent.isEmpty()) key else "$parent.$key"

  private fun readString(): String {
    val builder = StringBuilder()
    position++
    while (position < text.length) {
      val char = text[position]
      when {
        char == '"' -> {
          position++
          return builder.toString()
        }
        char == '\\' && position + 1 < text.length -> {
          val escaped = text[position + 1]
          if (escaped == 'u' && position + UNICODE_ESCAPE_LAST_OFFSET < text.length) {
            builder.append(
              text.substring(
                position + UNICODE_ESCAPE_PREFIX_LENGTH,
                position + UNICODE_ESCAPE_LENGTH,
              ).toInt(HEX_RADIX).toChar(),
            )
            position += UNICODE_ESCAPE_LENGTH
          } else {
            builder.append(
              when (escaped) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                else -> escaped
              },
            )
            position += 2
          }
        }
        else -> {
          builder.append(char)
          position++
        }
      }
    }
    return builder.toString()
  }
}

private const val UNICODE_ESCAPE_PREFIX_LENGTH = 2
private const val UNICODE_ESCAPE_LENGTH = 6
private const val UNICODE_ESCAPE_LAST_OFFSET = UNICODE_ESCAPE_LENGTH - 1
private const val HEX_RADIX = 16
