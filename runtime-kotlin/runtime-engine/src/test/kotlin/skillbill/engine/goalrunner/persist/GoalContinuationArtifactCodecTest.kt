package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.engine.goalrunner.repair.GOAL_BRANCH
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
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
}
