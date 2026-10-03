package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import skillbill.workflow.time.parsePersistedInstant
import java.time.Instant

data class FeatureTaskRuntimePhaseRecord(
  val phaseId: String,
  val status: WorkflowStepStatus,
  val attemptCount: Int,
  val startedAt: Instant,
  val firstStartedAt: Instant = startedAt,
  val finishedAt: Instant? = null,
  val durationMillis: Long? = null,
  val resolvedAgentId: String,
  val executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin =
    FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
  val outputArtifact: String? = null,
  val rejectedOutput: String? = null,
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val fileManifestBefore: List<String> = emptyList(),
  val fileManifestAfter: List<String> = emptyList(),
  val fileManifestIntroduced: List<String> = emptyList(),
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
  val reviewRunId: String? = null,
) {
  constructor(
    phaseId: String,
    status: Any,
    attemptCount: Int,
    startedAt: String,
    firstStartedAt: String = startedAt,
    finishedAt: String? = null,
    durationMillis: Long? = null,
    resolvedAgentId: String,
    executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
    outputArtifact: String? = null,
    rejectedOutput: String? = null,
    blockedReason: String? = null,
    failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
    fileManifestBefore: List<String> = emptyList(),
    fileManifestAfter: List<String> = emptyList(),
    fileManifestIntroduced: List<String> = emptyList(),
    loopId: String? = null,
    edgeIteration: Int? = null,
    reviewPassNumber: Int? = null,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
    launchedModel: String? = null,
    launchedEffort: String? = null,
    reviewRunId: String? = null,
  ) : this(
    phaseId = phaseId,
    status =
      when (status) {
        is WorkflowStepStatus -> status
        is String ->
          requireNotNull(WorkflowStepStatus.fromWire(status)) {
            "Unknown feature-task-runtime phase status '$status'."
          }
        else -> error("Unknown feature-task-runtime phase status '$status'.")
      },
    attemptCount = attemptCount,
    startedAt = parsePersistedInstant(startedAt),
    firstStartedAt = parsePersistedInstant(firstStartedAt),
    finishedAt = finishedAt?.let(::parsePersistedInstant),
    durationMillis = durationMillis,
    resolvedAgentId = resolvedAgentId,
    executionOrigin = executionOrigin,
    outputArtifact = outputArtifact,
    rejectedOutput = rejectedOutput,
    blockedReason = blockedReason,
    failureDisposition = failureDisposition,
    fileManifestBefore = fileManifestBefore,
    fileManifestAfter = fileManifestAfter,
    fileManifestIntroduced = fileManifestIntroduced,
    loopId = loopId,
    edgeIteration = edgeIteration,
    reviewPassNumber = reviewPassNumber,
    repairEvidence = repairEvidence,
    launchedModel = launchedModel,
    launchedEffort = launchedEffort,
    reviewRunId = reviewRunId,
  )

  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.phaseId must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseRecord.attemptCount must be >= 1, was $attemptCount."
    }
    require(resolvedAgentId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.resolvedAgentId must be non-blank." }
    durationMillis?.let { duration ->
      require(duration >= 0) { "FeatureTaskRuntimePhaseRecord.durationMillis must be non-negative, was $duration." }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseRecord.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
    reviewPassNumber?.let { pass ->
      require(phaseId == "review" && pass >= 1) {
        "FeatureTaskRuntimePhaseRecord.reviewPassNumber must be >= 1 and present only for review."
      }
    }
    launchedModel?.let { model ->
      require(model.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.launchedModel must be non-blank when present." }
    }
    launchedEffort?.let { effort ->
      require(effort.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.launchedEffort must be non-blank when present." }
      require(launchedModel != null) {
        "FeatureTaskRuntimePhaseRecord.launchedEffort requires launchedModel; the launch pair moves as a unit."
      }
    }
    reviewRunId?.let { runId ->
      require(phaseId == "review" && runId.isNotBlank()) {
        "FeatureTaskRuntimePhaseRecord.reviewRunId must be non-blank and present only for review."
      }
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "record_kind" to "private_phase_record",
      SharedPayloadKeys.PHASE_ID to phaseId,
      SharedPayloadKeys.STATUS to status.wireValue,
      "attempt_count" to attemptCount,
      "started_at" to startedAt.toString(),
      "first_started_at" to firstStartedAt.toString(),
      FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID to resolvedAgentId,
      FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN to executionOrigin.wireValue,
    ).apply {
      finishedAt?.let { put("finished_at", it.toString()) }
      durationMillis?.let { put("duration_millis", it) }
      outputArtifact?.let { put("output_artifact", it) }
      blockedReason?.let { put(DecompositionManifestPayloadKeys.BLOCKED_REASON, it) }
      failureDisposition?.let { put(SharedPayloadKeys.FAILURE_DISPOSITION, it.wireValue) }
      if (fileManifestBefore.isNotEmpty()) put("file_manifest_before", fileManifestBefore)
      if (fileManifestAfter.isNotEmpty()) put("file_manifest_after", fileManifestAfter)
      if (fileManifestIntroduced.isNotEmpty()) put("file_manifest_introduced", fileManifestIntroduced)
      loopId?.let { put("loop_id", it) }
      edgeIteration?.let { put("edge_iteration", it) }
      reviewPassNumber?.let { put("review_pass_number", it) }
      repairEvidence?.let { put(FeatureTaskRuntimePhasePayloadKeys.REPAIR_EVIDENCE, it.toArtifactMap()) }
      putLaunchPair()
    }

  private fun MutableMap<String, Any?>.putLaunchPair() {
    launchedModel?.let { put("launched_model", it) }
    launchedEffort?.let { put("launched_effort", it) }
    reviewRunId?.let { put(ReviewVerificationSignalKeys.REVIEW_RUN_ID, it) }
  }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseRecord {
      requireCompatibleShape(raw)
      val phaseId =
        requireKnownFeatureTaskRuntimePhaseId(
          durableArtifactMapReader(raw).requiredString(SharedPayloadKeys.PHASE_ID),
          SharedPayloadKeys.PHASE_ID,
        )
      return try {
        val reader = durableArtifactMapReader(raw)
        FeatureTaskRuntimePhaseRecord(
          phaseId = phaseId,
          status =
            WorkflowStepStatus.fromWire(reader.requiredString(SharedPayloadKeys.STATUS))
              ?: incompatiblePhaseRecord(listOf("unknown status '${raw[SharedPayloadKeys.STATUS]}'")),
          attemptCount = reader.requiredInt("attempt_count"),
          startedAt = parsePersistedInstant(reader.requiredString("started_at")),
          firstStartedAt = parsePersistedInstant(reader.requiredString("first_started_at")),
          finishedAt = reader.optionalString("finished_at")?.let(::parsePersistedInstant),
          durationMillis = reader.optionalLong("duration_millis"),
          resolvedAgentId = reader.requiredString(FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID),
          executionOrigin =
            FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue(
              reader.requiredString(FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN),
            ),
          outputArtifact = reader.optionalString("output_artifact"),
          rejectedOutput = null,
          blockedReason = reader.optionalString(DecompositionManifestPayloadKeys.BLOCKED_REASON),
          failureDisposition =
            reader.optionalString(SharedPayloadKeys.FAILURE_DISPOSITION)?.let { value ->
              FeatureTaskRuntimeFailureDisposition.fromWireValue(value) ?: incompatiblePhaseRecord()
            },
          fileManifestBefore = reader.optionalStringList("file_manifest_before"),
          fileManifestAfter = reader.optionalStringList("file_manifest_after"),
          fileManifestIntroduced = reader.optionalStringList("file_manifest_introduced"),
          loopId = reader.optionalString("loop_id"),
          edgeIteration = reader.optionalInt("edge_iteration"),
          reviewPassNumber = reader.optionalInt("review_pass_number"),
          repairEvidence =
            raw[FeatureTaskRuntimePhasePayloadKeys.REPAIR_EVIDENCE]?.let { value ->
              val evidence =
                value as? Map<*, *>
                  ?: incompatiblePhaseRecord()
              FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(
                evidence.entries.associate { (key, item) -> key.toString() to item },
              )
            },
          launchedModel = reader.optionalString("launched_model"),
          launchedEffort = reader.optionalString("launched_effort"),
          reviewRunId = reader.optionalString("review_run_id"),
        )
      } catch (_: IllegalArgumentException) {
        incompatiblePhaseRecord()
      }
    }

    private fun requireCompatibleShape(raw: Map<String, Any?>) {
      val required =
        setOf(
          SharedPayloadKeys.CONTRACT_VERSION,
          "record_kind",
          SharedPayloadKeys.PHASE_ID,
          SharedPayloadKeys.STATUS,
          "attempt_count",
          "started_at",
          "first_started_at",
          FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID,
          FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN,
        )
      val allowed =
        required +
          setOf(
            "finished_at",
            "duration_millis",
            "output_artifact",
            DecompositionManifestPayloadKeys.BLOCKED_REASON,
            SharedPayloadKeys.FAILURE_DISPOSITION,
            "file_manifest_before",
            "file_manifest_after",
            "file_manifest_introduced",
            "loop_id",
            "edge_iteration",
            "review_pass_number",
            "rejected_output",
            FeatureTaskRuntimePhasePayloadKeys.REPAIR_EVIDENCE,
            "launched_model",
            "launched_effort",
            "review_run_id",
          )
      val missing = required - raw.keys
      val unknown = raw.keys - allowed
      val identityDetail =
        when {
          raw["record_kind"] != "private_phase_record" -> "record_kind was '${raw["record_kind"]}'"
          raw[SharedPayloadKeys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION ->
            "contract_version was '${raw[SharedPayloadKeys.CONTRACT_VERSION]}'"

          else -> null
        }
      if (missing.isNotEmpty() || unknown.isNotEmpty() || identityDetail != null) {
        incompatiblePhaseRecord(
          listOfNotNull(
            identityDetail,
            missing.takeIf { it.isNotEmpty() }?.let { "missing required keys ${it.sorted()}" },
            unknown.takeIf { it.isNotEmpty() }?.let {
              "unknown keys ${it.sorted()} (a row written by a newer runtime than this build)"
            },
          ),
        )
      }
    }

    private fun incompatiblePhaseRecord(details: List<String> = emptyList()): Nothing =
      throw InvalidWorkflowStateSchemaError(
        "Private feature-task-runtime phase record is incompatible with persistence contract " +
          "$FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION" +
          details.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty() +
          "; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
      )
  }
}
