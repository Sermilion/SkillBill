package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AcceptanceRepairBlockReasonTest {
  @Test
  fun `long block reason retains every gap and charged outcome within the limit`() {
    val criterion =
      AcceptanceCriterionRepairCriterion(
        canonicalRef = "AC-001",
        originalAlias = "S3-AC-001",
        originalText = "S3-AC-001. Authentication flow works.",
        ordinal = 1,
        sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
        validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
        validationOwnerAvailable = true,
        evidenceRef = "audit:AC-001",
        gaps =
          (1..AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION).map { index ->
            AcceptanceCriterionGap(
              id = "AC-001.G$index",
              text = "t".repeat(AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH),
              citation = "src/Foo.kt:" + "1".repeat(AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH - 12),
              status = AcceptanceCriterionGapStatus.OPEN,
              origin = AcceptanceCriterionGapOrigin.DISCOVERY,
              closedRevision = null,
            )
          },
      )
    val outcomes =
      (1..3).map { index ->
        AcceptanceCriterionRepairOutcome(
          outcomeId = "outcome-$index",
          reservationId = "reservation-$index",
          criterionRef = "AC-001",
          digest = "a".repeat(64),
          kind = AcceptanceCriterionRepairOutcomeKind.BLOCKED,
          evidenceRef = "external dependency",
          charged = true,
          openGapsBefore = 32,
          openGapsAfter = 32,
        )
      }
    val state =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf(criterion.originalText),
          listOf(criterion),
          emptyList(),
        ),
      )!!.copy(
        criteria = listOf(criterion.copy(escalated = true)),
        chargedAttempts = mapOf("AC-001" to 3),
        outcomes = outcomes,
        stage = AcceptanceCriterionRepairStage.BUDGET_BLOCKED,
      )

    val reason = AcceptanceRepairBlockReason.build(state, "AC-001")

    assertTrue(reason.length <= AcceptanceRepairBlockReason.MAX_CHARS)
    assertContains(reason, "...")
    assertContains(reason, "AC-001 (S3-AC-001)")
    (1..32).forEach { assertContains(reason, "AC-001.G$it:") }
    outcomes.forEach { outcome ->
      assertContains(reason, "${outcome.outcomeId} blocked open gaps 32 -> 32")
    }
    assertTrue(reason.endsWith("An operator retry resets the charged count for this criterion only."))

    val citedCriterion = criterion.copy(gaps = criterion.gaps.map { it.copy(citation = "src/Foo.kt:12") })
    val citedReason = AcceptanceRepairBlockReason.build(state.copy(criteria = listOf(citedCriterion)), "AC-001")

    assertTrue(citedReason.length <= AcceptanceRepairBlockReason.MAX_CHARS)
    citedCriterion.gaps.forEach { gap ->
      assertContains(citedReason.lines().single { it.startsWith("${gap.id}:") }, " @ ${gap.citation}")
    }
  }
}
