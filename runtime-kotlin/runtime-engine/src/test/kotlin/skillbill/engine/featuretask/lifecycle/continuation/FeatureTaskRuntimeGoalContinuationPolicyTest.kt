package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.task.runtime.goal.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeGoalContinuationPolicyTest {
  private val baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList())

  @Test
  fun `absent durable validation depth adopts supplied depth without conflict`() {
    assertNull(
      goalContinuationConflict(
        request = request(goalContinuation = continuation(validationDepth = ValidationDepth.FULL)),
        durable = durable(validationDepth = null),
        baseline = baseline,
      ),
    )
  }

  @Test
  fun `matching durable and supplied validation depth proceeds`() {
    assertNull(
      goalContinuationConflict(
        request = request(goalContinuation = continuation(validationDepth = ValidationDepth.DEFAULT)),
        durable = durable(validationDepth = ValidationDepth.FULL),
        baseline = baseline,
      ),
    )
    assertEquals(ValidationDepth.FULL, ValidationDepth.DEFAULT)
  }

  @Test
  fun `FeatureTaskRuntimeGoalContinuationContext defaults validationDepth to full`() {
    assertEquals(
      ValidationDepth.FULL,
      FeatureTaskRuntimeGoalContinuationContext(
        parentIssueKey = "SKILL-173",
        subtaskId = 1,
        goalBranch = "feat/SKILL-173",
        suppressPr = true,
        reviewBaseline = baseline,
      ).validationDepth,
    )
  }

  private fun request(
    goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  ): FeatureTaskRuntimeRunRequest =
    FeatureTaskRuntimeRunRequest(
      issueKey = "SKILL-173",
      workflowId = "wfl-child",
      sessionId = "ftr-child",
      runInvariants =
        FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-173/spec.md",
          featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
          acceptanceCriteria = listOf("AC-001"),
          mandatesAndOverrides = emptyList(),
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      invokedAgentId = "claude",
      repoRoot = Path.of("/tmp/skillbill-validation-depth"),
      goalContinuation = goalContinuation,
    )

  private fun continuation(validationDepth: ValidationDepth): FeatureTaskRuntimeGoalContinuationContext =
    FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = "SKILL-173",
      subtaskId = 1,
      goalBranch = "feat/SKILL-173",
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = validationDepth,
      reviewBaseline = baseline,
    )

  private fun durable(validationDepth: ValidationDepth?): FeatureTaskRuntimeGoalContinuationArtifact =
    FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = "SKILL-173",
      subtaskId = 1,
      suppressPr = true,
      goalBranch = "feat/SKILL-173",
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = validationDepth,
    )
}
