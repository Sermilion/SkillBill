package skillbill.application.scaffold

import skillbill.error.shellcontent.invalidScaffoldPayloadError

internal fun parseStringList(
  payload: Map<String, Any?>,
  key: String,
): List<String> {
  val raw =
    payload[key]
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field '$key' must be a list of strings.",
      )
  if (raw !is List<*>) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, key)
}

internal fun parseStringListOrEmpty(
  payload: Map<String, Any?>,
  key: String,
): List<String> {
  val raw = payload[key] ?: return emptyList()
  if (raw !is List<*>) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, key)
}

internal fun parseRoutingSignalList(
  routing: Map<*, *>,
  key: String,
  fieldName: String,
): List<String>? {
  if (!routing.containsKey(key)) return null
  val raw =
    routing[key]
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field '$fieldName' must be a list of strings.",
      )
  if (raw !is List<*>) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, fieldName)
}

internal fun parseStringListValue(
  raw: List<*>,
  fieldName: String,
): List<String> {
  val mapped =
    raw.map { value ->
      value as? String
        ?: throw invalidScaffoldPayloadError(
          "Scaffold payload field '$fieldName' must contain only non-empty strings.",
        )
    }
  if (mapped.any(String::isBlank)) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must contain only non-empty strings.",
    )
  }
  return mapped
}

internal fun parseBooleanOrFalse(
  payload: Map<String, Any?>,
  key: String,
): Boolean {
  if (!payload.containsKey(key)) return false
  return payload[key] as? Boolean
    ?: throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a boolean when provided.",
    )
}

internal fun requireOptionalNonBlank(
  payload: Map<String, Any?>,
  key: String,
): String? {
  if (!payload.containsKey(key)) return null
  val value =
    payload[key] as? String
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field '$key' must be a non-empty string when provided.",
      )
  if (value.isBlank()) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string when provided.",
    )
  }
  return value
}

internal fun requireStringInMap(
  layer: Map<*, *>,
  fieldLabel: String,
  key: String,
): String {
  val value =
    layer[key] as? String
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field '$fieldLabel' must be a non-empty string.",
      )
  if (value.isBlank()) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field '$fieldLabel' must be a non-empty string.",
    )
  }
  return value
}
