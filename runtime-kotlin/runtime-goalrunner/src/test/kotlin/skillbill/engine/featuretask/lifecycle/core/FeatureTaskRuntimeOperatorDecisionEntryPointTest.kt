package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.goalrunner.GoalOperatorDecisionService
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.noChangePause
import skillbill.engine.goalrunner.withWorkflowId
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeOperatorDecisionEntryPointTest {
  @Test
  fun `every declared decision has a stable wire value the CLI can parse`() {
    assertEquals(
      setOf("retry_fix", "accept_and_advance", "abandon_subtask"),
      GoalSubtaskOperatorDecision.entries.map { it.wireValue }.toSet(),
      "The CLI parses --operator-decision against exactly this vocabulary.",
    )
  }

  @Test
  fun `a reopened phase record projects onto a pending step instead of failing the projection`() {
    val reopened =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        attemptCount = 13,
        startedAt = "2026-08-19T20:07:08Z",
        finishedAt = "2026-08-19T21:42:42Z",
        resolvedAgentId = "cursor",
        blockedReason = "an operator decision is required before implementation",
      ).asPendingForOperatorResume()

    val projected = stepUpdatesFrom(mapOf(reopened.phaseId to reopened)).single()

    assertEquals("implement_fix", projected.stepId)
    assertEquals("pending", projected.status, "a reopened phase is unstarted work, not completed work")
  }

  @Test
  fun `a decision on a child without a no-change pause keeps the review-remediation rejection`() {
    val service =
      GoalOperatorDecisionService(pausedChildStore(), RecordingOutcomeStore(), goalRunnerDefaultPhaseRecorder())

    val rejected =
      assertIs<GoalRunnerOperatorDecisionResult.Rejected>(
        service.record(decisionRequest(GoalSubtaskOperatorDecision.RETRY_FIX, instructions = "Retry the guard.")),
      )

    assertTrue(rejected.reason.startsWith("Operator decisions over review remediation are removed"), rejected.reason)
  }

  @Test
  fun `retry_fix on a no-change pause without instructions is rejected and leaves the pause undecided`() {
    val recorder = pausedChildRecorder()
    val service = GoalOperatorDecisionService(pausedChildStore(), RecordingOutcomeStore(), recorder)

    val rejected =
      assertIs<GoalRunnerOperatorDecisionResult.Rejected>(
        service.record(decisionRequest(GoalSubtaskOperatorDecision.RETRY_FIX, instructions = "   ")),
      )

    assertTrue(rejected.reason.contains("needs non-blank operator instructions"), rejected.reason)
    assertNull(recorder.loadNoChangePause("wfl-1")?.operatorDecision)
  }

  @Test
  fun `retry_fix with instructions records the trimmed instructions on the pause`() {
    val recorder = pausedChildRecorder()
    val service = GoalOperatorDecisionService(pausedChildStore(), RecordingOutcomeStore(), recorder)

    val recorded =
      assertIs<GoalRunnerOperatorDecisionResult.Recorded>(
        service.record(
          decisionRequest(GoalSubtaskOperatorDecision.RETRY_FIX, instructions = "  Re-check the guard.  "),
        ),
      )

    assertEquals("wfl-1", recorded.workflowId)
    assertEquals("retry_fix", recorder.loadNoChangePause("wfl-1")?.operatorDecision)
    assertEquals("Re-check the guard.", recorder.loadNoChangePause("wfl-1")?.operatorInstructions)
  }

  @Test
  fun `instructions are rejected for a no-change decision other than retry_fix`() {
    val recorder = pausedChildRecorder()
    val service = GoalOperatorDecisionService(pausedChildStore(), RecordingOutcomeStore(), recorder)

    val rejected =
      assertIs<GoalRunnerOperatorDecisionResult.Rejected>(
        service.record(decisionRequest(GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE, instructions = "Advance now.")),
      )

    assertTrue(rejected.reason.contains("applies only to retry_fix"), rejected.reason)
    assertNull(recorder.loadNoChangePause("wfl-1")?.operatorDecision)
  }

  private fun decisionRequest(
    decision: GoalSubtaskOperatorDecision,
    instructions: String? = null,
  ): GoalRunnerOperatorDecisionRequest =
    GoalRunnerOperatorDecisionRequest(
      issueKey = "SKILL-56",
      subtaskId = 1,
      decision = decision,
      instructions = instructions,
    )

  private fun pausedChildStore(): InMemoryGoalManifestStore =
    InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1).withWorkflowId(1, "wfl-1"))

  private fun pausedChildRecorder(): FeatureTaskRuntimePhaseRecorder =
    goalRunnerDefaultPhaseRecorder().also { recorder ->
      recorder.openTestWorkflow("wfl-1", "goal-child-no-change")
      recorder.persistNoChangePause("wfl-1", noChangePause())
    }
}
