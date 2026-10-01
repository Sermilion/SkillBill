package skillbill.workflow.taskruntime.model.review

import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeReviewPassSequenceTest {
  @Test
  fun `pass one keeps the tier its pinned mode resolves to`() {
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.AUTO, 1),
    )
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, 1),
    )
    assertEquals(
      CodeReviewExecutionMode.DELEGATED,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.DELEGATED, 1),
    )
  }

  @Test
  fun `pass two and later fail loudly instead of reserving remediation review`() {
    listOf(2, 3, 7).forEach { passNumber ->
      assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
        FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.INLINE, passNumber)
      }
    }
  }

  @Test
  fun `auto never resolves silently and names the deciding rule`() {
    assertEquals(
      "auto_mode_by_pass_number:pass_1_inline",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 1).decidingRule,
    )
  }

  @Test
  fun `an explicit mode overrides auto and is recorded as an override`() {
    assertEquals(
      "explicit_inline_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.INLINE, 1).decidingRule,
    )
    assertEquals(
      "explicit_delegated_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 1).decidingRule,
    )
  }

  @Test
  fun `resolving auto does not mutate the pinned code review mode`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(state.codeReviewMode, 2)
    }
    assertEquals(CodeReviewExecutionMode.AUTO, state.codeReviewMode)
    assertEquals("auto", state.toArtifactMap()["code_review_mode"])
  }

  @Test
  fun `a non-positive pass number fails loudly`() {
    listOf(0, -1, -7).forEach { passNumber ->
      assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
        FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, passNumber)
      }
    }
  }
}
