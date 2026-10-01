package skillbill.workflow.model.goalreview

import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalSubtaskRemediationBaseShaTest {
  private val preFixSha = "c".repeat(40)

  @Test
  fun `remediation base sha round-trips through the artifact map`() {
    val stored = initialState().copy(remediationBaseSha = preFixSha)

    assertEquals(preFixSha, stored.toArtifactMap()["remediation_base_sha"])
    assertEquals(preFixSha, GoalSubtaskReviewState.fromArtifactMap(stored.toArtifactMap()).remediationBaseSha)
  }

  @Test
  fun `a record without a remediation base sha decodes to null and omits the key`() {
    val initial = initialState()

    assertNull(initial.remediationBaseSha)
    assertEquals(false, initial.toArtifactMap().containsKey("remediation_base_sha"))
    assertNull(GoalSubtaskReviewState.fromArtifactMap(initial.toArtifactMap()).remediationBaseSha)
  }

  @Test
  fun `a malformed remediation base sha is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> { initialState().copy(remediationBaseSha = "not-a-sha") }
  }

  private fun initialState() =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
}
