package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeBlockedRepairTest {
  @Test
  fun `a non-retryable worker block charges once and replans the same criterion without rejection`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    var repairs = 0
    var plans = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" -> {
              audits++
              continuingAuditOutput(state, audits).replace(
                "AC-001.G1: Missing production caller @ src/Foo.kt:1",
                "AC-001.G1: Missing production caller @ src/Foo.kt:1\\n" +
                  "AC-001.G2: Missing recovery path @ src/Foo.kt:2",
              )
            }
            "audit_plan_fix" -> {
              plans++
              if (plans == 2) {
                assertBlockedOutcome(assertNotNull(state))
              }
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              check(repairs <= 2) { "The worker received a rejection or the loop failed to continue." }
              assertEquals("AC-001", state?.currentCriterionRef)
              if (repairs == 1) {
                """{
                "contract_version":"0.7","phase_id":"audit_implement_fix","status":"blocked",
                "failure_disposition":"non_retryable_policy_conflict","summary":"External dependency refused",
                "produced_outputs":{"value":"An external dependency refuses the required write operation."}
              }"""
              } else {
                defaultPhaseOutput(request)
              }
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(2, repairs)
      assertEquals(2, plans)
      val final = assertNotNull(scope.snapshot)
      assertEquals(1, final.chargedAttempts["AC-001"])
      assertEquals(1, final.outcomes.count { it.kind == AcceptanceCriterionRepairOutcomeKind.BLOCKED })
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, final.stage)
      assertEquals(
        listOf("audit", "audit_plan_fix", "audit_implement_fix", "audit_plan_fix", "audit_implement_fix", "audit"),
        harness.launchedPromptPhaseOrder().filter { it.startsWith("audit") }.take(6),
      )
    }
  }

  private fun assertBlockedOutcome(afterBlock: AcceptanceCriterionRepairState) {
    assertEquals("AC-001", afterBlock.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, afterBlock.stage)
    assertEquals(1, afterBlock.chargedAttempts["AC-001"])
    val blocked = afterBlock.outcomes.single()
    assertEquals(AcceptanceCriterionRepairOutcomeKind.BLOCKED, blocked.kind)
    assertEquals(2, blocked.openGapsBefore)
    assertEquals(2, blocked.openGapsAfter)
    assertTrue(blocked.charged)
  }
}
