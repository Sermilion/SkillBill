package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeGoalStartBaselineTest {
  @Test
  fun `goal start baseline is the earliest goal child that recorded a resolved branch`() {
    val harness = runnerHarness()
    harness.repository.seedGoalChildWorkflowIds(
      PARENT_WORKFLOW_ID,
      listOf(UNSTARTED_CHILD, FIRST_CHILD, RETRIED_CHILD),
    )
    harness.recorder.openTestWorkflow(UNSTARTED_CHILD, SESSION_ID)
    seedBaseline(harness, FIRST_CHILD, listOf("operator/Notes.md"))
    seedBaseline(harness, RETRIED_CHILD, listOf("operator/Notes.md", "src/LeftoverFromEarlierAttempt.kt"))

    val goalStart = harness.recorder.loadGoalStartResolvedBranch(PARENT_WORKFLOW_ID)

    assertEquals(listOf("operator/Notes.md"), goalStart?.baselineOwnedPaths)
  }

  @Test
  fun `goal start baseline is absent when no goal child recorded a resolved branch`() {
    val harness = runnerHarness()
    harness.repository.seedGoalChildWorkflowIds(PARENT_WORKFLOW_ID, listOf(UNSTARTED_CHILD))
    harness.recorder.openTestWorkflow(UNSTARTED_CHILD, SESSION_ID)

    assertNull(harness.recorder.loadGoalStartResolvedBranch(PARENT_WORKFLOW_ID))
  }

  private fun seedBaseline(
    harness: RunnerHarness,
    workflowId: String,
    baselineOwnedPaths: List<String>,
  ) {
    harness.recorder.openTestWorkflow(workflowId, SESSION_ID)
    harness.recorder.recordResolvedBranch(
      workflowId,
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-1-goal",
        reviewBaseSha = "0".repeat(40),
        baselineOwnedPaths = baselineOwnedPaths,
      ),
    )
  }

  private companion object {
    const val PARENT_WORKFLOW_ID = "wfgr-20260927-000000-prnt"
    const val UNSTARTED_CHILD = "wftr-20260927-000001-none"
    const val FIRST_CHILD = "wftr-20260927-000002-frst"
    const val RETRIED_CHILD = "wftr-20260927-000003-rtry"
  }
}
