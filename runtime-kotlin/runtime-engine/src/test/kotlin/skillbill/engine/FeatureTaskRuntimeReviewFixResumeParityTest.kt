package skillbill.engine

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewFixResumeParityTest {
  @Test
  fun `resumed review_fix re-entry without a completed review is discarded and review runs first`() {
    var reviews = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = satisfiedAuditLauncher(),
          reviewRunner =
            scriptedReviewPhaseRunner {
              reviews += 1
              "verdict: approved"
            },
        ),
      )
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
    harness.seedLoopEdge(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
      edgeIteration = 1,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, reviews)
    assertEquals(0, harness.launchedPromptPhaseOrder().count { it == "implement_fix" })
    assertTrue("review" in harness.launchOrder())
    assertEquals(
      1,
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      },
    )
  }
}
