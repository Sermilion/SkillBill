package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAckStatus
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.engine.featuretask.phase.core.testPhaseSettlementService
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairResumeEvidenceTest {
  @Test
  fun `reserved repair restores its cleared audit from the matching completed settlement`() =
    assertReservedRepairReceipt(matchingSettlement = true)

  @Test
  fun `reserved repair cannot substitute an older settlement for a missing matching audit`() =
    assertReservedRepairReceipt(matchingSettlement = false)

  @Test
  fun `reserved repair cannot substitute an older completed attempt for the current audit`() =
    assertReservedRepairReceipt(matchingSettlement = true, recordAttempt = 5)

  private fun assertReservedRepairReceipt(
    matchingSettlement: Boolean,
    recordAttempt: Int = 4,
  ) {
    SerialRepairSqliteFixture().use { f ->
      val reserved = assertNotNull(f.initial.reserve("saved-reservation"))
      val scope = AcceptanceCriterionRepairRunState(AcceptanceCriterionRepairHydration.Present(reserved))
      val request =
        FeatureTaskRuntimeRunRequest(
          issueKey = "SKILL-446",
          workflowId = f.workflowId,
          sessionId = "resume",
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = "spec.md",
              acceptanceCriteria = f.binding.authoredCriteria,
              mandatesAndOverrides = emptyList(),
            ),
          invokedAgentId = "codex",
          repoRoot = f.root,
          acceptanceCriterionRepair = scope,
        )
      val repository = InMemoryFeatureTaskPhaseSettlementRepository()
      val service = testPhaseSettlementService(repository, f.clock)
      listOf(3, 4).forEach { attempt ->
        assertEquals(
          FeatureTaskPhaseSettlementAckStatus.OK,
          service.complete(
            FeatureTaskPhaseSettlementCompleteRequest(f.workflowId, "audit", attempt, "audit findings $attempt"),
          ).status,
        )
      }
      if (!matchingSettlement) repository.delete(f.workflowId, "audit", 4)
      val record =
        FeatureTaskRuntimePhaseRecord(
          phaseId = "audit",
          status = WorkflowStepStatus.PENDING,
          attemptCount = recordAttempt,
          startedAt = f.clock.instant(),
          resolvedAgentId = "codex",
        )
      val completed =
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          sequenceNumber = 10,
          timestamp = f.clock.instant(),
          phaseId = "audit",
          attemptCount = 4,
        )
      val restored =
        service.restoreAcceptanceRepairResumeEvidence(
          request,
          AcceptanceCriterionRepairStepRoles("audit", "audit_plan_fix", "audit_implement_fix", "audit_repair"),
          mapOf("audit" to record),
          listOf(completed, completed.copy(action = FeatureTaskRuntimePhaseLedgerAction.RETRY, sequenceNumber = 11)),
          NoopRuntimeDiagnostics,
        )
      if (matchingSettlement && recordAttempt == 4) {
        assertEquals("audit", assertNotNull(restored).phaseId)
        assertEquals(4, restored.iteration)
        assertEquals("audit findings 4", restored.normalizedOutput?.output?.value)
      } else {
        assertNull(restored)
      }
      assertEquals(reserved, scope.snapshot)
    }
  }
}
