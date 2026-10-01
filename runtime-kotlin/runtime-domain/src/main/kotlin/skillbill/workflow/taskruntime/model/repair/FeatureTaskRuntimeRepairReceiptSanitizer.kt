package skillbill.workflow.taskruntime.model.repair

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError

internal const val REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES: Int = 256
internal const val REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES: Int = 128

private val COMPACT_SYMBOL = Regex("^[A-Za-z_][A-Za-z0-9_$-]*(?:\\.[A-Za-z_][A-Za-z0-9_$-]*)?$")
private val COMPACT_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_$-]*$")
private val FILE_BASENAME = Regex("^[A-Za-z0-9_.-]+$")
private val LINE_NUMBER =
  Regex(
    "(?:\\b(?:lines?|ln)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:(?:\\bL|#)\\s*\\d+(?:\\s*[-–]\\s*(?:L|#)?\\s*\\d+)?)|" +
      "(?:\\b(?:columns?|cols?)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?::\\s*\\d+(?::\\s*\\d+)?(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:[\\(\\[\\{]\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*[\\)\\]\\}])",
    RegexOption.IGNORE_CASE,
  )
private val SOURCE_BODY =
  Regex(
    "(?:^|\\.\\s+)\\s*(?:val|var)\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*=|" +
      "(?:^|\\.\\s+)\\s*return\\b",
  )
private val DIFF_HUNK = Regex("@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )")
private val SERIALIZED_PAYLOAD = Regex("\\{\\s*\"|\"\\s*:\\s*[\\[{\"]")
private const val CODE_FENCE: String = "```"

internal fun requireReceiptSymbol(
  value: String,
  field: String,
) {
  requireReceiptSanitizedText(value, field, REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES)
  if (value.contains('/') || value.contains('\\') || !COMPACT_SYMBOL.matches(value)) {
    receiptError(field, "must be a compact symbol (Type or Type.member), never a repository path.")
  }
}

fun salvageCompactReceiptSymbol(raw: String): String? {
  val trimmed = raw.trim()
  if (COMPACT_SYMBOL.matches(trimmed) || trimmed.contains('/') || trimmed.contains('\\')) {
    return null
  }
  val typePart = trimmed.substringBefore('.')
  if (!COMPACT_IDENTIFIER.matches(typePart) || !trimmed.contains('.')) {
    return null
  }
  val afterDot = trimmed.substringAfter('.')
  if (afterDot.isEmpty()) {
    return null
  }
  val candidate =
    when {
      afterDot.any(Char::isWhitespace) -> typePart
      else -> {
        val memberToken = COMPACT_IDENTIFIER.find(afterDot)?.value
        if (memberToken != null) "$typePart.$memberToken" else typePart
      }
    }
  return candidate.takeIf(COMPACT_SYMBOL::matches)
}

internal fun requireReceiptFileBasename(
  value: String,
  field: String,
) {
  requireUtf8Budget(value, field, REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES)
  if (value.contains('/') || value.contains('\\') || !FILE_BASENAME.matches(value)) {
    receiptError(field, "must be a file basename, never a repository path.")
  }
}

internal fun requireReceiptIdentityText(
  value: String,
  field: String,
  maxUtf8Bytes: Int,
) {
  utf8BudgetViolation(value, maxUtf8Bytes)?.let { reason -> receiptError(field, reason) }
}

internal fun requireReceiptSanitizedText(
  value: String,
  field: String,
  maxUtf8Bytes: Int,
) {
  sanitizedTextViolation(value, maxUtf8Bytes)?.let { reason -> receiptError(field, reason) }
}

internal fun sanitizedTextViolation(
  value: String,
  maxUtf8Bytes: Int,
): String? =
  when {
    value.isBlank() -> "must be a non-blank string."
    value.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes -> "allows at most $maxUtf8Bytes UTF-8 bytes."
    value.any(Char::isISOControl) || value.contains('\n') || value.contains('\r') ->
      "must be a single line with no line break or control character."
    value.contains(CODE_FENCE) -> "must not contain a code fence."
    LINE_NUMBER.containsMatchIn(value) -> "must not contain a line number."
    DIFF_HUNK.containsMatchIn(value) ||
      SERIALIZED_PAYLOAD.containsMatchIn(value) ||
      SOURCE_BODY.containsMatchIn(value) -> "must not contain a diff hunk, serialized payload, or source body."
    else -> null
  }

internal fun utf8BudgetViolation(
  value: String,
  maxUtf8Bytes: Int,
): String? =
  when {
    value.isBlank() -> "must be a non-blank string."
    value.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes -> "allows at most $maxUtf8Bytes UTF-8 bytes."
    else -> null
  }

private fun requireUtf8Budget(
  value: String,
  field: String,
  maxUtf8Bytes: Int,
) {
  val bytes = value.toByteArray(Charsets.UTF_8).size
  if (bytes > maxUtf8Bytes) {
    receiptError(field, "allows at most $maxUtf8Bytes UTF-8 bytes.")
  }
}

internal fun receiptError(
  fieldPath: String,
  payloadFreeReason: String,
): Nothing =
  throw InvalidFeatureTaskRuntimeRepairReceiptError(
    fieldPath = fieldPath,
    reason = payloadFreeReason,
    payloadFreeReason = payloadFreeReason,
  )
