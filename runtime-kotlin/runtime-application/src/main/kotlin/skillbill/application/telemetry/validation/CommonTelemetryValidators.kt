package skillbill.application.telemetry.validation

val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")

internal fun validateEnum(
  value: String,
  allowed: List<String>,
  fieldName: String,
): String? = if (value in allowed) null else "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString(", ")}"

internal fun validateNonBlank(
  value: String,
  fieldName: String,
): String? = if (value.isBlank()) "$fieldName must be non-empty." else null

internal fun validatePositive(
  value: Int,
  fieldName: String,
): String? = if (value > 0) null else "$fieldName must be greater than 0."
