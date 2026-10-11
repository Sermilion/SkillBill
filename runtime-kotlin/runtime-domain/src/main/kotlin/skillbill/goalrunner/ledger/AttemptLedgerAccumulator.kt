package skillbill.goalrunner.ledger

import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.model.GoalAttemptLedgerStatusEntry
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.asGoalRunnerIntOrNull
import skillbill.goalrunner.model.recordDurableDecodeSubstitution
import skillbill.workflow.engine.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.workflow.model.goalobservability.asGoalWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun summarizeAttemptLedgerFromEntries(entries: Iterable<Map<*, *>>): GoalRunnerAttemptLedgerSummary =
  summarizeAttemptLedgerStatusEntries(
    entries.asSequence().map {
      GoalAttemptLedgerStatusEntry.fromWire(FeatureTaskRuntimeWorkflowArtifactMap.from(it))
    }.asIterable(),
  )

fun summarizeAttemptLedgerStatusEntries(
  entries: Iterable<GoalAttemptLedgerStatusEntry>,
): GoalRunnerAttemptLedgerSummary {
  var blockedAttemptCount = 0
  var supervisorKillCount = 0
  val phaseAttemptCounts = linkedMapOf<String, Int>()
  val cumulativeFixIterations = linkedMapOf<String, Int>()
  val reAttemptCauseCounts = linkedMapOf<String, Int>()
  var findingsInScope: Int? = null
  entries.forEach { entry ->
    val action = entry.action ?: return@forEach
    if (entry.stopReason != null) {
      blockedAttemptCount += blockStopReasonCount(entry.stopReason)
      entry.reAttemptCause?.takeIf(String::isNotBlank)?.let { cause ->
        reAttemptCauseCounts.merge(cause, 1, Int::plus)
      }
      entry.findingsInScope?.let { findingsInScope = it }
    }
    if (entry.diagnosticClass == "supervisor_killed_confirmed_alive") supervisorKillCount++
    when (action) {
      "child_activation", "resume" -> {
        val step =
          entry.currentStep?.takeIf(String::isNotBlank)
            ?: entry.previousStep?.takeIf(String::isNotBlank)
            ?: "initial_start"
        phaseAttemptCounts.merge(step, 1, Int::plus)
      }
      "backward_edge_entry" -> {
        val subtaskId = entry.subtaskId ?: return@forEach
        val loopId = entry.loopId?.takeIf(String::isNotBlank) ?: return@forEach
        val count = entry.cumulativeLoopCount ?: return@forEach
        cumulativeFixIterations.merge("$subtaskId:$loopId", count, ::maxOf)
      }
    }
  }
  return GoalRunnerAttemptLedgerSummary(
    blockedAttemptCount = blockedAttemptCount,
    supervisorKillCount = supervisorKillCount,
    phaseAttemptCounts = phaseAttemptCounts,
    cumulativeFixIterations = cumulativeFixIterations,
    reAttemptCauseCounts = reAttemptCauseCounts,
    findingsInScope = findingsInScope,
  )
}

private val BLOCK_STOP_REASONS: Set<String> =
  setOf(
    "failed",
    "blocked",
    "policy_blocked",
    "dependencies_blocked",
    "pull_request_failed",
  )

fun backwardEdgeCountsFromLedger(artifacts: Any): Map<String, Int> {
  val wire = artifacts.asGoalWorkflowArtifactMap("goal attempt ledger artifacts")
  val entries = (wire[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty()
  val counts = mutableMapOf<String, Int>()
  entries.forEach { item ->
    val entry = item as? Map<*, *> ?: return@forEach
    if (entry["action"]?.toString() != "backward_edge_entry") return@forEach
    val subtaskId = entry[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() ?: return@forEach
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return@forEach
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return@forEach
    val key = "$subtaskId:$loopId"
    counts.merge(key, count, ::maxOf)
  }
  return counts
}

fun parseInstantOrNull(value: String): Instant? {
  try {
    return Instant.parse(value)
  } catch (_: DateTimeParseException) {
  }
  return try {
    LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
  } catch (_: DateTimeParseException) {
    recordDurableDecodeSubstitution(
      seam = "AttemptLedgerAccumulator.parseInstantOrNull",
      valueUsed = "null",
      expectedValue = "instant_or_sqlite_timestamp",
      reason = "timestamp_parse_failed",
    )
    null
  }
}

private fun isBlockStopReason(stopReason: String?): Boolean =
  stopReason != null && stopReason.lowercase() in BLOCK_STOP_REASONS

private fun blockStopReasonCount(stopReason: String?): Int = if (isBlockStopReason(stopReason)) 1 else 0

val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
