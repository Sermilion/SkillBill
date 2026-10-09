package skillbill.workflow.taskruntime.model.audit

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeNoChangePauseTest {
  @Test
  fun `no-change pause reads back what its writer produces`() {
    val pause =
      FeatureTaskRuntimeNoChangePause(
        reason = FeatureTaskRuntimeNoChangeReason.OUT_OF_REPO,
        criteria =
          listOf(
            FeatureTaskRuntimeNoChangeCriterion(
              criterionId = "AC-001",
              verdict = FeatureTaskRuntimeNoChangeReason.OUT_OF_REPO,
              evidence = "The ticket layout is rendered by the server.",
            ),
          ),
        citations = listOf("web/report.ts:40", "web/report.ts:52-60"),
        boundaryTrace = "the webapp sends only ticket IDs; the server renders the report",
        owningSystem = "ticket-service",
        suggestedHandoff = "Hand off to ticket-service",
        auditSummary = "Both citations support the claim.",
      )
    assertEquals(pause, FeatureTaskRuntimeNoChangePause.fromArtifactMap(pause.toArtifactMap()))
  }

  @Test
  fun `no-change pause rejects an artifact map with an unknown field`() {
    val map = pauseWithoutOwner().toArtifactMap().toMutableMap().also { it["unexpected"] = "x" }
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeNoChangePause.fromArtifactMap(map)
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  private fun pauseWithoutOwner(): FeatureTaskRuntimeNoChangePause =
    FeatureTaskRuntimeNoChangePause(
      reason = FeatureTaskRuntimeNoChangeReason.NOT_REPRODUCIBLE,
      criteria =
        listOf(
          FeatureTaskRuntimeNoChangeCriterion(
            criterionId = "AC-001",
            verdict = FeatureTaskRuntimeNoChangeReason.NOT_REPRODUCIBLE,
            evidence = "The report path has no failing branch.",
          ),
        ),
      citations = listOf("web/report.ts:40"),
      boundaryTrace = "the webapp sends only ticket IDs",
      owningSystem = null,
      suggestedHandoff = "Return the issue to the reporter for reproduction steps",
      auditSummary = "No failing branch found.",
    )
}
