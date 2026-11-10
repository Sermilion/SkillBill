package skillbill.engine.featuretask.runner

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReportAdmissionTest {
  @ParameterizedTest
  @ValueSource(strings = ["repair_report", "targeted_failure", "omitted", "prose"])
  fun `unclassified partial repairs require admission and consume each reservation once`(reportKind: String) {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val git = AtomicReference<RecordingWorkflowGitOperations>()
    val reservations = mutableListOf<String>()
    val partialReport = "AC-001 remains unfinished. I did not submit a durable phase settlement."
    var repairs = 0
    var plans = 0
    var admissions = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = assertNotNull(request.skillRunRequest.promptOverride)
        val phase = phaseIdFromPrompt(prompt)
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" -> {
              audits++
              if (state?.reservation != null) {
                assertContains(prompt, "Read-only repair-report admission", message = reportKind)
                assertContains(prompt, partialReport)
                admissions++
                settledAuditSatisfiedRecord().replace("satisfied", "progress")
              } else {
                continuingAuditOutput(state, audits)
              }
            }
            "audit_plan_fix" -> {
              plans++
              if (repairs > 0) assertEquals("repair-$repairs", git.get().contentIdentities["src/Foo.kt"])
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              check(repairs <= 3) { "Unclassified repair reports bypassed the criterion budget." }
              reservations += assertNotNull(state?.reservation?.reservationId)
              assertEquals(repairs - 1, state.chargedAttempts.getOrDefault("AC-001", 0))
              git.get().contentIdentities["src/Foo.kt"] = "repair-$repairs"
              repairReport(reportKind, phase, partialReport)
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    git.set(harness.gitOperations)
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$reportKind: $report")
      assertContains(report.blockedReason, "three unsuccessful repair attempts")
      assertEquals(3, plans)
      assertEquals(3, admissions)
      assertEquals(3, reservations.distinct().size)
      assertIncompleteRepairAttempts(harness, scope, partialReport)
    }
  }

  private fun repairReport(
    reportKind: String,
    phase: String,
    value: String,
  ): String =
    if (reportKind == "prose") {
      value
    } else {
      JsonCodec.mapToJsonString(
        buildMap {
          put(SharedPayloadKeys.CONTRACT_VERSION, FEATURE_TASK_RUNTIME_CONTRACT_VERSION)
          put(SharedPayloadKeys.PHASE_ID, phase)
          put(SharedPayloadKeys.STATUS, WorkflowStepStatus.COMPLETED.wireValue)
          put(SharedPayloadKeys.PRODUCED_OUTPUTS, mapOf(SharedPayloadKeys.VALUE to value))
          if (reportKind != "omitted") put(SharedPayloadKeys.VERDICT, reportKind)
        },
      )
    }

  private fun assertIncompleteRepairAttempts(
    harness: RunnerHarness,
    scope: AcceptanceCriterionRepairRunState,
    partialReport: String,
  ) {
    val state = assertNotNull(scope.snapshot)
    assertEquals(3, state.chargedAttempts["AC-001"])
    assertEquals(List(3) { AcceptanceCriterionRepairOutcomeKind.INCOMPLETE }, state.outcomes.map { it.kind })
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
    assertEquals(AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED, state.criteria.first().sourceDisposition)
    val attempts =
      harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty()
        .filter { it.phaseId == "audit_implement_fix" }.groupBy { it.attemptNumber }
    assertEquals(3, attempts.size)
    attempts.values.forEach { records ->
      assertEquals(FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE, records.last().status)
      assertEquals(partialReport, records.last().value)
    }
    assertFalse("review" in harness.launchOrder())
  }

  @Test
  fun `finished prose requires report admission followed by independent source verification`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    var admissions = 0
    var verifications = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = assertNotNull(request.skillRunRequest.promptOverride)
        val phase = phaseIdFromPrompt(prompt)
        val state = accounting.get()?.snapshot
        facts(
          when {
            phase == "audit_implement_fix" -> "Every production item in the saved repair plan is implemented."
            phase != "audit" -> defaultPhaseOutput(request)
            else -> {
              audits++
              when {
                state?.pendingClarificationQuestion != null -> {
                  admissions++
                  assertContains(prompt, "Read-only repair-report admission")
                  assertEquals(
                    AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                    state.criteria.first().sourceDisposition,
                  )
                  settledAuditSatisfiedRecord()
                }
                state?.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION -> {
                  verifications++
                  assertEquals(1, admissions)
                  assertEquals(
                    AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                    state.criteria.first().sourceDisposition,
                  )
                  serialStructuredAuditOutput(audits, state, "The repaired production path meets AC-001.")
                }
                else -> continuingAuditOutput(state, audits)
              }
            }
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
      assertEquals(1, admissions)
      assertEquals(1, verifications)
      assertTrue(assertNotNull(scope.snapshot).chargedAttempts.isEmpty())
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      assertContains(harness.launchOrder(), "review")
    }
  }
}
