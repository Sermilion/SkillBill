package skillbill.workflow.model.persistence.artifact

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import java.math.BigDecimal
import java.math.BigInteger

internal class DurableArtifactMapReader(
  private val map: Map<String, Any?>,
  private val fail: (message: String) -> Nothing,
) {
  fun requiredString(key: String): String {
    val value =
      map[key]
        ?: fail("Feature-task-runtime artifact map is missing required field '$key'.")
    return (value as? String)?.takeIf(String::isNotBlank)
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a non-blank string.")
  }

  fun optionalString(key: String): String? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return (map[key] as? String)?.takeIf(String::isNotBlank)
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a non-blank string when present.")
  }

  fun requiredInt(key: String): Int {
    if (!map.containsKey(key) || map[key] == null) {
      fail("Feature-task-runtime artifact map is missing required integer field '$key'.")
    }
    return map[key].asExactIntOrNull()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to an integer.")
  }

  fun optionalInt(key: String): Int? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return map[key].asExactIntOrNull()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to an integer when present.")
  }

  fun optionalLong(key: String): Long? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return map[key].asExactLongOrNull()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a long integer when present.")
  }

  fun requiredLong(key: String): Long {
    if (!map.containsKey(key) || map[key] == null) {
      fail("Feature-task-runtime artifact map is missing required long field '$key'.")
    }
    return map[key].asExactLongOrNull()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a long integer.")
  }

  fun optionalBoolean(key: String): Boolean? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return map[key] as? Boolean
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a boolean when present.")
  }

  fun requiredBoolean(key: String): Boolean =
    optionalBoolean(key)
      ?: fail("Feature-task-runtime artifact map is missing required boolean field '$key'.")

  fun optionalStringList(key: String): List<String> {
    if (!map.containsKey(key) || map[key] == null) {
      return emptyList()
    }
    val list =
      map[key] as? List<*>
        ?: fail("Feature-task-runtime artifact field '$key' must decode to a list of strings when present.")
    return list.map { element ->
      (element as? String)?.takeIf(String::isNotBlank)
        ?: fail("Feature-task-runtime artifact field '$key' must contain only non-blank strings.")
    }
  }

  fun requiredStringList(key: String): List<String> {
    val value =
      map[key]
        ?: fail("Feature-task-runtime artifact map is missing required list field '$key'.")
    val list =
      value as? List<*>
        ?: fail("Feature-task-runtime artifact field '$key' must decode to a list.")
    return list.mapIndexed { index, element ->
      (element as? String)?.takeIf(String::isNotBlank)
        ?: fail("Feature-task-runtime artifact field '$key[$index]' must be non-blank.")
    }
  }

  fun requiredList(key: String): List<*> {
    val value =
      map[key]
        ?: fail("Feature-task-runtime artifact map is missing required list field '$key'.")
    return value as? List<*>
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a list.")
  }

  fun optionalList(key: String): List<*>? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return map[key] as? List<*>
      ?: fail("Feature-task-runtime artifact field '$key' must decode to a list when present.")
  }

  fun requiredNestedObject(key: String): Map<String, Any?> {
    val value =
      map[key]
        ?: fail("Feature-task-runtime artifact map is missing required object field '$key'.")
    return value.asStringKeyMap()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to an object.")
  }

  fun optionalNestedObject(key: String): Map<String, Any?>? {
    if (!map.containsKey(key) || map[key] == null) {
      return null
    }
    return map[key].asStringKeyMap()
      ?: fail("Feature-task-runtime artifact field '$key' must decode to an object when present.")
  }

  fun historicalPhaseOutput(): PhaseOutput {
    val produced = map[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>
    return PhaseOutput(
      value = produced?.get(SharedPayloadKeys.VALUE) as? String ?: "",
      prompt = (produced?.get(SharedPayloadKeys.PROMPT) as? String)?.takeIf(String::isNotBlank),
    )
  }

  fun nestedObjectFromValue(
    value: Any?,
    fieldPath: String,
  ): Map<String, Any?> =
    value.asStringKeyMap()
      ?: fail("Feature-task-runtime artifact field '$fieldPath' must decode to an object.")
}

internal fun durableArtifactMapReader(map: Map<String, Any?>): DurableArtifactMapReader =
  DurableArtifactMapReader(map) { message -> throw InvalidWorkflowStateSchemaError(message) }

internal fun Any?.toStringKeyedArtifactMap(fail: (String) -> Nothing): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: fail("Artifact map keys must be strings.")
    stringKey to value
  } ?: fail("Artifact value must decode to an object.")

fun Any?.asExactIntOrNull(): Int? =
  asExactLongOrNull()?.let { value ->
    if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
  }

fun Any?.asExactLongOrNull(): Long? =
  when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    is BigInteger -> runCatching { longValueExact() }.getOrNull()
    is BigDecimal -> runCatching { longValueExact() }.getOrNull()
    is Double -> runCatching { BigDecimal(toString()).longValueExact() }.getOrNull()
    is Float -> runCatching { BigDecimal(toString()).longValueExact() }.getOrNull()
    is String -> toLongOrNull()
    else -> null
  }

private fun Any?.asStringKeyMap(): Map<String, Any?>? =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: return null
    stringKey to value
  }
