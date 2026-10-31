package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.time.Instant
import java.time.format.DateTimeParseException

private val checksMapper: ObjectMapper by lazy { ObjectMapper() }

internal fun parsePullRequestChecks(result: GhCommandResult): PullRequestChecks {
  val entries =
    (
      try {
        checksMapper.readTree(result.stdout)
      } catch (_: JsonProcessingException) {
        null
      }
    )?.takeIf(JsonNode::isArray)
  return when {
    entries != null -> checksFromEntries(entries)
    result.stdout.contains(NO_CHECKS_MARKER, ignoreCase = true) -> PullRequestChecks.NoChecks
    result.stdout.contains(UNKNOWN_FLAG_MARKER, ignoreCase = true) ->
      PullRequestChecks.Unavailable(UNSUPPORTED_JSON_REASON)
    else -> PullRequestChecks.Unavailable(result.describeFailure())
  }
}

private fun checksFromEntries(entries: JsonNode): PullRequestChecks {
  if (entries.size() == 0) return PullRequestChecks.NoChecks
  val checks = entries.map(::checkFrom)
  return if (checks.all { it != null }) {
    PullRequestChecks.Reported(checks.filterNotNull())
  } else {
    PullRequestChecks.Unavailable(UNRECOGNISED_CHECK_REASON)
  }
}

private fun checkFrom(entry: JsonNode): PullRequestCheck? {
  val name = entry.path("name").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) ?: return null
  val bucket = entry.path("bucket").takeIf(JsonNode::isTextual)?.asText()?.let(::bucketFrom) ?: return null
  val link = entry.path("link").takeIf(JsonNode::isTextual)?.asText().orEmpty()
  return PullRequestCheck(name = name, bucket = bucket, link = link, startedAt = startedAtFrom(entry))
}

private fun startedAtFrom(entry: JsonNode): Instant? =
  entry.path("startedAt").takeIf(JsonNode::isTextual)?.asText()
    ?.let { text ->
      try {
        Instant.parse(text)
      } catch (_: DateTimeParseException) {
        null
      }
    }
    ?.takeIf { it.isAfter(Instant.EPOCH) }

private fun bucketFrom(wireValue: String): CheckBucket? = CheckBucket.entries.firstOrNull { it.wireValue == wireValue }

private const val NO_CHECKS_MARKER: String = "no checks reported"
private const val UNKNOWN_FLAG_MARKER: String = "unknown flag"
private const val UNSUPPORTED_JSON_REASON: String = "gh pr checks --json unsupported; upgrade gh"
private const val UNRECOGNISED_CHECK_REASON: String = "GitHub CLI returned a check with an unrecognised bucket or name."
