package skillbill.infrastructure.launcher.agentrun

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import skillbill.infrastructure.launcher.review.CursorReviewStreamError
import skillbill.infrastructure.launcher.review.CursorReviewStreamForbiddenOperationError
import skillbill.infrastructure.launcher.review.CursorReviewStreamMalformedError
import skillbill.infrastructure.launcher.review.CursorReviewStreamProviderFailureError
import skillbill.infrastructure.launcher.review.CursorReviewStreamTerminationError
import skillbill.ports.agentrun.model.ReportedLaunchIdentity
import skillbill.ports.agentrun.model.ReportedLaunchIdentityReason
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

internal fun decodeCursorStreamJson(stdout: String): DecodedAgentRunOutput {
  if (stdout.isBlank()) {
    return cursorOutput("")
  }
  val lines = stdout.lineSequence().toList()
  if (lines.isEmpty()) {
    return cursorOutput("")
  }
  val parsed = parseCursorStreamLines(lines)
  parsed.error?.let { throw it }
  val harvested = pickCursorHarvest(parsed.terminalText, parsed.lastAssistantText, parsed.longestAssistantText)
  return cursorOutput(
    harvested,
    assistantEventCount = parsed.assistantEventCount.takeIf { parsed.decodedEnvelope },
    rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS).takeIf { harvested.isBlank() },
  )
}

private data class CursorStreamParse(
  val terminalText: String?,
  val longestAssistantText: String?,
  val lastAssistantText: String?,
  val assistantEventCount: Int,
  val decodedEnvelope: Boolean,
  val error: Throwable?,
)

private fun parseCursorStreamLines(lines: List<String>): CursorStreamParse {
  var terminalText: String? = null
  var longestAssistantText: String? = null
  var lastAssistantText: String? = null
  var assistantEventCount = 0
  var decodedEnvelope = false
  var errorEvent = false
  var errorType: String? = null
  var errorMessage: String? = null
  var totalByteCount = 0
  lines.asSequence().takeWhile { line ->
    totalByteCount += line.toByteArray().size
    totalByteCount <= CURSOR_STREAM_MAX_TOTAL_BYTES
  }.filter(String::isNotBlank).forEach { line ->
    val event =
      try {
        structuredOutputMapper.readTree(line)
      } catch (error: JsonProcessingException) {
        throw CursorReviewStreamMalformedError(
          "Malformed Cursor stream JSONL line: ${line.take(CURSOR_STREAM_MALFORMED_LINE_PREVIEW_CHARS)}",
          error,
        )
      }
    decodedEnvelope = true
    when (event.path("type").takeIf { it.isTextual }?.asText()) {
      "error" -> {
        errorEvent = true
        errorType = event.path("error_type").takeIf { it.isTextual }?.asText()
        errorMessage = event.path("message").takeIf { it.isTextual }?.asText()
      }
      "assistant" -> {
        assistantEventCount += 1
        cursorAssistantText(event)?.let { text ->
          lastAssistantText = text
          if (text.length > (longestAssistantText?.length ?: 0)) longestAssistantText = text
        }
      }
      "result" -> {
        terminalText = event.path("result").takeIf { it.isTextual }?.asText()
      }
    }
  }
  val error = if (errorEvent) cursorStreamError(errorType, errorMessage) else null
  return CursorStreamParse(
    terminalText = terminalText,
    longestAssistantText = longestAssistantText,
    lastAssistantText = lastAssistantText,
    assistantEventCount = assistantEventCount,
    decodedEnvelope = decodedEnvelope,
    error = error,
  )
}

private fun cursorStreamError(
  errorType: String?,
  errorMessage: String?,
): Throwable =
  when (errorType) {
    "forbidden_operation" ->
      CursorReviewStreamForbiddenOperationError(
        errorMessage ?: "Cursor reported a forbidden operation",
      )
    "provider_failure" ->
      CursorReviewStreamProviderFailureError(
        errorMessage ?: "Cursor reported a provider failure",
      )
    "termination" ->
      CursorReviewStreamTerminationError(
        errorMessage ?: "Cursor process terminated prematurely",
      )
    else ->
      CursorReviewStreamError(
        errorMessage ?: "Cursor reported an unknown error",
      )
  }

