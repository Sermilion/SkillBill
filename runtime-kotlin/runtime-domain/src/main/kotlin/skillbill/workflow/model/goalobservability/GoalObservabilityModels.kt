package skillbill.workflow.model.goalobservability

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PROGRESS_EVENT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.time.parsePersistedInstant
import java.time.Instant

internal const val GOAL_OBSERVABILITY_HISTORY_LIMIT: Int = 50

const val GOAL_PROGRESS_HISTORY_LIMIT: Int = 50

enum class GoalObservabilityRecordKind(val wireValue: String) {
  PROGRESS("progress"),
  REFUSAL("refusal"),
  MIGRATION("migration"),
  ;

  companion object {
    fun fromWire(value: String): GoalObservabilityRecordKind =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidGoalObservabilityEvent("record_kind", "record_kind", "unrecognized value '$value'.")
  }
}

enum class GoalProgressEventKind(val wireValue: String) {
  PHASE_STARTED("phase_started"),
  PHASE_COMPLETED("phase_completed"),
  OPERATION_STARTED("operation_started"),
  OPERATION_HEARTBEAT("operation_heartbeat"),
  OPERATION_COMPLETED("operation_completed"),
  ;

  val isOperationEvent: Boolean
    get() = this == OPERATION_STARTED || this == OPERATION_HEARTBEAT || this == OPERATION_COMPLETED

  companion object {
    fun fromWire(value: String): GoalProgressEventKind =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidGoalProgressEventSchemaError("<wire>", "event_kind", "unrecognized value '$value'.")
  }
}

enum class GoalProgressOutcome(val wireValue: String) {
  NONE("none"),
  SUCCEEDED("succeeded"),
  FAILED("failed"),
  TIMED_OUT("timed_out"),
  CANCELLED("cancelled"),
  ;

  companion object {
    fun fromWire(value: String): GoalProgressOutcome =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidGoalProgressEventSchemaError("<wire>", "outcome", "unrecognized value '$value'.")
  }
}

data class GoalProgressEvent(
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val processAlive: Boolean,
  val sequenceNumber: Int,
  val timestamp: Instant,
  val stepId: String? = null,
  val operationName: String? = null,
  val operationKind: String? = null,
  val expectedLong: Boolean = false,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  val contractVersion: String = GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
) {
  constructor(
    eventKind: GoalProgressEventKind,
    workflowId: String,
    workflowPhase: String,
    processAlive: Boolean,
    sequenceNumber: Int,
    timestamp: String,
    stepId: String? = null,
    operationName: String? = null,
    operationKind: String? = null,
    expectedLong: Boolean = false,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
    contractVersion: String = GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
  ) : this(
    eventKind = eventKind,
    workflowId = workflowId,
    workflowPhase = workflowPhase,
    processAlive = processAlive,
    sequenceNumber = sequenceNumber,
    timestamp = parsePersistedInstant(timestamp),
    stepId = stepId,
    operationName = operationName,
    operationKind = operationKind,
    expectedLong = expectedLong,
    outcome = outcome,
    contractVersion = contractVersion,
  )

  init {
    require(workflowId.isNotBlank()) { "GoalProgressEvent.workflowId is required." }
    require(workflowPhase.isNotBlank()) { "GoalProgressEvent.workflowPhase is required." }
    require(sequenceNumber >= 0) { "GoalProgressEvent.sequenceNumber must be non-negative." }
    if (eventKind.isOperationEvent) {
      require(!operationName.isNullOrBlank()) {
        "GoalProgressEvent.operationName is required for operation_* events."
      }
    }
  }

  fun toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      "event_kind" to eventKind.wireValue,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "workflow_phase" to workflowPhase,
      "process_alive" to processAlive,
      "sequence_number" to sequenceNumber,
      "timestamp" to timestamp.toString(),
    ).apply {
      stepId?.takeIf(String::isNotBlank)?.let { put(SharedPayloadKeys.STEP_ID, it) }
      operationName?.takeIf(String::isNotBlank)?.let { put("operation_name", it) }
      operationKind?.takeIf(String::isNotBlank)?.let { put("operation_kind", it) }
      if (eventKind.isOperationEvent) {
        put("expected_long", expectedLong)
      }
      if (outcome != GoalProgressOutcome.NONE) {
        put("outcome", outcome.wireValue)
      }
    }
}

private data class GoalProgressHistory(
  val events: List<GoalProgressEvent> = emptyList(),
  val retentionLimit: Int = GOAL_PROGRESS_HISTORY_LIMIT,
) {
  fun append(event: GoalProgressEvent): GoalProgressHistory =
    copy(events = (events + event).sortedBy(GoalProgressEvent::sequenceNumber).takeLast(retentionLimit))

  internal fun toArtifactList(): List<Map<String, Any?>> = events.map(GoalProgressEvent::toArtifactMap)

  fun latest(): GoalProgressEvent? = events.maxByOrNull(GoalProgressEvent::sequenceNumber)
}

private const val GOAL_OBSERVABILITY_SAMPLE_PATH_LIMIT: Int = 10

data class GoalObservabilityChangedFileSummary(
  val total: Int,
  val added: Int,
  val modified: Int,
  val deleted: Int,
  val renamed: Int,
  val untracked: Int,
  val samplePaths: List<String> = emptyList(),
)

data class GoalObservabilityDiffStat(
  val filesChanged: Int,
  val insertions: Int,
  val deletions: Int,
)

data class GoalObservabilityFileDiffStat(
  val path: String,
  val insertions: Int,
  val deletions: Int,
)

data class GoalObservabilitySelectedDiffHunk(
  val path: String,
  val staged: Boolean,
  val header: String,
  val lines: List<String>,
  val truncated: Boolean,
)

