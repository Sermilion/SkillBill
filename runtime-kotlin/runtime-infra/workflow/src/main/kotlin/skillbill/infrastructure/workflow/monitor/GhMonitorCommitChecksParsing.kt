package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

private const val CHECK_RUNS_FIELD = "check_runs"
private const val STATUSES_FIELD = "statuses"

internal fun parseMonitorCommitChecks(
  mapper: ObjectMapper,
  checkRunsStdout: String,
  statusStdout: String,
): PullRequestChecks {
  val checks =
    try {
      val checkRuns = readEntries(mapper, checkRunsStdout, CHECK_RUNS_FIELD, ::checkRunBucket)
      val statuses = readEntries(mapper, statusStdout, STATUSES_FIELD, ::statusBucket)
      if (checkRuns == null || statuses == null) null else checkRuns + statuses
    } catch (_: IOException) {
      null
    }
  return when {
    checks == null -> PullRequestChecks.Unavailable("GitHub returned unparsable or incomplete commit checks.")
    checks.isEmpty() -> PullRequestChecks.NoChecks
    else -> PullRequestChecks.Reported(checks)
  }
}

private fun readEntries(
  mapper: ObjectMapper,
  stdout: String,
  field: String,
  bucketOf: (JsonNode) -> CheckBucket,
): List<PullRequestCheck>? {
  val checks = mutableListOf<PullRequestCheck>()
  var pages = 0
  mapper.factory.createParser(stdout).use { parser ->
    while (parser.nextToken() != null) {
      val entries = mapper.readTree<JsonNode>(parser).path(field).takeIf(JsonNode::isArray) ?: return null
      pages += 1
      for (entry in entries) checks += checkOf(entry, bucketOf) ?: return null
    }
  }
  return checks.takeIf { pages > 0 }
}

private fun checkOf(
  entry: JsonNode,
  bucketOf: (JsonNode) -> CheckBucket,
): PullRequestCheck? {
  val name = textOf(entry, "name") ?: textOf(entry, "context") ?: return null
  return PullRequestCheck(
    name,
    bucketOf(entry),
    textOf(entry, "details_url") ?: textOf(entry, "target_url").orEmpty(),
    startedAtOf(entry),
  )
}

private fun checkRunBucket(entry: JsonNode): CheckBucket =
  if (textOf(entry, "status") != "completed") {
    CheckBucket.PENDING
  } else {
    when (textOf(entry, "conclusion")) {
      "success" -> CheckBucket.PASS
      "skipped", "neutral" -> CheckBucket.SKIPPING
      "failure", "timed_out", "action_required", "error", "startup_failure" -> CheckBucket.FAIL
      "cancelled" -> CheckBucket.CANCEL
      else -> CheckBucket.PENDING
    }
  }

private fun statusBucket(entry: JsonNode): CheckBucket =
  when (textOf(entry, "state")) {
    "success" -> CheckBucket.PASS
    "failure", "error" -> CheckBucket.FAIL
    else -> CheckBucket.PENDING
  }

private fun textOf(
  node: JsonNode,
  field: String,
): String? = node.path(field).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)

private fun startedAtOf(entry: JsonNode): Instant? =
  textOf(entry, "started_at")?.let { text ->
    try {
      Instant.parse(text)
    } catch (_: DateTimeParseException) {
      null
    }
  }