private fun pickCursorHarvest(
  terminalText: String?,
  lastAssistantText: String?,
  longestAssistantText: String?,
): String {
  val last = lastAssistantText?.trim().orEmpty()
  if (isStrictReviewRegister(last) || isStrictAssessmentRegister(last)) return last
  val source = terminalText?.takeIf { it.isNotBlank() } ?: longestAssistantText.orEmpty()
  return harvestCursorRegister(source)
}

private fun harvestCursorRegister(text: String): String {
  val trimmed = text.trim()
  if (trimmed.isEmpty() || isStrictReviewRegister(trimmed) || isStrictAssessmentRegister(trimmed)) {
    return trimmed
  }
  peelTrailingNoFindings(trimmed)?.let { return it }
  val split = insertCursorRegisterBoundaries(trimmed)
  val splitLines =
    split.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .toList()
  val findingLines = splitLines.filter { FINDING_LINE_START.containsMatchIn(it) }
  val verdictLine = splitLines.firstOrNull { CANONICAL_VERDICT_LINE.matches(it) }
  val hasNoFindingsToken = splitLines.any { it == NO_FINDINGS_TOKEN }
  return when {
    findingLines.isNotEmpty() ->
      (findingLines + listOfNotNull(verdictLine)).joinToString("\n")
    hasNoFindingsToken && verdictLine != null ->
      splitLines
        .filter { line ->
          line == NO_FINDINGS_TOKEN ||
            CANONICAL_VERDICT_LINE.matches(line) ||
            isReviewRegisterControlPrefix(line)
        }.joinToString("\n")
    hasNoFindingsToken -> NO_FINDINGS_TOKEN
    else -> assessmentRegisterOf(split) ?: trimmed
  }
}

private fun insertCursorRegisterBoundaries(text: String): String {
  val withFindings = GLUED_FINDING_START.replace(text, "\n$1")
  val withVerdict = GLUED_CANONICAL_VERDICT_START.replace(withFindings, "\n$1")
  val withNoFindings = GLUED_TRAILING_NO_FINDINGS.replace(withVerdict, "\n$1")
  return GLUED_ASSESSMENT_START.replace(withNoFindings, "\n$1")
}

private fun isStrictReviewRegister(text: String): Boolean {
  val trimmed = text.trim()
  if (trimmed == NO_FINDINGS_TOKEN) return true
  val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  return lines.isNotEmpty() && lines.all { isReviewRegisterLine(it) }
}

private fun isReviewRegisterLine(line: String): Boolean =
  when {
    line == NO_FINDINGS_TOKEN -> true
    line.equals("No findings.", ignoreCase = true) -> true
    CANONICAL_VERDICT_LINE.matches(line) -> true
    isReviewRegisterControlPrefix(line) -> true
    FINDING_LINE_START.containsMatchIn(line) -> true
    REVIEW_REGISTER_MARKDOWN_HEADING.matches(line) -> true
    REVIEW_REGISTER_COLON_HEADING.matches(line) -> true
    else -> false
  }

private fun isReviewRegisterControlPrefix(line: String): Boolean =
  line.startsWith("lanes:", ignoreCase = true) ||
    line.startsWith("coverage:", ignoreCase = true) ||
    line.startsWith("warning:", ignoreCase = true)

private fun isStrictAssessmentRegister(text: String): Boolean {
  val lines = assessmentLines(text)
  return lines.isNotEmpty() && lines.all { ASSESSMENT_RECORD_LINE.containsMatchIn(it) }
}

