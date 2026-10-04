package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import skillbill.contracts.JsonCodec
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import java.math.BigDecimal
import java.math.BigInteger

internal fun Map<String, Any?>.nonNegativeLongOrDefault(key: String): Long =
  when (val value = this[key]) {
    null -> 0L
    is BigInteger -> runCatching { value.longValueExact() }.getOrNull()
    is BigDecimal -> runCatching { value.toBigIntegerExact().longValueExact() }.getOrNull()
    is Number -> value.toLong()
    else -> null
  }?.takeIf { it >= 0 }
    ?: goalRunnerControlSchemaError("field '$key' must be a non-negative integer.")

internal fun Any?.exactPositiveSubtaskIdOrNull(): Int? = (this as? Number)?.asExactIntOrNull()?.takeIf { it > 0 }

internal const val LEGACY_UNKNOWN_PAUSED_AT: String = "1970-01-01T00:00:00Z"

internal fun legacyPausedAt(state: Map<String, Any?>): String? {
  if (!state.booleanOrDefault("paused", false)) return null
  val lease = state["execution_lease"]?.let(JsonCodec::anyToStringAnyMap)
  return lease?.nullableString("heartbeat_at") ?: LEGACY_UNKNOWN_PAUSED_AT
}

internal fun decodeExecutionLease(raw: Any?): GoalRunnerExecutionLease {
  val lease =
    JsonCodec.anyToStringAnyMap(raw)
      ?: goalRunnerControlSchemaError("execution lease must be an object or null.")
  val allowedKeys =
    setOf(
      "generation",
      "owner_token",
      "host_identity",
      "boot_identity",
      "pid",
      "process_birth_token",
      "heartbeat_at",
      "expires_at",
    )
  lease.keys.forEach { key ->
    if (key !in allowedKeys) {
      goalRunnerControlSchemaError("execution lease has unsupported field '$key'.")
    }
  }
  return GoalRunnerExecutionLease(
    generation = lease["generation"].toPositiveLong("generation"),
    ownerToken = lease.requiredString("owner_token"),
    hostIdentity = lease.requiredString("host_identity"),
    bootIdentity = lease.requiredString("boot_identity"),
    pid = lease["pid"].toPositiveLong("pid"),
    processBirthToken = lease.requiredString("process_birth_token"),
    heartbeatAt = lease.requiredString("heartbeat_at"),
    expiresAt = lease.requiredString("expires_at"),
  )
}

internal fun Map<String, Any?>.booleanOrDefault(
  key: String,
  default: Boolean,
): Boolean {
  if (!containsKey(key)) return default
  return when (val value = this[key]) {
    null -> goalRunnerControlSchemaError("field '$key' must be a boolean.")
    is Boolean -> value
    else -> goalRunnerControlSchemaError("field '$key' must be a boolean.")
  }
}

internal fun Map<String, Any?>.nullableString(key: String): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value
    else -> goalRunnerControlSchemaError("field '$key' must be a string or null.")
  }

internal fun Map<String, Any?>.requiredString(key: String): String =
  when (val value = this[key]) {
    is String ->
      value.takeIf(String::isNotBlank)
        ?: goalRunnerControlSchemaError("execution lease field '$key' must not be blank.")
    else -> goalRunnerControlSchemaError("execution lease field '$key' must be a nonblank string.")
  }

internal fun Any?.toPositiveLong(key: String): Long =
  when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    is BigInteger -> runCatching { longValueExact() }.getOrNull()
    is BigDecimal -> runCatching { toBigIntegerExact().longValueExact() }.getOrNull()
    is Number -> runCatching { BigDecimal(toString()).longValueExact() }.getOrNull()
    else -> null
  }?.also {
    if (it <= 0) goalRunnerControlSchemaError("execution lease field '$key' must be positive.")
  } ?: goalRunnerControlSchemaError("execution lease field '$key' must be a positive integer.")

internal fun Any?.toPositiveIntOrNull(key: String): Int? =
  when (this) {
    null -> null
    is Byte -> toInt()
    is Short -> toInt()
    is Int -> this
    is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    is BigInteger -> runCatching { intValueExact() }.getOrNull()
    is BigDecimal -> runCatching { toBigIntegerExact().intValueExact() }.getOrNull()
    is Number -> runCatching { BigDecimal(toString()).intValueExact() }.getOrNull()
    else -> goalRunnerControlSchemaError("field '$key' must be a positive integer or null.")
  }?.also {
    if (it <= 0) goalRunnerControlSchemaError("field '$key' must be positive.")
  } ?: if (this == null) {
    null
  } else {
    goalRunnerControlSchemaError("field '$key' must be a positive integer or null.")
  }

internal fun Map<String, Any?>.intIntMap(key: String): Map<Int, Int> {
  val raw = this[key] ?: return emptyMap()
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: goalRunnerControlSchemaError("field '$key' must be an object.")
  return map.entries.associate { (entryKey, value) ->
    val subtaskId =
      entryKey.toIntOrNull()
        ?: goalRunnerControlSchemaError("field '$key' keys must be integer strings.")
    val retries =
      when (value) {
        is Number -> value.toInt()
        else -> goalRunnerControlSchemaError("field '$key' values must be integers.")
      }
    subtaskId to retries
  }
}

internal fun Map<String, Any?>.intStringMap(key: String): Map<Int, String> {
  val raw = this[key] ?: return emptyMap()
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: goalRunnerControlSchemaError("field '$key' must be an object.")
  return map.entries.associate { (entryKey, value) ->
    val subtaskId =
      entryKey.toIntOrNull()
        ?: goalRunnerControlSchemaError("field '$key' keys must be integer strings.")
    val text =
      value as? String
        ?: goalRunnerControlSchemaError("field '$key' values must be strings.")
    subtaskId to text
  }
}
