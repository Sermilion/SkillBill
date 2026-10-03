package skillbill.engine.featuretask.persist

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStepWireUpdate
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.WorkflowIssueKeyConflictError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

const val GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY: String = "goal_child_repair_evidence"

internal data class WorkflowRowAdvance(
  val currentStepId: String,
  val workflowStatus: String,
  val stepUpdates: List<FeatureTaskRuntimePhaseStepWireUpdate>? = null,
  val terminalInstant: Instant? = null,
) {
  companion object {
    fun keepFrom(record: WorkflowStateSnapshot): WorkflowRowAdvance =
      WorkflowRowAdvance(currentStepId = record.currentStepId, workflowStatus = record.workflowStatus.wireValue)
  }
}

class FeatureTaskRuntimeWorkflowPersistence
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  ) {
    private val engine: WorkflowEngine = WorkflowEngine()

    fun ensureWorkflowOpen(
      workflowId: String,
      sessionId: String,
      issueKey: String? = null,
      executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
    ): Boolean =
      database.transaction { unitOfWork ->
        val normalizedIssueKey = normalizeIssueKey(issueKey)
        val existing =
          unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
        if (existing != null) {
          val storedPlan =
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
              existing.toSnapshot().artifacts,
            )
          if (storedPlan == null) throw MissingFeatureTaskRuntimeExecutionPlanError()
          if (executionPlan != null && storedPlan !=
            JsonCodec.parseValue(
              executionPlan.encoded().toString(Charsets.UTF_8),
            )
          ) {
            throw FeatureTaskRuntimeExecutionPlanConflictError()
          }
          val persistedIssueKey =
            existing.issueKey
              ?.trim()
              ?.takeIf(String::isNotEmpty)
              ?.let(::normalizeIssueKey)
          if (
            persistedIssueKey != null &&
            normalizedIssueKey != null &&
            persistedIssueKey != normalizedIssueKey
          ) {
            throw WorkflowIssueKeyConflictError(workflowId, persistedIssueKey, normalizedIssueKey)
          }
          if (persistedIssueKey == null && normalizedIssueKey != null) {
            unitOfWork.workflowStates.saveFeatureTaskWorkflow(
              existing.copy(issueKey = normalizedIssueKey, sessionId = existing.sessionId.ifBlank { sessionId }),
              FeatureTaskWorkflowMode.RUNTIME,
            )
          } else if (existing.sessionId.isBlank()) {
            unitOfWork.workflowStates.saveFeatureTaskWorkflow(
              existing.copy(sessionId = sessionId),
              FeatureTaskWorkflowMode.RUNTIME,
            )
          }
          return@transaction true
        }
        if (executionPlan == null) throw MissingFeatureTaskRuntimeExecutionPlanError()
        val opened =
          engine.openRecord(
            WorkflowFamily.TASK_RUNTIME.definition,
            workflowId,
            sessionId,
            WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
          )
        unitOfWork.workflowStates.saveRecord(
          WorkflowFamily.TASK_RUNTIME,
          opened.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                mapOf(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                    JsonCodec.parseValue(executionPlan.encoded().toString(Charsets.UTF_8)),
                  ),
                ),
              ),
          ).toRecord().copy(issueKey = normalizedIssueKey),
        )
        true
      }

    fun readArtifacts(workflowId: String): DurableWorkflowArtifacts? =
      database.read { unitOfWork ->
        val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
        workflowSnapshotValidator.validate(record, record.workflowName)
        record.artifacts
      }

    internal fun persistArtifactsPatch(
      workflowStates: WorkflowStateRepository,
      record: WorkflowStateSnapshot,
      patch: Map<String, Any?>,
      advance: WorkflowRowAdvance = WorkflowRowAdvance.keepFrom(record),
    ) {
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            terminalInstant = advance.terminalInstant,
            workflowStatus =
              WorkflowStatus.fromWire(advance.workflowStatus)
                ?: throw InvalidWorkflowStateSchemaError(
                  "Workflow update workflow_status has unsupported value '${advance.workflowStatus}'.",
                ),
            currentStepId = advance.currentStepId,
            stepUpdates =
              WorkflowStepUpdates.from(
                advance.stepUpdates?.map(FeatureTaskRuntimePhaseStepWireUpdate::toWireMap),
              ),
            artifactsPatch = WorkflowArtifactPatch.from(patch),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      workflowSnapshotValidator.validate(updated, updated.workflowName)
      workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
    }

    internal fun persistRunInvariantsPatch(
      workflowStates: WorkflowStateRepository,
      record: WorkflowStateSnapshot,
      runInvariants: FeatureTaskRuntimeRunInvariants,
    ) {
      persistArtifactsPatch(
        workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.entry(
            runInvariants.asWorkflowArtifactEntry(),
          ),
        ),
      )
    }
  }

internal object FeatureTaskRuntimeWorkflowArtifactPatches {
  fun clearGoalContinuationOutcome(): Map<String, Any?> =
    mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.entry(null))

  fun goalContinuationArtifact(entry: Any): Map<String, Any?> =
    mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(entry))

  fun goalSubtaskReviewState(entry: Any): Map<String, Any?> =
    mapOf(DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(entry))

  fun goalReviewBaseRecoveries(entries: List<Any?>): Map<String, Any?> =
    mapOf(DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.entry(entries))

  fun goalChildRepairEvidence(entries: List<Any?>): Map<String, Any?> =
    mapOf(GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY to entries)
}

internal fun stepUpdatesFrom(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
): List<FeatureTaskRuntimePhaseStepWireUpdate> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String =
    when {
      record.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
      record.status.workflowStepStatus() == WorkflowStepStatus.PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
      record.status.workflowStepStatus() == WorkflowStepStatus.PENDING -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
      record.finishedAt != null -> "completed"
      record.status.workflowStepStatus() in setOf(WorkflowStepStatus.RUNNING, WorkflowStepStatus.COMPLETED) ->
        record.status.wireValue
      else -> throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime phase '${record.phaseId}' has unmappable status '${record.status}' for steps[].",
      )
    }
  return records.values.map { record ->
    FeatureTaskRuntimePhaseStepWireUpdate(
      stepId = record.phaseId,
      status = stepStatusFor(record),
      attemptCount = record.attemptCount,
    )
  }
}

fun workflowStatusFor(request: FeatureTaskRuntimePhaseStateRequest): String =
  when {
    request.status.workflowStepStatus() == WorkflowStepStatus.PAUSED -> "paused"
    request.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED -> "blocked"
    request.finished && request.phaseId in PhaseSlot.PULL_REQUEST.steps ->
      "completed"
    else -> "running"
  }

fun attemptStatusFor(request: FeatureTaskRuntimePhaseStateRequest): FeatureTaskRuntimeImplementationAttemptStatus =
  when (request.status.workflowStepStatus()) {
    WorkflowStepStatus.COMPLETED -> FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED
    WorkflowStepStatus.BLOCKED -> FeatureTaskRuntimeImplementationAttemptStatus.BLOCKED
    else -> FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
  }

fun durationMillis(
  startedAt: Instant,
  finishedAt: Instant,
): Long = Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(0)

fun sha256Hex(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

const val PHASE_RECORDER_STATUS_RUNNING = "running"