data class GoalObservabilitySelectedDiffHunks(
  val hunks: List<GoalObservabilitySelectedDiffHunk> = emptyList(),
  val truncated: Boolean = false,
)

data class GoalObservabilityEvent(
  val recordKind: GoalObservabilityRecordKind = GoalObservabilityRecordKind.PROGRESS,
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val timestamp: Instant,
  val sequenceNumber: Int,
  val workflowId: String? = null,
  val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
  val diffStat: GoalObservabilityDiffStat? = null,
  val changedFiles: List<String> = emptyList(),
  val diffStatByFile: List<GoalObservabilityFileDiffStat> = emptyList(),
  val contractVersion: String = GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
) {
  constructor(
    recordKind: GoalObservabilityRecordKind = GoalObservabilityRecordKind.PROGRESS,
    issueKey: String,
    subtaskId: Int,
    workflowPhase: String,
    workerRole: String,
    livenessClass: String,
    activitySummary: String,
    timestamp: String,
    sequenceNumber: Int,
    workflowId: String? = null,
    changedFileSummary: GoalObservabilityChangedFileSummary? = null,
    diffStat: GoalObservabilityDiffStat? = null,
    changedFiles: List<String> = emptyList(),
    diffStatByFile: List<GoalObservabilityFileDiffStat> = emptyList(),
    contractVersion: String = GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
  ) : this(
    recordKind = recordKind,
    issueKey = issueKey,
    subtaskId = subtaskId,
    workflowPhase = workflowPhase,
    workerRole = workerRole,
    livenessClass = livenessClass,
    activitySummary = activitySummary,
    timestamp = parsePersistedInstant(timestamp),
    sequenceNumber = sequenceNumber,
    workflowId = workflowId,
    changedFileSummary = changedFileSummary,
    diffStat = diffStat,
    changedFiles = changedFiles,
    diffStatByFile = diffStatByFile,
    contractVersion = contractVersion,
  )

  internal fun toArtifactMap(includeHeavyFields: Boolean = false): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      "record_kind" to recordKind.wireValue,
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "workflow_phase" to workflowPhase,
      "worker_role" to workerRole,
      "liveness_class" to livenessClass,
      "activity_summary" to activitySummary,
      "timestamp" to timestamp.toString(),
      "sequence_number" to sequenceNumber,
    ).apply {
      changedFileSummary?.let { summary -> put("changed_file_summary", summary.toArtifactMap()) }
      diffStat?.let { stat -> put("diff_stat", stat.toArtifactMap()) }
      if (includeHeavyFields && changedFiles.isNotEmpty()) {
        put("changed_files", changedFiles)
      }
      if (includeHeavyFields && diffStatByFile.isNotEmpty()) {
        put("diff_stat_by_file", diffStatByFile.map(GoalObservabilityFileDiffStat::toArtifactMap))
      }
    }.filterValues { value -> value != null }

  fun compactLivenessSummary(): String =
    buildString {
      append("liveness=")
      append(livenessClass)
      append(" phase=")
      append(workflowPhase)
      append(" role=")
      append(workerRole)
      append(" sequence=")
      append(sequenceNumber)
      append(" at=")
      append(timestamp)
      append(" activity=")
      append(activitySummary)
      changedFileSummary?.let { summary ->
        append(" files=")
        append(summary.total)
      }
      diffStat?.let { stat ->
        append(" diff=+")
        append(stat.insertions)
        append("/-")
        append(stat.deletions)
      }
    }

  fun toCompactSummaryWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toCompactSummaryMap())

  internal fun toCompactSummaryMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      "workflow_phase" to workflowPhase,
      "worker_role" to workerRole,
      "liveness_class" to livenessClass,
      "activity_summary" to activitySummary,
      "sequence_number" to sequenceNumber,
      "timestamp" to timestamp.toString(),
    ).apply {
      changedFileSummary?.let { summary -> put("changed_file_summary", summary.toArtifactMap()) }
      diffStat?.let { stat -> put("diff_stat", stat.toArtifactMap()) }
    }
}

internal data class GoalObservabilityHistory(
  val events: List<GoalObservabilityEvent> = emptyList(),
  val retentionLimit: Int = GOAL_OBSERVABILITY_HISTORY_LIMIT,
) {
  fun nextSequenceNumber(): Int = events.maxOfOrNull(GoalObservabilityEvent::sequenceNumber)?.let { it + 1 } ?: 0

  fun append(event: GoalObservabilityEvent): GoalObservabilityHistory =
    copy(events = (events + event).sortedBy(GoalObservabilityEvent::sequenceNumber).takeLast(retentionLimit))

  internal fun toArtifactList(includeHeavyFields: Boolean = false): List<Map<String, Any?>> =
    events.map { event -> event.toArtifactMap(includeHeavyFields) }
}

private fun GoalObservabilityChangedFileSummary.toArtifactMap(): Map<String, Any?> =
  linkedMapOf(
    "total" to total,
    "added" to added,
    "modified" to modified,
    "deleted" to deleted,
    "renamed" to renamed,
    "untracked" to untracked,
    "sample_paths" to samplePaths.take(GOAL_OBSERVABILITY_SAMPLE_PATH_LIMIT),
  )

private fun GoalObservabilityDiffStat.toArtifactMap(): Map<String, Any?> =
  linkedMapOf(
    "files_changed" to filesChanged,
    "insertions" to insertions,
    "deletions" to deletions,
  )

private fun GoalObservabilityFileDiffStat.toArtifactMap(): Map<String, Any?> =
  linkedMapOf(
    "path" to path,
    "insertions" to insertions,
    "deletions" to deletions,
  )
