package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeCorrectiveRespawnIntegrationTest {
  @Test
  fun `retryable terminal audit blocks after one agent session`() {
    var auditLaunches = 0
    val retryableFailure =
      """
      {
        "contract_version":"0.2",
        "phase_id":"audit",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"SKILL187-TERMINAL-BLOCK",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
      """.trimIndent()
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              if (phaseId == "audit") auditLaunches += 1
              facts(if (phaseId == "audit" && auditLaunches == 1) retryableFailure else defaultPhaseOutput(request))
            },
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(1, auditLaunches)
    assertEquals(1, auditPrompts(harness).size)
  }

  private fun auditPrompts(harness: RunnerHarness): List<String> =
    harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }
}
