package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeRunInvariantsResumeParityTest {
  @Test
  fun `resume keeps the durable run invariants instead of re-freezing the requested ones`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
    val frozen =
      harness.request().runInvariants.copy(
        acceptanceCriteria = listOf("AC-frozen-1"),
        mandatesAndOverrides = listOf("mandate-frozen"),
      )
    harness.runInvariantsStore.resolve(WORKFLOW_ID, frozen)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val durable = requireNotNull(harness.runInvariantsStore.resolve(WORKFLOW_ID))
    assertEquals(listOf("AC-frozen-1"), durable.acceptanceCriteria)
    assertEquals(listOf("mandate-frozen"), durable.mandatesAndOverrides)
    val auditPrompt =
      harness.launcher.requests
        .map { request -> requireNotNull(request.skillRunRequest.promptOverride) }
        .single { prompt -> phaseIdFromPrompt(prompt) == "audit" }
    assertTrue("AC-001. AC-frozen-1" in auditPrompt)
    assertTrue("  - mandate-frozen" in auditPrompt)
    assertFalse("AC-001. AC-1" in auditPrompt)
    assertFalse("mandate-X" in auditPrompt)
  }
}
