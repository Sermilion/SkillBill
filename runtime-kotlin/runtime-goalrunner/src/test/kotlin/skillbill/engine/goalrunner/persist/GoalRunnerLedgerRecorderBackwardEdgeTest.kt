package skillbill.engine.goalrunner.persist

import skillbill.application.testHarnessClock
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerLedgerRecorderBackwardEdgeTest {
  private fun recorder(outcomes: RecordingOutcomeStore): GoalRunnerLedgerRecorder =
    GoalRunnerLedgerRecorder(
      outcomes,
      GoalRunnerRunRequest(
        issueKey = "SKILL-142",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = "claude",
      ),
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )

  @Test
  fun `backward edge cumulative count advances by the child edge iteration not one`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = recorder(outcomes)

    recorder.recordBackwardEdgeEntry(
      GoalRunnerBackwardEdge(
        workflowId = "wfl-1",
        issueKey = "SKILL-142",
        subtaskId = 1,
        loopId = "regenerate_implement",
        edgeIteration = 2,
        progress = null,
      ),
    )

    val entry = outcomes.attemptLedgerEntries.single()
    assertEquals(
      2,
      entry.cumulativeLoopCount,
      "a child run that fired a regeneration edge twice must record cumulative_loop_count=2, not 1",
    )
  }

  @Test
  fun `repeated backward edge entries accumulate across calls`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = recorder(outcomes)

    val edge = GoalRunnerBackwardEdge("wfl-1", "SKILL-142", 1, "review_fix", 1, null)
    recorder.recordBackwardEdgeEntry(edge)
    recorder.recordBackwardEdgeEntry(edge)

    val counts = outcomes.attemptLedgerEntries.map { it.cumulativeLoopCount }
    assertEquals(listOf(1, 2), counts)
  }

  @Test
  fun `watermark seed composes with the child edge iteration on resume`() {
    val outcomes = RecordingOutcomeStore()
    outcomes.ledgerSequenceWatermarks =
      GoalRunnerLedgerSequenceWatermarks(
        backwardEdgeCounts = mapOf("1:regenerate_implement" to 3),
      )
    val recorder = recorder(outcomes)

    recorder.recordBackwardEdgeEntry(
      GoalRunnerBackwardEdge(
        workflowId = "wfl-1",
        issueKey = "SKILL-142",
        subtaskId = 1,
        loopId = "regenerate_implement",
        edgeIteration = 2,
        progress = null,
      ),
    )

    val entry = outcomes.attemptLedgerEntries.single()
    assertEquals(
      5,
      entry.cumulativeLoopCount,
      "a seeded watermark of 3 plus a child edge iteration of 2 must compose to 5",
    )
  }

  @Test
  fun `policy block ledger action cannot inherit launch-only fields`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = recorder(outcomes)

    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext.ChildActivation(
        workflowId = "wfl-1",
        issueKey = "SKILL-142",
        subtaskId = 1,
        progress = null,
        launchOutcome = null,
        diagnosticClass = "child_process_failed",
        recoverableJsonPresent = true,
        nextSafeAction = "resume",
        causingLoopEntry = null,
        reAttemptCause = null,
      ),
    )
    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext.PolicyBlock(
        workflowId = "wfl-1",
        issueKey = "SKILL-142",
        subtaskId = 1,
        progress = null,
        blockedReason = "policy denied",
        stopReason = null,
      ),
    )

    val blocked = outcomes.attemptLedgerEntries.last()
    assertEquals("policy denied", blocked.blockedReason)
    assertNull(blocked.launchOutcome)
    assertNull(blocked.childSessionId)
    assertNull(blocked.exitStatus)
    assertNull(blocked.recoverableJsonPresent)
  }
}
