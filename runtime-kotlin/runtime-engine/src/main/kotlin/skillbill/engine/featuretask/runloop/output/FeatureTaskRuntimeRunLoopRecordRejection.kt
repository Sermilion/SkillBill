package skillbill.engine.featuretask.runloop.output

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runner.SCHEMA_GATE_DETAIL_MAX_CHARS
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail

internal fun rejectionPath(detail: String): String {
  Regex("""(?:instance location|path|pointer)\s*[:=]\s*['"]?(/[^\s,'"]*)""", RegexOption.IGNORE_CASE)
    .find(detail)
    ?.groupValues
    ?.get(1)
    ?.let { return it }
  val dollarPath = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""").find(detail)?.value ?: return "/"
  return dollarPath.removePrefix("$")
    .replace(Regex("""\.([A-Za-z0-9_-]+)"""), "/${'$'}1")
    .replace(Regex("""\[([0-9]+)]"""), "/${'$'}1")
}

internal fun payloadFreeRejectionReason(
  rule: String,
  path: String,
): String = "Rejected output violated '$rule' at '$path'. Inspect the private diagnostic for the exact response."

internal fun retryRejectionReason(
  payloadFreeReason: String,
  validationReason: String?,
): String =
  if (validationReason.isNullOrBlank()) {
    payloadFreeReason
  } else {
    "$payloadFreeReason Violated constraint: ${boundedSchemaGateDetail(validationReason)}"
  }

internal fun payloadFreeSemanticGateConstraint(
  rule: String,
  detail: String,
  rejectedOutput: Map<
    String,
    Any?,
  >,
): String? =
  when (rule) {
    "repair-receipt" -> detail.takeUnless { it.isBlank() }
    "validation-result" -> {
      val produced = JsonCodec.anyToStringAnyMap(rejectedOutput[SharedPayloadKeys.PRODUCED_OUTPUTS])
      val failureDetails = produced?.get(SharedPayloadKeys.VALUE) as? String
      listOfNotNull(detail, failureDetails).joinToString("\n").take(SCHEMA_GATE_DETAIL_MAX_CHARS)
    }
    "producer-projection",
    "consumer-projection",
    "output-verification",
    -> scrubResponseDerivedGateDetail(detail, rejectedOutput)
    else -> scrubBoundedReferenceGateConstraint(detail)
  }

internal fun scrubBoundedReferenceGateConstraint(detail: String): String? {
  if (detail.isBlank()) return null
  val namesArtifactRef = detail.contains("artifact_ref")
  val namesCheckRef = detail.contains("check_ref")
  if (!namesArtifactRef && !namesCheckRef) return null
  val cap = BOUNDED_REF_LENGTH_CAP_PATTERN.find(detail)?.groupValues?.get(1)?.replace(",", "")
  return when {
    namesArtifactRef && cap != null -> "artifact_ref allows at most $cap characters."
    namesCheckRef && cap != null -> "check_ref allows at most $cap characters."
    namesArtifactRef ->
      "artifact_ref must be a bounded path or symbol reference such as " +
        "src/main/Example.kt or src/main/Example.kt:Example."
    else ->
      "check_ref must match AC-###, F-###, or a name ending in Test or Check, optionally followed " +
        "by :symbol; examples: AC-005, FeatureTaskRuntimeAuditEntryGateTest, or codeCheck:detekt."
  }
}

internal fun scrubResponseDerivedGateDetail(
  detail: String,
  rejectedOutput: Map<String, Any?>,
): String? {
  if (detail.isBlank()) return null
  var text = detail.take(SCHEMA_GATE_DETAIL_MAX_CHARS)
  text = scrubOffVocabularyVerdictQuote(text)
  text = OFFENDING_VALUE_APPENDIX_PATTERN.replace(text, "")
  text = EXPECTED_ACTUAL_LIST_PATTERN.replace(text, "")
  responseStringValues(rejectedOutput)
    .filter { value ->
      value.length >= MIN_RESPONSE_STRING_VALUE_LENGTH &&
        SCHEMA_DETAIL_TYPE_WORDS.none { typeWord -> typeWord.equals(value, ignoreCase = true) } &&
        text.contains(value)
    }
    .sortedByDescending(String::length)
    .forEach { value -> text = text.replace(value, "[response value omitted]") }
  return text.trim().takeUnless { it.isBlank() }
}

private fun responseStringValues(value: Any?): List<String> =
  when (value) {
    is String -> listOf(value)
    is Map<*, *> -> value.values.flatMap(::responseStringValues)
    is Iterable<*> -> value.flatMap(::responseStringValues)
    else -> emptyList()
  }.distinct()

internal fun scrubOffVocabularyVerdictQuote(text: String): String {
  val start = text.indexOf(OFF_VOCABULARY_VERDICT_OPEN, ignoreCase = true)
  if (start < 0) return text
  val afterOpenQuote = start + OFF_VOCABULARY_VERDICT_OPEN.length
  val closeAt = text.lastIndexOf(OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY)
  return if (closeAt >= afterOpenQuote) {
    text.substring(0, start) + "off-vocabulary verdict" + text.substring(closeAt + 1)
  } else {
    text.substring(0, start) + "off-vocabulary verdict"
  }
}

const val OFF_VOCABULARY_VERDICT_OPEN = "off-vocabulary verdict '"

const val OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY = "' and no"

val OFFENDING_VALUE_APPENDIX_PATTERN =
  Regex("""(?:\s*[—-]\s*)?offending value:.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

val EXPECTED_ACTUAL_LIST_PATTERN =
  Regex("""\bexpected=\[[^\]]*]\s*actual=\[[^\]]*]\.?""", RegexOption.IGNORE_CASE)

val BOUNDED_REF_LENGTH_CAP_PATTERN =
  Regex("""(?:allows|must be) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

val SCHEMA_DETAIL_TYPE_WORDS =
  setOf(
    "array",
    "boolean",
    "integer",
    "null",
    "number",
    "object",
    "string",
  )

const val MIN_RESPONSE_STRING_VALUE_LENGTH = 4
