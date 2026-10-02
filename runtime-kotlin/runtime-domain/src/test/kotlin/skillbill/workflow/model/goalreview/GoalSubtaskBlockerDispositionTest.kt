package skillbill.workflow.model.goalreview

import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalSubtaskBlockerDispositionTest {
  private fun reservedPassOne(): GoalSubtaskReviewState =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass()

  private fun disposition(
    id: String,
    verdict: GoalSubtaskBlockerDispositionVerdict,
  ) = GoalSubtaskBlockerDisposition(id, verdict, listOf("changed line settling $id"))

  @Test
  fun `every resolved blocker advances the child`() {
    val settled =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
        blockerDispositions =
          listOf(
            disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED),
            disposition("F-002", GoalSubtaskReviewDispositionFixtures.RESOLVED),
          ),
      )
    assertFalse(settled.pausedForOperatorDecision)
    assertFalse(settled.reviewCapReached)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, settled.passResults.last().verdict)
  }

  @Test
  fun `an unresolved blocker does not reserve a second review pass`() {
    val unresolved =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
        blockerDispositions =
          listOf(
            disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED),
            disposition("F-002", GoalSubtaskReviewDispositionFixtures.UNRESOLVED),
          ),
      )
    assertFalse(unresolved.reviewCapReached)
    assertEquals(1, unresolved.unresolvedBlockerDispositions.size)
    assertEquals(1, unresolved.completedPassCount)
    assertEquals(unresolved, unresolved.reserveNextPass())
  }

  @Test
  fun `dispositions and their evidence survive encode and decode`() {
    val settled =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
        blockerDispositions = listOf(disposition("F-002", GoalSubtaskReviewDispositionFixtures.UNRESOLVED)),
      )
    val reloaded = GoalSubtaskReviewState.fromArtifactMap(settled.toArtifactMap())
    assertEquals(settled.blockerDispositions, reloaded.blockerDispositions)
    assertEquals(settled.toArtifactMap(), reloaded.toArtifactMap())
    assertEquals(1, reloaded.completedPassCount)
    assertEquals(settled.reviewBaseSha, reloaded.reviewBaseSha)
    assertEquals(settled.baselineUntrackedPaths, reloaded.baselineUntrackedPaths)
  }

  @Test
  fun `an unevidenced disposition is rejected at the parse seam`() {
    assertFailsWith<IllegalArgumentException> {
      GoalSubtaskBlockerDisposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED, emptyList())
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskBlockerDisposition.fromArtifactMap(
        mapOf("finding_id" to "F-001", "verdict" to "resolved"),
        "blocker_dispositions[0]",
      )
    }
  }

  @Test
  fun `major findings stay out of disposition scope`() {
    val settled =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
        blockerDispositions = listOf(disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED)),
      )
    assertEquals(listOf("F-001"), settled.blockerDispositions.map { it.findingId })
    assertTrue(settled.passResults.last().findings.any { it.severity == "major" })
    assertFalse(settled.passResults.last().blocksAdvance, "blocksAdvance stays Blocker-only.")
  }

  @Test
  fun `the review result artifact still equals the prefix plus its exact pass number`() {
    val settled =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
        blockerDispositions = listOf(disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED)),
      )
    assertEquals(
      "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
      settled.passResults.last().reviewResultArtifact,
    )
    assertEquals(1, settled.completedPassCount)
  }

  @Test
  fun `the operator decision vocabulary remains decodable for legacy records`() {
    assertEquals(
      setOf("retry_fix", "accept_and_advance", "abandon_subtask"),
      GoalSubtaskOperatorDecision.entries.map { it.wireValue }.toSet(),
    )
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskOperatorDecision.fromWire("skip_review")
    }
  }

  @Test
  fun `the bounded disposition summary carries no location bearing evidence`() {
    val settled =
      reservedPassOne().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
        blockerDispositions =
          listOf(
            GoalSubtaskBlockerDisposition(
              "F-002",
              GoalSubtaskReviewDispositionFixtures.UNRESOLVED,
              listOf("src/main/kotlin/skillbill/Repo.kt:42 still mutates in place"),
            ),
          ),
      )
    val summary = settled.boundedDispositionSummary().toString()
    assertFalse(summary.contains("Repo.kt"), "No path may reach a goal-facing surface.")
    assertFalse(summary.contains(":42"), "No line number may reach a goal-facing surface.")
    assertTrue(summary.contains("unresolved"))
    assertTrue(
      settled.toArtifactMap().toString().contains("Repo.kt"),
      "Location-bearing evidence stays in the durable artifact for goal findings retrieval.",
    )
  }
}

private object GoalSubtaskReviewDispositionFixtures {
  val RESOLVED = GoalSubtaskBlockerDispositionVerdict.RESOLVED
  val UNRESOLVED = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED
}
