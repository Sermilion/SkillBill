package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()

class GoalContinuationArtifactCodecTest {
  @Test
  fun `engine continuation reader rejects malformed payload instead of treating it as no child`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
            mapOf(
              SharedPayloadKeys.ISSUE_KEY to "SKILL-372",
              SharedPayloadKeys.SUBTASK_ID to 2.7,
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR to true,
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH to "feat/SKILL-372",
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE to "inline",
            ),
        ),
      ).goalContinuation()
    }
  }

  @Test
  fun `engine continuation reader distinguishes an absent artifact from a malformed artifact`() {
    assertNull(DurableWorkflowArtifacts.EMPTY.goalContinuation())
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to null),
      ).goalContinuation()
    }
  }

  @Test
  fun `a workflow stored under another mode reads as absent without consulting message text`() {
    val states = ModeAwareWorkflowStates(FeatureTaskWorkflowMode.PROSE)

    assertNull(taskRuntimeRecordOrNull(states, WORKFLOW_ID))
  }

  @Test
  fun `a malformed task runtime row still propagates its schema error`() {
    val states = ModeAwareWorkflowStates(FeatureTaskWorkflowMode.RUNTIME)

    assertFailsWith<InvalidWorkflowStateSchemaError> { taskRuntimeRecordOrNull(states, WORKFLOW_ID) }
  }

  private class ModeAwareWorkflowStates(
    private val mode: FeatureTaskWorkflowMode,
  ) : WorkflowStateRepositoryDefaults() {
    override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord =
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "session",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "plan",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = mode,
      )

    override fun get(
      family: WorkflowFamily,
      workflowId: String,
    ): WorkflowStateSnapshot? {
      if (family != WorkflowFamily.TASK_RUNTIME) return null
      if (mode == FeatureTaskWorkflowMode.RUNTIME) {
        throw InvalidWorkflowStateSchemaError("Workflow '$workflowId' has a malformed steps payload.")
      }
      return getFeatureTaskWorkflow(workflowId).toSnapshot()
    }
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-codec-test"
  }
}
