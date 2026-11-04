package skillbill.engine.operation.prreviewfix

import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import skillbill.review.diffcomment.model.DiffCommentThreadState
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrReviewFixOperationExecutionTest {
  @Test
  fun `local confirmation applies fixes replies with outcome and commits without pushing`() {
    PrReviewFixHarness().use { harness ->
      val threadId = harness.addLocalThread()
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token

      val completed =
        assertIs<OperationOutcome.Completed>(
          harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1")),
        )

      assertContains(completed.text, "Commit: ")
      assertContains(completed.text, "(not pushed)")
      assertEquals(DiffCommentThreadState.RESOLVED, harness.localThreadState(threadId))
      assertEquals("Address local diff comments T1 on ${PrReviewFixHarness.PR_BRANCH}", harness.latestCommitMessage())
      assertEquals(emptyList(), harness.pushes)
      assertEquals(emptyList(), harness.warnings)
    }
  }

  @Test
  fun `a saved failed outcome leaves the thread open and fails the operation`() {
    PrReviewFixHarness().use { harness ->
      val threadId = harness.addLocalThread()
      harness.runner.localReply = "outcome: failed\nThe requested fix could not be completed."
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token

      val failed =
        assertIs<OperationOutcome.Failed>(
          harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1")),
        )

      assertContains(failed.reason, "replied (failed -> open)")
      assertEquals(DiffCommentThreadState.OPEN, harness.localThreadState(threadId))
      assertContains(failed.reason, "(not pushed)")
      assertEquals(emptyList(), harness.pushes)
    }
  }

  @Test
  fun `an inferred local outcome records why the reply changes thread state`() {
    listOf(
      "Addressed the local comment." to "missing_outcome",
      "outcome: unknown\nAddressed the local comment." to "unknown_outcome",
    ).forEach { (reply, cause) ->
      PrReviewFixHarness().use { harness ->
        val threadId = harness.addLocalThread()
        harness.runner.localReply = reply
        val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token

        assertIs<OperationOutcome.Completed>(
          harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1")),
        )

        assertEquals(DiffCommentThreadState.RESOLVED, harness.localThreadState(threadId))
        assertEquals(
          listOf(
            "seam=local_review_reply_outcome value_expected=declared_outcome value_used=fixed cause=$cause",
          ),
          harness.warnings,
        )
      }
    }
  }

  @Test
  fun `a decorated or punctuated outcome line wins over the fixed fallback for a runner that edits a file`() {
    listOf(
      LocalReplyCase(
        "`outcome: failed`\nCould not finish.",
        "failed",
        DiffCommentThreadState.OPEN,
        true,
        "Could not finish.",
      ),
      LocalReplyCase(
        "outcome: refuted.\nThe comment is mistaken.",
        "refuted",
        DiffCommentThreadState.DECLINED,
        false,
        "The comment is mistaken.",
      ),
      LocalReplyCase(
        "**Outcome:** Skipped\nLeft alone.",
        "skipped",
        DiffCommentThreadState.OPEN,
        false,
        "Left alone.",
      ),
      LocalReplyCase(
        "```\noutcome: fixed\nRenamed it.",
        "fixed",
        DiffCommentThreadState.RESOLVED,
        false,
        "Renamed it.",
      ),
      LocalReplyCase(
        "outcome: failed — partial edit could not compile",
        "failed",
        DiffCommentThreadState.OPEN,
        true,
        "partial edit could not compile",
      ),
    ).forEach { case ->
      PrReviewFixHarness().use { harness ->
        val threadId = harness.addLocalThread()
        harness.runner.localReply = case.reply
        val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token

        val outcome = harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1"))

        if (case.failsOperation) {
          assertContains(assertIs<OperationOutcome.Failed>(outcome).reason, "replied (${case.wire} -> open)")
        } else {
          assertContains(assertIs<OperationOutcome.Completed>(outcome).text, "replied (${case.wire} -> ")
        }
        assertEquals(case.state, harness.localThreadState(threadId), case.reply)
        assertEquals(case.body, harness.localThreadReplyBody(threadId), case.reply)
        assertEquals(emptyList(), harness.warnings, case.reply)
      }
    }
  }

  @Test
  fun `a copied outcome template is an unknown outcome and applies the fallback`() {
    PrReviewFixHarness().use { harness ->
      val threadId = harness.addLocalThread()
      harness.runner.localReply = "outcome: fixed|refuted|skipped|failed\nAddressed the local comment."
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token

      assertIs<OperationOutcome.Completed>(
        harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1")),
      )

      assertEquals(DiffCommentThreadState.RESOLVED, harness.localThreadState(threadId))
      assertEquals(
        listOf(
          "seam=local_review_reply_outcome value_expected=declared_outcome value_used=fixed cause=unknown_outcome",
        ),
        harness.warnings,
      )
      assertEquals("Addressed the local comment.", harness.localThreadReplyBody(threadId))
    }
  }

  @Test
  fun `a thread closed after admission is not edited or replied to`() {
    PrReviewFixHarness().use { harness ->
      val threadId = harness.addLocalThread()
      val token = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invokeLocal()).token
      harness.afterWorktreeStatus = { harness.closeLocalThread(threadId) }

      val blocked =
        assertIs<OperationOutcome.Blocked>(
          harness.invokeLocal(OperationArguments(confirm = token, select = "T1=1")),
        )

      assertContains(blocked.reason, "is no longer open")
      assertEquals(listOf(ANALYSIS_STEP), harness.runner.inputs.map { it.stepName })
      assertEquals(DiffCommentThreadState.DECLINED, harness.localThreadState(threadId))
      assertEquals(emptyList(), harness.validations)
      assertEquals("initial", harness.latestCommitMessage())
      assertEquals("", harness.status())
    }
  }

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

  private data class LocalReplyCase(
    val reply: String,
    val wire: String,
    val state: DiffCommentThreadState,
    val failsOperation: Boolean,
    val body: String,
  )
}
