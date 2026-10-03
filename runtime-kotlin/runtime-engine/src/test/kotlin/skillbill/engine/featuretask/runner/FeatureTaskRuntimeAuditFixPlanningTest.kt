package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditFixPlanningTest {
  @Test
  fun `repair consumes a persisted plan before editing and audit runs again after execution`() {
    var audits = 0
    lateinit var harness: RunnerHarness
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "audit" ->
            facts(
              if (++audits == 1) auditRemainingAcOutput("AC-002: admission missing") else auditSatisfiedOutput(),
            )
          "audit_implement_fix" -> {
            val saved = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_plan_fix"))
            assertContains(assertNotNull(saved.outputArtifact), "Closure evidence:")
            assertContains(prompt, "### from: audit_plan_fix")
            assertContains(prompt, "Check admission inside the mutation transaction")
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    harness = runnerHarness(RuntimeHarnessConfig(launcher = launcher))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(
      listOf("audit", "audit_plan_fix", "audit_implement_fix", "audit"),
      harness.launchedPromptPhaseOrder().filter { it.startsWith("audit") },
    )
  }

  @Test
  fun `omitted criterion in repair plan prevents repair from starting`() {
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(auditRemainingAcOutput("AC-002: missing admission"))
          "audit_plan_fix" -> facts(auditRepairPlanFor(emptyList()))
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = launcher))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit_plan_fix", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "schema-invalid output")
    assertTrue("audit_implement_fix" !in harness.launchedPromptPhaseOrder())
  }
}
