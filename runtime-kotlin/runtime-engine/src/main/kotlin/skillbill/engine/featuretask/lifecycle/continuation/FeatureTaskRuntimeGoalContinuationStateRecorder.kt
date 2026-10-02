package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.time.Clock

class FeatureTaskRuntimeGoalContinuationStateRecorder(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val clock: Clock,
) {
  internal fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
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
      val outcomePatch =
        request.outcome?.let {
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.entry(it.toPersistenceWire()),
          )
        }.orEmpty()
      val adoptionPatch =
        request.fieldAdoption?.let {
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION.entry(
              it.asWorkflowArtifactEntry(),
            ),
          )
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
            currentStepId = request.outcome?.lastResumableStep ?: record.currentStepId,
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
