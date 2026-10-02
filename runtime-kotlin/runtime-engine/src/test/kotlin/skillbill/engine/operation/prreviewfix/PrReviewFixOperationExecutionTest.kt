package skillbill.engine.operation.prreviewfix

import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrReviewFixOperationExecutionTest {
  @Test
  fun `confirming one thread fixes and replies to only that thread and pushes nothing without push on`() {
    PrReviewFixHarness().use { harness ->
      val token = harness.analyze()

      assertIs<OperationOutcome.Completed>(harness.invoke(OperationArguments(confirm = token, select = "T2=1")))

      assertEquals(listOf(ANALYSIS_STEP, THREAD_STEP), harness.runner.inputs.map { input -> input.stepName })
      assertContains(harness.runner.inputs.last().directive, "Selected thread: T2 (PRRT_b) at b.kt:4")
      assertContains(harness.runner.inputs.last().directive, "## Phase 2 — Execution")
      assertEquals(listOf("PRRT_b" to "Renamed as asked in T2."), harness.github.replies)
      assertEquals(1, harness.validations.size)
      assertEquals(emptyList(), harness.pushes)
    }
  }

  @Test
  fun `a thread that re-edits a file an earlier thread changed still lists it among its changed files`() {
    PrReviewFixHarness().use { harness ->
      harness.runner.editSharedFile = true
      val token = harness.analyze()

      val completed =
        assertIs<OperationOutcome.Completed>(
          harness.invoke(OperationArguments(confirm = token, select = "all-recommended")),
        )

      assertContains(completed.text, "| T2 (PRRT_b) | fix-T2.txt, shared.txt | 1 |")
    }
  }

  @Test
  fun `a confirm without a selection or naming no actionable thread runs nothing and keeps the token`() {
    listOf(
      null to "Operation 'pr-review-fix' needs a selection with confirm:; pass $SELECTION_FORMS.",
      "PRRT_resolved=1" to "Selection 'PRRT_resolved=1' is invalid:",
    ).forEach { (select, expected) ->
      PrReviewFixHarness().use { harness ->
        val token = harness.analyze()

        val usage =
          assertIs<OperationOutcome.Usage>(harness.invoke(OperationArguments(confirm = token, select = select)))

        assertContains(usage.reason, expected, message = "select=$select")
        assertEquals(listOf(ANALYSIS_STEP), harness.runner.inputs.map { input -> input.stepName })
        assertEquals(emptyList(), harness.github.replies)
        assertEquals(emptyList(), harness.validations)
        assertNull(assertNotNull(harness.proposal(token)).consumedAt)
      }
    }
  }

  @Test
  fun `a moved PR head or a new unresolved thread refuses the token before anything runs`() {
    val moves: List<(FakeReviewThreads) -> Unit> =
      listOf(
        { github -> github.headOid = "head-2" },
        { github -> github.threads += FakeReviewThreads.thread("PRRT_new", "d.kt", 1) },
      )
    moves.forEach { move ->
      PrReviewFixHarness().use { harness ->
        val token = harness.analyze()
        move(harness.github)

        val refused =
          assertIs<OperationOutcome.Blocked>(
            harness.invoke(OperationArguments(confirm = token, select = "all-recommended")),
          )

        assertContains(refused.reason, "stale")
        assertEquals(listOf(ANALYSIS_STEP), harness.runner.inputs.map { input -> input.stepName })
        assertEquals(emptyList(), harness.github.replies)
        assertNull(assertNotNull(harness.proposal(token)).consumedAt)
      }
    }
  }

  @Test
  fun `push on over a dirty worktree refuses before anything runs and keeps the token`() {
    PrReviewFixHarness().use { harness ->
      val token = harness.analyze()
      harness.repo.resolve("wip.txt").writeText("unrelated work in progress")

      val refused =
        assertIs<OperationOutcome.Blocked>(
          harness.invoke(OperationArguments(confirm = token, select = "all-recommended", push = "on")),
        )

      assertContains(refused.reason, "uncommitted changes")
      assertEquals(listOf(ANALYSIS_STEP), harness.runner.inputs.map { input -> input.stepName })
      assertEquals(emptyList(), harness.pushes)
      assertEquals("?? wip.txt", harness.status())
      assertNull(assertNotNull(harness.proposal(token)).consumedAt)
    }
  }

  @Test
  fun `a blocked quality gate posts no reply and pushes nothing`() {
    PrReviewFixHarness().use { harness ->
      val token = harness.analyze()
      harness.validationBlocks = true

      assertIs<OperationOutcome.Failed>(
        harness.invoke(OperationArguments(confirm = token, select = "all-recommended", push = "on")),
      )

      assertEquals(1, harness.validations.size)
      assertEquals(emptyList(), harness.github.replies)
      assertEquals(emptyList(), harness.pushes)
    }
  }

  private fun PrReviewFixHarness.analyze(): String = assertIs<OperationOutcome.AwaitingConfirmation>(invoke()).token
}
