package skillbill.contracts.scaffold.wire

import skillbill.error.shellcontent.invalidScaffoldPayloadError

fun requireString(
  map: Map<String, Any?>,
  key: String,
): String {
  val value =
    map[key] as? String
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field '$key' must be a non-empty string.",
      )
  if (value.isBlank()) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string.",
    )
  }
  return value
}

fun requireStringOrDefault(
  map: Map<String, Any?>,
  key: String,
  default: String,
): String {
  if (!map.containsKey(key)) {
    return default
  }
  return when (val value = map[key]) {
    null -> default
    is String -> value.takeIf { it.isNotBlank() } ?: default
    else -> throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' has wrong type " +
        "(expected String, got ${value::class.simpleName}).",
    )
  }
}

fun optionalString(
  map: Map<String, Any?>,
  key: String,
): String? {
  if (!map.containsKey(key)) {
    return null
  }
  return when (val value = map[key]) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
    else -> throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' has wrong type " +
        "(expected String, got ${value::class.simpleName}).",
    )
  }
}

fun optionalList(
  map: Map<String, Any?>,
  key: String,
): List<*>? {
  val raw = map[key] ?: return null
  if (raw !is List<*>) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list when provided " +
        "(got ${raw::class.simpleName}).",
    )
  }
  return raw
}
