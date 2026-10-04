package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusSelectionTier
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeStatusSelectionPolicyTest {
  private companion object {
    val OBSERVED: Instant = Instant.parse("2026-08-06T12:00:00Z")
  }

  @Test
  fun `active outranks paused blocked failed and terminal competitors`() {
    val winner = candidate("active", IdeStatusLifecycleState.ACTIVE, "w-active", "2026-08-06T10:00:00Z")
    val competitors =
      listOf(
        candidate("paused", IdeStatusLifecycleState.PAUSED, "w-paused", "2026-08-06T11:00:00Z"),
        candidate("blocked", IdeStatusLifecycleState.BLOCKED, "w-blocked", "2026-08-06T11:00:00Z"),
        candidate("failed", IdeStatusLifecycleState.FAILED, "w-failed", "2026-08-06T11:00:00Z"),
        candidate("terminal", IdeStatusLifecycleState.TERMINAL, "w-terminal", "2026-08-06T11:00:00Z"),
        winner,
      )
    assertEquals("w-active", IdeStatusSelectionPolicy.select(competitors, OBSERVED)?.workflowId)
  }

  @Test
  fun `paused outranks blocked failed and terminal`() {
    val winner = candidate("paused", IdeStatusLifecycleState.PAUSED, "w-paused", "2026-08-06T10:00:00Z")
    val competitors =
      listOf(
        candidate("blocked", IdeStatusLifecycleState.BLOCKED, "w-blocked", "2026-08-06T11:00:00Z"),
        candidate("failed", IdeStatusLifecycleState.FAILED, "w-failed", "2026-08-06T11:00:00Z"),
        candidate("terminal", IdeStatusLifecycleState.TERMINAL, "w-terminal", "2026-08-06T11:00:00Z"),
        winner,
      )
    assertEquals("w-paused", IdeStatusSelectionPolicy.select(competitors, OBSERVED)?.workflowId)
  }

  @Test
  fun `feature-goal outranks child runtime for the same issue within a tier`() {
    val goal =
      IdeStatusCandidate(
        workflowId = "goal-1",
        workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
        issueKey = "SKILL-148",
        currentState = "running",
        lifecycleState = IdeStatusLifecycleState.ACTIVE,
        selectionTier = IdeStatusSelectionTier.ACTIVE,
        updatedAt = Instant.parse("2026-08-06T11:50:00Z"),
        startedAt = Instant.parse("2026-08-06T08:00:00Z"),
        isGoalAuthoritative = true,
      )
    val child =
      IdeStatusCandidate(
        workflowId = "runtime-child",
        workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
        issueKey = "SKILL-148",
        currentState = "running",
        lifecycleState = IdeStatusLifecycleState.ACTIVE,
        selectionTier = IdeStatusSelectionTier.ACTIVE,
        updatedAt = Instant.parse("2026-08-06T12:00:00Z"),
        startedAt = Instant.parse("2026-08-06T11:00:00Z"),
        isGoalAuthoritative = false,
      )
    assertEquals("goal-1", IdeStatusSelectionPolicy.select(listOf(child, goal), OBSERVED)?.workflowId)
  }

  @Test
  fun `within a tier more recent updated_at wins then workflow_id lexicographic order`() {
    val older = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-b", "2026-08-06T10:00:00Z")
    val newer = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-a", "2026-08-06T11:00:00Z")
    assertEquals("w-a", IdeStatusSelectionPolicy.select(listOf(older, newer), OBSERVED)?.workflowId)

    val tieA = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-a", "2026-08-06T11:00:00Z")
    val tieB = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-b", "2026-08-06T11:00:00Z")
    assertEquals("w-a", IdeStatusSelectionPolicy.select(listOf(tieB, tieA), OBSERVED)?.workflowId)
  }

  @Test
  fun `empty candidate list yields null`() {
    assertNull(IdeStatusSelectionPolicy.select(emptyList(), OBSERVED))
  }

  @Test
  fun `failed and terminal work stops being selectable once it ages out`() {
    for (lifecycle in listOf(IdeStatusLifecycleState.FAILED, IdeStatusLifecycleState.TERMINAL)) {
      val reported = candidate("a", lifecycle, "w-reported", "2026-08-06T09:00:00Z")
      assertEquals("w-reported", IdeStatusSelectionPolicy.select(listOf(reported), OBSERVED)?.workflowId)

      val settled = candidate("b", lifecycle, "w-settled", "2026-08-06T05:00:00Z")
      assertNull(IdeStatusSelectionPolicy.select(listOf(settled), OBSERVED))
    }
  }

  @Test
  fun `settled work outlives its fresh window so a stale reading can still be reported`() {
    val staleAge = IdeStatusFreshnessClassifier.FRESH_WINDOW.plusMinutes(1)
    for (lifecycle in listOf(
      IdeStatusLifecycleState.BLOCKED,
      IdeStatusLifecycleState.FAILED,
      IdeStatusLifecycleState.TERMINAL,
    )) {
      val updatedAt = OBSERVED.minus(staleAge)
      val settled = candidate("a", lifecycle, "w-stale", updatedAt.toString())
      assertEquals("w-stale", IdeStatusSelectionPolicy.select(listOf(settled), OBSERVED)?.workflowId)
      assertEquals(
        IdeStatusFreshness.STALE,
        IdeStatusFreshnessClassifier.classify(updatedAt, OBSERVED),
      )
    }
  }

  @Test
  fun `blocked work waiting on the user outlives the settled ceiling`() {
    val waiting = candidate("a", IdeStatusLifecycleState.BLOCKED, "w-waiting", "2026-08-06T01:00:00Z")
    assertEquals("w-waiting", IdeStatusSelectionPolicy.select(listOf(waiting), OBSERVED)?.workflowId)
  }

  @Test
  fun `a days-old blocked goal never occupies the surface`() {
    val abandoned = candidate("a", IdeStatusLifecycleState.BLOCKED, "w-161", "2026-08-04T20:07:54Z")
    assertNull(IdeStatusSelectionPolicy.select(listOf(abandoned), OBSERVED))
  }

  @Test
  fun `live work survives a long quiet phase but not an abandoned day`() {
    val quiet = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-quiet", "2026-08-06T00:00:00Z")
    assertEquals("w-quiet", IdeStatusSelectionPolicy.select(listOf(quiet), OBSERVED)?.workflowId)

    val abandonedActive = candidate("b", IdeStatusLifecycleState.ACTIVE, "w-dead", "2026-08-01T00:00:00Z")
    val abandonedPaused = candidate("c", IdeStatusLifecycleState.PAUSED, "w-dead-p", "2026-08-01T00:00:00Z")
    assertNull(IdeStatusSelectionPolicy.select(listOf(abandonedActive), OBSERVED))
    assertNull(IdeStatusSelectionPolicy.select(listOf(abandonedPaused), OBSERVED))
  }

  @Test
  fun `a finished goal still claiming running loses to the work that is moving`() {
    val finishedClaimingRunning =
      candidate("SKILL-190", IdeStatusLifecycleState.ACTIVE, "w-190", "2026-08-06T02:00:00Z")
    val live = candidate("SKILL-201", IdeStatusLifecycleState.BLOCKED, "w-201", "2026-08-06T11:58:00Z")

    assertEquals("w-201", IdeStatusSelectionPolicy.select(listOf(finishedClaimingRunning, live), OBSERVED)?.workflowId)
  }

  @Test
  fun `a live run quiet inside the fresh window keeps its tier lead`() {
    val quietButFresh = candidate("SKILL-201", IdeStatusLifecycleState.ACTIVE, "w-201", "2026-08-06T11:45:00Z")
    val settled = candidate("SKILL-190", IdeStatusLifecycleState.BLOCKED, "w-190", "2026-08-06T11:59:00Z")

    assertEquals("w-201", IdeStatusSelectionPolicy.select(listOf(settled, quietButFresh), OBSERVED)?.workflowId)
  }

  @Test
  fun `stale work is still selectable when nothing fresher exists`() {
    val stale = candidate("SKILL-190", IdeStatusLifecycleState.ACTIVE, "w-190", "2026-08-06T02:00:00Z")

    assertEquals("w-190", IdeStatusSelectionPolicy.select(listOf(stale), OBSERVED)?.workflowId)
  }

  @Test
  fun `clock skew never drops work from selection`() {
    val future = candidate("a", IdeStatusLifecycleState.BLOCKED, "w-future", "2026-09-01T00:00:00Z")
    assertEquals("w-future", IdeStatusSelectionPolicy.select(listOf(future), OBSERVED)?.workflowId)
  }

  private fun candidate(
    issueKey: String,
    lifecycle: IdeStatusLifecycleState,
    workflowId: String,
    updatedAt: String,
  ): IdeStatusCandidate =
    IdeStatusCandidate(
      workflowId = workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      issueKey = issueKey,
      currentState = lifecycle.wireValue,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = Instant.parse(updatedAt),
      startedAt = Instant.parse("2026-08-06T08:00:00Z"),
      isGoalAuthoritative = false,
    )
}
