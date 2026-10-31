package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.phase.record.requireCurrentWorker
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.time.Clock

internal class FeatureTaskRuntimeGoalContinuationStateRecorder(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val clock: Clock,
) {
  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
      if (admitted != null &&
        !requireCurrentWorker(admitted, unitOfWork.workflowStates, request.workflowId)
      ) {
        return@transaction false
      }
      val artifacts = record.artifacts
      val existingContinuation = continuationFromArtifacts(artifacts)
      val supplied =
        request.continuation?.let { continuation ->
          continuation.copy(subtaskName = continuation.subtaskName ?: existingContinuation?.subtaskName)
        }
      check(existingContinuation.compatibleWith(supplied)) {
        "Goal continuation is immutable for workflow '${request.workflowId}'; " +
          "parent, subtask, branch, and review mode cannot change on resume."
      }
      val continuationPatch = continuationPatch(supplied, existingContinuation)
      val reviewStatePatch = reviewStatePatch(request.copy(continuation = supplied), artifacts, existingContinuation)
      val acceptedOutcome =
        request.outcome?.let { outcome ->
          outcome.withAcceptedBlockReference(record) {
            admitted?.workerOwnership?.generation
              ?: unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(record.workflowId)?.generation
          }
        }
      val outcomePatch =
        acceptedOutcome?.let {
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.entry(it.toPersistenceWire()),
          )
        }.orEmpty()
      val adoptionPatch =
        request.fieldAdoption?.let {
          mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION.entry(it))
        }.orEmpty()
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            terminalInstant = clock.instant(),
            workflowStatus =
              request.workflowStatus?.let { status ->
                WorkflowStatus.fromWire(status)
                  ?: error("Unknown workflow status '$status'.")
              } ?: record.workflowStatus,
            currentStepId = acceptedOutcome?.lastResumableStep ?: record.currentStepId,
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                continuationPatch + reviewStatePatch + outcomePatch + adoptionPatch,
              ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
      true
    }

  private fun FeatureTaskRuntimeGoalContinuationOutcome.withAcceptedBlockReference(
    record: WorkflowStateSnapshot,
    expectedGeneration: () -> Long?,
  ): FeatureTaskRuntimeGoalContinuationOutcome {
    if (status != GoalRunnerTerminalStatus.BLOCKED) return copy(acceptedBlockReference = null)
    val identity = BlockIncidentIdentity.fromArtifacts(record.artifacts) ?: return copy(acceptedBlockReference = null)
    val generation = expectedGeneration()
    val acceptedAttempt =
      record.steps.find { it.stepId == lastResumableStep }
        ?.attemptCount?.takeIf { it > 0 }
    val reference =
      identity.takeIf {
        it.stepId == lastResumableStep &&
          it.attempt == acceptedAttempt &&
          (generation == null || it.generation == generation)
      }?.acceptedReference(record.workflowId)
    val retained =
      acceptedBlockReference?.takeIf {
        it.matchesIdentity(record.workflowId, lastResumableStep, identity.attempt, identity.generation) &&
          it.incidentToken == identity.incidentToken &&
          it.aliasValue == identity.alias.value
      }
    return copy(acceptedBlockReference = retained ?: reference)
  }

  fun reviewState(workflowId: String): GoalSubtaskReviewState? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      reviewStateFromArtifacts(record.artifacts)
    }

  fun continuation(workflowId: String): FeatureTaskRuntimeGoalContinuationArtifact? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      continuationFromArtifacts(record.artifacts)
    }
}