private fun assessmentRegisterOf(text: String): String? {
  val lines = assessmentLines(text).filter { ASSESSMENT_RECORD_LINE.containsMatchIn(it) }
  return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun assessmentLines(text: String): List<String> =
  text.lineSequence()
    .map { it.trim().trimStart('-', '*', '>', ' ', '`').trimEnd('`', ' ') }
    .filter { it.isNotEmpty() }
    .toList()

private fun peelTrailingNoFindings(text: String): String? {
  val trimmed = text.trim()
  if (trimmed == NO_FINDINGS_TOKEN) return NO_FINDINGS_TOKEN
  if (FINDING_CANDIDATE.containsMatchIn(trimmed)) return null
  if (TRAILING_NO_FINDINGS.containsMatchIn(trimmed)) return NO_FINDINGS_TOKEN
  return null
}

private fun cursorAssistantText(event: JsonNode): String? {
  val content = event.path("message").path("content")
  if (content.isArray) {
    val joined =
      content.mapNotNull { part -> part.path("text").takeIf { it.isTextual }?.asText() }
        .joinToString("")
    return joined.takeIf(String::isNotBlank)
  }
  return event.path("message").path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
    ?: event.path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
}

private fun cursorOutput(
  text: String,
  assistantEventCount: Int? = null,
  rawOutputPreview: String? = null,
): DecodedAgentRunOutput =
  DecodedAgentRunOutput(
    text = text,
    assistantEventCount = assistantEventCount,
    rawOutputPreview = rawOutputPreview,
    reportedIdentity = ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.UNSUPPORTED_AGENT),
  )

private const val NO_FINDINGS_TOKEN = "NO_FINDINGS"
private const val CURSOR_STREAM_MAX_TOTAL_BYTES = 10_000_000
private const val CURSOR_STREAM_MALFORMED_LINE_PREVIEW_CHARS = 100
private val FINDING_LINE_START =
  Regex(
    "^\\s*(?:-\\s+)?\\[F-\\d{${ParallelReviewFindingParser.PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH}}]",
  )
private val FINDING_CANDIDATE = Regex("\\[F-\\d+]")
private val TRAILING_NO_FINDINGS = Regex("(?:^|[^A-Z0-9_])NO_FINDINGS\\s*$")
private val GLUED_FINDING_START =
  Regex(
    "(?<![\\n\\r])(\\[F-\\d{${ParallelReviewFindingParser.PARALLEL_REVIEW_FINDING_ID_PAD_WIDTH}}])",
  )
private val CANONICAL_VERDICT_BODY =
  "verdict:\\s*(?:${FeatureTaskRuntimeVerdict.APPROVED.wireValue}|" +
    "${FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue})"
private val GLUED_TRAILING_NO_FINDINGS =
  Regex("(?<![\\n\\r])(NO_FINDINGS)(?=\\s*(?:\$|(?i:$CANONICAL_VERDICT_BODY)))")
private val CANONICAL_VERDICT_LINE =
  Regex("^$CANONICAL_VERDICT_BODY$", RegexOption.IGNORE_CASE)
private val GLUED_CANONICAL_VERDICT_START =
  Regex("(?<=NO_FINDINGS)[ \\t]*((?i:$CANONICAL_VERDICT_BODY))")
private val REVIEW_REGISTER_MARKDOWN_HEADING =
  Regex(
    "^#{1,6}\\s+(?:summary|overview|review|findings|code quality(?: \\(non-blocking\\))?)\\s*$",
    RegexOption.IGNORE_CASE,
  )
private val REVIEW_REGISTER_COLON_HEADING =
  Regex(
    "^(?:Summary|Overview|Review|Findings|Code Quality):$",
    RegexOption.IGNORE_CASE,
  )
private val ASSESSMENT_RECORD_LINE =
  Regex("""^(Finding|Test value|Scenario|Regression|Verify)\b""", RegexOption.IGNORE_CASE)
private val GLUED_ASSESSMENT_START =
  Regex(
    "(?<![\\n\\r])(Finding:|Test value:|Scenario IS-|Regression TR-|Verify GA-|Verify:)",
    RegexOption.IGNORE_CASE,
  )
