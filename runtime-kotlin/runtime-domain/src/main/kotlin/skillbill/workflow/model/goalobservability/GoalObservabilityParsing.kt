package skillbill.workflow.model.goalobservability

import skillbill.contracts.JsonCodec
import skillbill.contracts.scaffold.wire.optionalList
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.goal.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.persistence.artifact.DurableArtifactMapReader
import skillbill.workflow.model.persistence.artifact.toStringKeyedArtifactMap
import skillbill.workflow.time.parsePersistedInstant

fun goalObservabilityLatestEventFromArtifacts(artifacts: Any): GoalObservabilityEvent? {
  val artifactMap = artifacts.asGoalWorkflowArtifactMap("goal observability artifacts")
  return artifactMap[GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY]
    ?.let { raw -> goalObservabilityEventFromArtifact(raw, GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY) }
}

internal fun goalObservabilityHistoryFromArtifacts(artifacts: Any): GoalObservabilityHistory {
  val artifactMap = artifacts.asGoalWorkflowArtifactMap("goal observability history artifacts")
  val rawHistory = artifactMap[GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY] ?: return GoalObservabilityHistory()
  val rawEvents =
    rawHistory as? List<*>
      ?: throw invalidGoalObservabilityEvent(
        GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY,
        "",
        "run history must be an array of goal-observability events.",
      )
  return GoalObservabilityHistory(
    events =
      rawEvents.mapIndexed { index, raw ->
        goalObservabilityEventFromArtifact(raw, "$GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY[$index]")
      },
  )
}

internal fun goalObservabilityEventFromArtifact(
  raw: Any?,
  sourceLabel: String,
): GoalObservabilityEvent {
  val eventMap = raw.toGoalObservabilityEventMap(sourceLabel)
  eventMap.requireOnlyKeys(GOAL_OBSERVABILITY_EVENT_KEYS, sourceLabel)
  val reader = goalObservabilityReader(eventMap, sourceLabel)
  return GoalObservabilityEvent(
    contractVersion = requireGoalObservabilityContractVersion(reader, sourceLabel),
    recordKind =
      reader.optionalString("record_kind")?.let(GoalObservabilityRecordKind::fromWire)
        ?: GoalObservabilityRecordKind.PROGRESS,
    issueKey = reader.requiredString("issue_key"),
    subtaskId =
      reader.requiredObservationInt(eventMap, "subtask_id", sourceLabel)
        .requirePositiveObservationInt(sourceLabel, "subtask_id"),
    workflowId = reader.optionalString("workflow_id"),
    workflowPhase = reader.requiredString("workflow_phase"),
    workerRole = reader.requiredString("worker_role"),
    livenessClass = reader.requiredString("liveness_class"),
    activitySummary = reader.requiredString("activity_summary"),
    timestamp = parseObservationTimestamp(reader.requiredString("timestamp"), sourceLabel),
    sequenceNumber =
      reader.requiredObservationInt(eventMap, "sequence_number", sourceLabel)
        .requireNonNegativeObservationInt(sourceLabel),
    changedFileSummary =
      reader.optionalNestedObject("changed_file_summary")
        ?.toChangedFileSummary(sourceLabel),
    diffStat = reader.optionalNestedObject("diff_stat")?.toDiffStat(sourceLabel),
    changedFiles =
      reader.optionalStringList("changed_files").also { files ->
        if (files.size > MAX_CHANGED_FILES) {
          throw invalidGoalObservabilityEvent(
            sourceLabel,
            "changed_files",
            "field must contain at most 500 entries.",
          )
        }
      },
    diffStatByFile = reader.optionalList("diff_stat_by_file").toFileDiffStats(sourceLabel),
  )
}

internal fun Any.asGoalWorkflowArtifactMap(sourceLabel: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(this)
    ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "artifacts must decode to an object.")

private fun Int.requirePositiveObservationInt(
  sourceLabel: String,
  field: String,
): Int =
  takeIf { it > 0 }
    ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a positive integer.")

private fun Int.requireNonNegativeObservationInt(sourceLabel: String): Int =
  takeIf { it >= 0 }
    ?: throw invalidGoalObservabilityEvent(sourceLabel, "sequence_number", "field must be non-negative.")

private fun parseObservationTimestamp(
  value: String,
  sourceLabel: String,
) = try {
  parsePersistedInstant(value)
} catch (error: IllegalArgumentException) {
  throw invalidGoalObservabilityEvent(sourceLabel, "timestamp", "field must be a persisted instant.", error)
}

