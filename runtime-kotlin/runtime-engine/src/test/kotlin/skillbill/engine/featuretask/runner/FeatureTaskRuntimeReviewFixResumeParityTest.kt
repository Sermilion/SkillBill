package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.REVIEW_FIX_BLOCKER_FINDING_ID
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

  @Test
  fun `resuming an implement_fix interrupted inside review_fix keeps verify_findings settled and relaunches the fix`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, settledAuditSatisfiedRecord())
    harness.seedReviewPhase("completed", 1, REVIEW_WITH_BLOCKER_OUTPUT, 1)
    harness.seedPhase("verify_findings", "completed", 1, INVOKED_AGENT, VERIFIED_BLOCKER_OUTPUT)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        status = "running",
        attemptCount = 1,
        resolvedAgentId = INVOKED_AGENT,
        finished = false,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
        edgeIteration = 1,
      ),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement_fix" })
    assertEquals(0, harness.launchedPromptPhaseOrder().count { it == "verify_findings" })
  }
}

private val REVIEW_WITH_BLOCKER_OUTPUT =
  """
  {
    "contract_version": "0.7",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "produced_outputs": {
      "value": "$REVIEW_FIX_BLOCKER_FINDING_ID [blocker] src/Foo.kt:1 $REVIEW_BLOCKER_MESSAGE",
      "findings": [{
        "severity": "blocker",
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "message": "$REVIEW_BLOCKER_MESSAGE",
        "location": "src/Foo.kt:1"
      }],
      "blocker_dispositions": []
    }
  }
  """.trimIndent()

private val VERIFIED_BLOCKER_OUTPUT =
  """
  {
    "contract_version": "0.7",
    "phase_id": "verify_findings",
    "status": "completed",
    "summary": "Verified the blocker and carried it into repair.",
    "produced_outputs": {
      "value": "$REVIEW_FIX_BLOCKER_FINDING_ID verified: $REVIEW_BLOCKER_MESSAGE",
      "finding_dispositions": [{
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "disposition": "verified",
        "boundary_context_unavailable": true
      }]
    }
  }
  """.trimIndent()
