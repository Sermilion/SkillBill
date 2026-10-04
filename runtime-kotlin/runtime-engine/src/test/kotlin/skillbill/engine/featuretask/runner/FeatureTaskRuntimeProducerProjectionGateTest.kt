
package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.validJsonOutput
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeProducerProjectionGateTest {
  @Test
  fun `a blocked producing-phase output settles terminally at its own phase`() {
    listOf("preplan", "plan", "implement").forEach { targetPhase ->
      val outcome = runTerminalProducer(targetPhase, terminalProducerOutput(targetPhase, status = "blocked"))

      assertEquals(targetPhase, outcome.blocked.lastIncompletePhase, "$targetPhase must settle at its own phase")
      assertContains(outcome.blocked.blockedReason, "status 'blocked'")
      assertContains(outcome.blocked.blockedReason, TERMINAL_BLOCKING_REASON)
    }
  }

  @Test
  fun `a failed producing-phase output settles terminally at its own phase through the block tool`() {
    listOf("preplan", "plan", "implement").forEach { targetPhase ->
      val outcome = runTerminalProducer(targetPhase, terminalProducerOutput(targetPhase, status = "failed"))

      assertEquals(targetPhase, outcome.blocked.lastIncompletePhase)
      assertContains(outcome.blocked.blockedReason, TERMINAL_BLOCKING_REASON)
    }
  }

  private fun runTerminalProducer(
    targetPhase: String,
    terminalOutput: String,
  ): ProducerBlockOutcome {
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              facts(if (phaseId == targetPhase) terminalOutput else validJsonOutput(phaseId))
            },
          agentAssignment = phasePerAgentAssignment(),
        ),
      )
    val report = harness.runner.run(harness.request())
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    return ProducerBlockOutcome(blocked)
  }

  private class ProducerBlockOutcome(
    val blocked: FeatureTaskRuntimeRunReport.Blocked,
  )
}

private const val TERMINAL_BLOCKING_REASON = "Upstream dependency was unavailable."

private fun terminalProducerOutput(
  phaseId: String,
  status: String,
): String {
  val reconciled = if (phaseId == "implement") ""","reconciled_state":{"reconciled":true}""" else ""
  return """{"contract_version":"0.2","phase_id":"$phaseId","status":"$status",""" +
    """"failure_disposition":"non_retryable_policy_conflict","summary":"Producer could not finish.",""" +
    """"produced_outputs":{"blocking_reasons":["$TERMINAL_BLOCKING_REASON"],""" +
    """"free_form":"not a projection"$reconciled}}"""
}
