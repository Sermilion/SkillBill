package skillbill.engine.featuretask.runner

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeProjectionRejectionTest {
  @Test
  fun `implement prose missing value blocks audit with a malformed-field reason`() {
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
            RuntimeRecordingLauncher { request ->
              facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
            },
          agentAssignment = phasePerAgentAssignment(),
        ),
      )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    val legacyImplementation =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"implement",""" +
        """"status":"completed","summary":"Legacy impl.",""" +
        """"produced_outputs":{"steps":["did the thing"],"narration":"free-form legacy body"}}"""
    harness.seedPhase(
      "implement",
      "completed",
      1,
      phaseAgent("implement"),
      legacyImplementation,
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "must contain non-blank prose for phase handoff")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "audit" })
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
  }

  @Test
  fun `a legacy handoff-envelope launch-seam block stays durably blocked on resume`() {
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
            RuntimeRecordingLauncher { request ->
              facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
            },
          agentAssignment = phasePerAgentAssignment(),
        ),
      )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), validJsonOutput("implement"))
    harness.seedBlockedPhase(
      "audit",
      1,
      phaseAgent("audit"),
      "Feature-task-runtime phase 'audit' rejected a durable handoff envelope at the launch seam: " +
        "stale briefing row.",
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "handoff envelope")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "audit" },
      "a non-record-rejection block is never re-entered",
    )
  }

  private fun preplanEnvelope(value: String = "Fixture preplan prose."): String =
    """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan",""" +
      """"status":"completed","summary":"Prose.",""" +
      """"produced_outputs":{"value":"$value"}}"""
}
