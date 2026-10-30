package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.preplan.PreplanTrackerBlockHooks
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimePreplanTrackerBlockTest {
  @Test
  fun `a preplan block on a failed tracker lookup retries with a correction instead of blocking`() {
    var preplanLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "preplan" && preplanLaunches++ == 0) {
          facts(TRACKER_BLOCKED_PREPLAN)
        } else {
          facts(validJsonOutput(phaseId))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          agentAssignment = phasePerAgentAssignment(),
          hydratedPlanning = false,
        ),
      )

    val report = harness.runner.run(harness.request())

    if (report is FeatureTaskRuntimeRunReport.Blocked) {
      assertNotEquals("preplan", report.lastIncompletePhase, report.blockedReason)
    }
    val preplanPrompts =
      launcher.requests
        .mapNotNull { it.skillRunRequest.promptOverride }
        .filter { phaseIdFromPrompt(it) == "preplan" }
    assertEquals(2, preplanPrompts.size)
    assertContains(preplanPrompts[1], "Do not call tracker tools")
  }

  @Test
  fun `only blocked outputs that rest on tracker data count as tracker blocks`() {
    assertTrue(PreplanTrackerBlockHooks.blocksOnTrackerData(blocked(LINEAR_LOOKUP_FAILURE)))
    assertTrue(PreplanTrackerBlockHooks.blocksOnTrackerData(blocked("The tracker is not connected.")))
    assertFalse(PreplanTrackerBlockHooks.blocksOnTrackerData(blocked("Upstream dependency was unavailable.")))
    assertFalse(
      PreplanTrackerBlockHooks.blocksOnTrackerData(
        FeatureTaskRuntimeWorkflowArtifactMap.from(mapOf("status" to "completed", "summary" to LINEAR_LOOKUP_FAILURE)),
      ),
    )
  }

  private fun blocked(summary: String): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(mapOf("status" to "blocked", "summary" to summary))
}

private const val LINEAR_LOOKUP_FAILURE =
  "The connected Linear workspace could not find SKILL-448: get_issue returned invalid_request."

private const val TRACKER_BLOCKED_PREPLAN =
  """{"contract_version":"0.2","phase_id":"preplan","status":"blocked",""" +
    """"summary":"$LINEAR_LOOKUP_FAILURE",""" +
    """"produced_outputs":{"blocking_reasons":["list_comments returned issue not found."]}}"""
