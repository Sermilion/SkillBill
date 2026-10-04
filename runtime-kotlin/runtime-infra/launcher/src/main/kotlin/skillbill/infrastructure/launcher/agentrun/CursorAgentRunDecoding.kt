package skillbill.infrastructure.launcher.agentrun

import com.fasterxml.jackson.databind.JsonNode
import skillbill.infrastructure.launcher.review.cursorReviewProviderFailure
import skillbill.infrastructure.launcher.review.cursorReviewTermination
import skillbill.infrastructure.launcher.review.cursorReviewUnknown
import skillbill.infrastructure.launcher.review.forbiddenCursorReviewOperation
import skillbill.infrastructure.launcher.review.malformedCursorReviewStream
import skillbill.review.parallel.ParallelReviewFindingParser

internal fun decodeCursorStreamJson(stdout: String): DecodedAgentRunOutput {
  if (stdout.isBlank()) {
    return DecodedAgentRunOutput("")
  }
  val lines = stdout.lineSequence().toList()
  if (lines.isEmpty()) {
    return DecodedAgentRunOutput("")
  }
  val parsed = parseCursorStreamLines(lines)
  parsed.error?.let { throw it }
  val harvested = pickCursorHarvest(parsed.terminalText, parsed.lastAssistantText, parsed.longestAssistantText)
  return DecodedAgentRunOutput(
    text = harvested,
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
      runCatching { structuredOutputMapper.readTree(line) }.getOrElse {
        throw malformedCursorReviewStream(
          "Malformed Cursor stream JSONL line: ${line.take(CURSOR_STREAM_MALFORMED_LINE_PREVIEW_CHARS)}",
          it,
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
      forbiddenCursorReviewOperation(
        errorMessage ?: "Cursor reported a forbidden operation",
      )
    "provider_failure" ->
      cursorReviewProviderFailure(
        errorMessage ?: "Cursor reported a provider failure",
      )
    "termination" ->
      cursorReviewTermination(
        errorMessage ?: "Cursor process terminated prematurely",
      )
    else ->
      cursorReviewUnknown(
        errorMessage ?: "Cursor reported an unknown error",
      )
  }

private fun pickCursorHarvest(
  terminalText: String?,
  lastAssistantText: String?,
  longestAssistantText: String?,
): String {
  val last = lastAssistantText?.trim().orEmpty()
  if (isStrictReviewRegister(last)) return last
  val source = terminalText?.takeIf { it.isNotBlank() } ?: longestAssistantText.orEmpty()
  return harvestCursorRegister(source)
}

private fun harvestCursorRegister(text: String): String {
  val trimmed = text.trim()
  if (trimmed.isEmpty() || isStrictReviewRegister(trimmed)) return trimmed
  peelTrailingNoFindings(trimmed)?.let { return it }
  val split = insertCursorRegisterBoundaries(trimmed)
  val findingLines =
    split.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && FINDING_LINE_START.containsMatchIn(it) }
      .toList()
  return when {
    findingLines.isNotEmpty() -> findingLines.joinToString("\n")
    split.lineSequence().any { it.trim() == NO_FINDINGS_TOKEN } -> NO_FINDINGS_TOKEN
    else -> trimmed
  }
}

private fun insertCursorRegisterBoundaries(text: String): String {
  val withFindings = GLUED_FINDING_START.replace(text, "\n$1")
  return GLUED_TRAILING_NO_FINDINGS.replace(withFindings, "\n$1")
}

private fun isStrictReviewRegister(text: String): Boolean {
  val trimmed = text.trim()
  if (trimmed == NO_FINDINGS_TOKEN) return true
  val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  return lines.isNotEmpty() && lines.all { FINDING_LINE_START.containsMatchIn(it) }
}

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
private val GLUED_TRAILING_NO_FINDINGS = Regex("(?<![\\n\\r])(NO_FINDINGS)\\s*$")
