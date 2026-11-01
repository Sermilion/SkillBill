package skillbill.engine.goalrunner.review

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.toOutcomeRecord
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDispositionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFindingOutcomeDerivationTest {
  @Test
  fun `every finding a pass produces receives an outcome without any manual triage`() {
    val findings = List(3) { index -> finding(ordinal = index + 1, findingId = "F-00${index + 1}") }

    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = emptyList(),
        currentFindings = findings,
        blockerDispositions = emptyList(),
      )

    assertEquals(3, outcomes.size, "Every finding the run produced must get an outcome row.")
    assertTrue(
      outcomes.all { it.outcome == ReviewFindingOutcome.CARRIED },
      "A finding the current pass still reports is carried until a later pass retires it.",
    )
    assertEquals(listOf(1, 2, 3), outcomes.map { it.findingOrdinal })
  }

  @Test
  fun `a run producing zero findings records nothing and does not error`() {
    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = emptyList(),
        currentFindings = emptyList(),
        blockerDispositions = emptyList(),
      )

    assertEquals(emptyList(), outcomes)
  }

  @Test
  fun `a finding an earlier pass reported and this pass does not is recorded as addressed`() {
    val addressed = finding(ordinal = 1, findingId = "F-001")
    val stillOpen = finding(ordinal = 2, findingId = "F-002")

    val renumbered = stillOpen.copy(reviewPassNumber = 2, findingOrdinal = 1, findingId = "F-001")

    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = listOf(addressed, stillOpen),
        currentFindings = listOf(renumbered),
        blockerDispositions = emptyList(),
      )

    assertEquals(
      ReviewFindingOutcome.ADDRESSED,
      outcomes.single { it.findingKey == addressed.findingKey }.outcome,
      "The fix loop retired the first finding, so it was addressed.",
    )
    assertEquals(
      listOf(ReviewFindingOutcome.CARRIED),
      outcomes.filter { it.findingKey == stillOpen.findingKey }.map { it.outcome },
      "The survivor stays carried and is not double-recorded, despite being renumbered to F-001.",
    )
  }

  @Test
  fun `renumbering across passes never inverts an outcome`() {
    val fixed = finding(ordinal = 1, findingId = "F-001")
    val survivor = finding(ordinal = 2, findingId = "F-002")

    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = listOf(fixed, survivor),
        currentFindings = listOf(survivor.copy(reviewPassNumber = 2, findingOrdinal = 1, findingId = "F-001")),
        blockerDispositions = emptyList(),
      )

    assertEquals(
      emptyList(),
      outcomes.filter { it.findingKey == survivor.findingKey && it.outcome == ReviewFindingOutcome.ADDRESSED },
      "A finding this pass still reports must never be recorded as addressed.",
    )
    assertEquals(
      emptyList(),
      outcomes.filter { it.findingKey == fixed.findingKey && it.outcome == ReviewFindingOutcome.CARRIED },
      "A finding the loop fixed must never be left carried because a survivor took over its id.",
    )
  }

  @Test
  fun `an explicit resolved blocker disposition overrides the cross-pass inference`() {
    val superseded = finding(ordinal = 1, findingId = "F-001")

    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = listOf(superseded),
        currentFindings = emptyList(),
        blockerDispositions =
          listOf(
            GoalSubtaskBlockerDisposition(
              findingId = "F-001",
              verdict = GoalSubtaskBlockerDispositionVerdict.RESOLVED,
              evidence = listOf("src/First.kt:7 no longer exists"),
            ),
          ),
      )

    assertEquals(
      ReviewFindingOutcome.ADDRESSED,
      outcomes.single().outcome,
      "An explicitly resolved Blocker is addressed, not silently counted as carried.",
    )
  }

  @Test
  fun `a finding without a reported id is still matched across passes by its content`() {
    val anonymous = finding(ordinal = 1, findingId = null)

    val addressed =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = listOf(anonymous),
        currentFindings = emptyList(),
        blockerDispositions = emptyList(),
      )
    val carried =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = listOf(anonymous),
        currentFindings = listOf(anonymous.copy(reviewPassNumber = 2)),
        blockerDispositions = emptyList(),
      )

    assertEquals(
      ReviewFindingOutcome.ADDRESSED,
      addressed.single().outcome,
      "Identity is content-derived, so a finding with no reported id is still matched across passes.",
    )
    assertEquals(
      listOf(ReviewFindingOutcome.CARRIED),
      carried.map { it.outcome },
      "The same anonymous finding reported again is carried, not double-recorded.",
    )
  }

  @Test
  fun `the review run id the pass reported resolves the shared key on every ledger row and outcome`() {
    val output =
      mapOf(
        "produced_outputs" to
          mapOf(
            "review_run_id" to "run-2026-08-06-a",
            "findings" to
              listOf(
                mapOf(
                  "id" to "F-001",
                  "severity" to "major",
                  "message" to "Outbox error signal is ambiguous",
                  "issue_category" to "data_persistence",
                  "location" to "src/Outbox.kt:12",
                ),
              ),
          ),
      )

    val ledgerFindings =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output = output,
        scope = UnaddressedFindingLedgerScope("SKILL-136", 6, "wf-1", 1),
      )
    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = emptyList(),
        currentFindings = ledgerFindings,
        blockerDispositions = emptyList(),
      )

    assertEquals("run-2026-08-06-a", ledgerFindings.single().reviewRunId)
    val outcome = outcomes.single()
    assertEquals("run-2026-08-06-a", outcome.reviewRunId)
    assertEquals("F-001", outcome.findingId)
    assertEquals(
      "resolved",
      outcome.keyState,
      "A reported run id plus a finding id is the shared key that joins the loop finding to the routed pack.",
    )
  }

  @Test
  fun `a blank or absent review run id stays unresolved rather than being recorded as a key`() {
    val findings =
      listOf(
        mapOf(
          "id" to "F-001",
          "severity" to "major",
          "message" to "No run id was reported",
          "issue_category" to "data_persistence",
          "location" to "src/Outbox.kt:12",
        ),
      )

    listOf(
      mapOf("produced_outputs" to mapOf("findings" to findings)),
      mapOf("produced_outputs" to mapOf("review_run_id" to "   ", "findings" to findings)),
    ).forEach { output ->
      val ledgerFindings =
        GoalSubtaskReviewSummaryReducer.unaddressedFindings(
          output = output,
          scope = UnaddressedFindingLedgerScope("SKILL-136", 6, "wf-1", 1),
        )

      val outcome = ledgerFindings.single().toOutcomeRecord(ReviewFindingOutcome.CARRIED)
      assertEquals(null, outcome.reviewRunId)
      assertEquals("unresolved", outcome.keyState)
    }
  }

  @Test
  fun `an unresolvable key is retained as unresolved rather than guessed`() {
    val outcomes =
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = emptyList(),
        currentFindings = listOf(finding(ordinal = 1, findingId = "F-001")),
        blockerDispositions = emptyList(),
      )

    val outcome = outcomes.single()
    assertEquals(null, outcome.reviewRunId, "The workflow loop imports no review run, so there is no key to record.")
    assertEquals("unresolved", outcome.keyState)
  }

  private fun finding(
    ordinal: Int,
    findingId: String?,
  ) = UnaddressedFinding(
    issueKey = "SKILL-136",
    subtaskId = 6,
    workflowId = "wf-1",
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = "major",
    issueCategory = "data_persistence",
    location = "src/File.kt:$ordinal",
    summary = "Finding $ordinal",
    findingId = findingId,
  )
}
