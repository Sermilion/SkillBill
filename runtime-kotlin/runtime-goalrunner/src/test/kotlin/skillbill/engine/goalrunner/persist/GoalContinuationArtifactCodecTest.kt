package skillbill.engine.goalrunner.persist

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.persistence.UndecodedTypedArtifact
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()

class GoalContinuationArtifactCodecTest {
  @Test
  fun `engine continuation reader rethrows the stored decode failure instead of treating it as no child`() {
    val undecoded =
      UndecodedTypedArtifact(
        jsonText = """{"issue_key":"SKILL-372","subtask_id":2.7}""",
        failure = invalidWorkflowStateSchemaError("Goal-continuation artifact subtask_id must be an integer."),
      )

    assertFailsWith<SkillBillRuntimeException> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to undecoded),
      ).goalContinuation()
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `engine continuation reader distinguishes an absent artifact from a malformed artifact`() {
    assertNull(DurableWorkflowArtifacts.EMPTY.goalContinuation())
    assertFailsWith<SkillBillRuntimeException> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to null),
      ).goalContinuation()
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `a workflow stored under another mode reads as absent without consulting message text`() {
    val states = ModeAwareWorkflowStates(FeatureTaskWorkflowMode.PROSE)

    assertNull(taskRuntimeRecordOrNull(states, WORKFLOW_ID))
  }

  @Test
  fun `a malformed task runtime row still propagates its schema error`() {
    val states = ModeAwareWorkflowStates(FeatureTaskWorkflowMode.RUNTIME)

    assertFailsWith<SkillBillRuntimeException> {
      taskRuntimeRecordOrNull(states, WORKFLOW_ID)
    }.also {
      assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code)
    }
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
        throw invalidWorkflowStateSchemaError("Workflow '$workflowId' has a malformed steps payload.")
      }
      return getFeatureTaskWorkflow(workflowId).toSnapshot()
    }
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-codec-test"
  }
}
