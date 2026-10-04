package skillbill.workflow.taskruntime.model.review

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
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
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.INLINE, passNumber)
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
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
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(state.codeReviewMode, 2)
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    assertEquals(CodeReviewExecutionMode.AUTO, state.codeReviewMode)
    assertEquals("auto", state.toArtifactMap()["code_review_mode"])
  }

  @Test
  fun `a non-positive pass number fails loudly`() {
    listOf(0, -1, -7).forEach { passNumber ->
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, passNumber)
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    }
  }
}
