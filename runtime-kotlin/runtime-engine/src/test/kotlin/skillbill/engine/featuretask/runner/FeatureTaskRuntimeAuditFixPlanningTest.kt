package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
            assertContains(assertNotNull(saved.outputArtifact), "Check admission inside the mutation transaction")
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
  fun `free prose repair plan reaches implementation without headings or field labels`() {
    val prose =
      "First inspect the admission caller and preserve its completed edits. " +
        "Then add the production check before writes so AC-002 closes."
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "audit" ->
            facts(
              if (++audits == 1) auditRemainingAcOutput("AC-002: missing admission") else auditSatisfiedOutput(),
            )
          "audit_plan_fix" -> facts(auditRepairPlanProse(prose))
          "audit_implement_fix" -> {
            assertContains(prompt, prose)
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = launcher))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(
      listOf("audit", "audit_plan_fix", "audit_implement_fix", "audit"),
      harness.launchedPromptPhaseOrder().filter { it.startsWith("audit") },
    )
  }

  private fun auditRepairPlanProse(prose: String): String =
    """{
    |  "contract_version": "0.7",
    |  "phase_id": "audit_plan_fix",
    |  "status": "completed",
    |  "summary": "Repairs planned.",
    |  "produced_outputs": {"value": "$prose"}
    |}
    """.trimMargin()
}