private fun Any?.toGoalObservabilityEventMap(sourceLabel: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(this)
    ?: (this as? Map<*, *>)?.let { map ->
      map.entries.associate { (key, value) ->
        val stringKey =
          key as? String
            ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event keys must be strings.")
        stringKey to value
      }
    }
    ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event must be a JSON object.")

private fun List<*>?.toFileDiffStats(sourceLabel: String): List<GoalObservabilityFileDiffStat> =
  this?.mapIndexed { index, rawFile ->
    rawFile.asRequiredMap("$sourceLabel.diff_stat_by_file[$index]")
      .toFileDiffStat("$sourceLabel.diff_stat_by_file[$index]")
  }.orEmpty()

private fun Map<*, *>.toChangedFileSummary(sourceLabel: String): GoalObservabilityChangedFileSummary =
  requireOnlyKeys(GOAL_OBSERVABILITY_CHANGED_FILE_SUMMARY_KEYS, "$sourceLabel.changed_file_summary").let {
    val reader = goalObservabilityReader(this, "$sourceLabel.changed_file_summary")
    GoalObservabilityChangedFileSummary(
      total = reader.requiredObservationInt(this, "total", sourceLabel).requireNonNegative(sourceLabel, "total"),
      added = reader.requiredObservationInt(this, "added", sourceLabel).requireNonNegative(sourceLabel, "added"),
      modified =
        reader.requiredObservationInt(this, "modified", sourceLabel).requireNonNegative(sourceLabel, "modified"),
      deleted = reader.requiredObservationInt(this, "deleted", sourceLabel).requireNonNegative(sourceLabel, "deleted"),
      renamed = reader.requiredObservationInt(this, "renamed", sourceLabel).requireNonNegative(sourceLabel, "renamed"),
      untracked =
        reader.requiredObservationInt(this, "untracked", sourceLabel).requireNonNegative(sourceLabel, "untracked"),
      samplePaths = reader.optionalStringList("sample_paths"),
    )
  }

private fun Map<*, *>.toDiffStat(sourceLabel: String): GoalObservabilityDiffStat =
  requireOnlyKeys(GOAL_OBSERVABILITY_DIFF_STAT_KEYS, "$sourceLabel.diff_stat").let {
    val reader = goalObservabilityReader(this, "$sourceLabel.diff_stat")
    GoalObservabilityDiffStat(
      filesChanged =
        reader
          .requiredObservationInt(this, "files_changed", sourceLabel)
          .requireNonNegative(sourceLabel, "files_changed"),
      insertions =
        reader.requiredObservationInt(this, "insertions", sourceLabel).requireNonNegative(sourceLabel, "insertions"),
      deletions =
        reader.requiredObservationInt(this, "deletions", sourceLabel).requireNonNegative(sourceLabel, "deletions"),
    )
  }

private fun Map<*, *>.toFileDiffStat(sourceLabel: String): GoalObservabilityFileDiffStat =
  requireOnlyKeys(GOAL_OBSERVABILITY_FILE_DIFF_STAT_KEYS, sourceLabel).let {
    val reader = goalObservabilityReader(this, sourceLabel)
    GoalObservabilityFileDiffStat(
      path = reader.requiredString("path"),
      insertions =
        reader.requiredObservationInt(this, "insertions", sourceLabel).requireNonNegative(sourceLabel, "insertions"),
      deletions =
        reader.requiredObservationInt(this, "deletions", sourceLabel).requireNonNegative(sourceLabel, "deletions"),
    )
  }

private fun Int.requireNonNegative(
  sourceLabel: String,
  field: String,
): Int =
  takeIf { it >= 0 }
    ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a non-negative integer.")

private fun DurableArtifactMapReader.requiredObservationInt(
  map: Map<*, *>,
  key: String,
  sourceLabel: String,
): Int {
  val value = map[key]
  if (value != null && value !is Number) {
    throw invalidGoalObservabilityEvent(sourceLabel, key, "field must decode to an integer.")
  }
  return requiredInt(key)
}

private val GOAL_OBSERVABILITY_EVENT_KEYS =
  setOf(
    "contract_version",
    "record_kind",
    "issue_key",
    "subtask_id",
    "workflow_id",
    "workflow_phase",
    "worker_role",
    "liveness_class",
    "activity_summary",
    "timestamp",
    "sequence_number",
    "changed_file_summary",
    "diff_stat",
    "changed_files",
    "diff_stat_by_file",
  )

private val GOAL_OBSERVABILITY_CHANGED_FILE_SUMMARY_KEYS =
  setOf(
    "total",
    "added",
    "modified",
    "deleted",
    "renamed",
    "untracked",
    "sample_paths",
  )

private val GOAL_OBSERVABILITY_DIFF_STAT_KEYS = setOf("files_changed", "insertions", "deletions")

private val GOAL_OBSERVABILITY_FILE_DIFF_STAT_KEYS = setOf("path", "insertions", "deletions")

private const val MAX_CHANGED_FILES = 500

internal fun Map<*, *>.requireOnlyKeys(
  allowedKeys: Set<String>,
  sourceLabel: String,
) {
  keys.forEach { key ->
    val stringKey =
      key as? String
        ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event keys must be strings.")
    if (stringKey !in allowedKeys) {
      throw invalidGoalObservabilityEvent(sourceLabel, "", "unknown field '$stringKey' is not allowed.")
    }
  }
}

internal fun Any?.asRequiredMap(sourceLabel: String): Map<*, *> =
  this as? Map<*, *>
    ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "field must be an object.")

internal fun goalObservabilityReader(
  map: Map<*, *>,
  sourceLabel: String,
): DurableArtifactMapReader {
  val converted =
    map.toStringKeyedArtifactMap { detail ->
      throw invalidGoalObservabilityEvent(sourceLabel, "", detail)
    }
  return DurableArtifactMapReader(converted) { detail ->
    throw invalidGoalObservabilityEvent(
      sourceLabel,
      goalObservabilityMalformedField(detail, sourceLabel),
      "malformed durable field.",
    )
  }
}

private fun goalObservabilityMalformedField(
  detail: String,
  sourceLabel: String,
): String {
  if (detail.contains("missing required field")) return ""
  return Regex("""'([^']+)'""")
    .find(detail)
    ?.groupValues
    ?.get(1)
    ?.let { field ->
      val localField = if (field == "changed_files" && detail.contains("contain only")) "$field[0]" else field
      if (sourceLabel.contains('.') || sourceLabel.contains('[')) {
        "${sourceLabel.substringAfter('.', sourceLabel)}.$localField"
      } else {
        localField
      }
    }
    ?: ""
}

internal fun requireGoalObservabilityContractVersion(
  reader: DurableArtifactMapReader,
  sourceLabel: String,
): String =
  reader.requiredString("contract_version").also { value ->
    if (value != GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION) {
      throw invalidGoalObservabilityEvent(
        sourceLabel,
        "contract_version",
        "field must equal $GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION.",
      )
    }
  }
