package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairPhaseRecorderTest {
  private val git =
    object : WorkflowGitOperations by NoopWorkflowGitOperations {
      override fun repositoryFingerprint(repoRoot: Path) = WorkflowGitOperationResult.Ok("current-source")
    }

  @Test
  fun `spec drift after a satisfied audit reports the bound source and preserves the running record`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement is implemented.")
        val next = phase(f, "audit", 2)
        assertTrue(recorder.start(next) == true)
        val before = f.snapshot().toRecord()
        val accounting = scope.snapshot
        f.sourceDigest = "e".repeat(64)
        val rejected =
          assertIs<RequiredPhaseWrite.Rejected>(
            recorder.complete(output(next, "satisfied", "The first requirement is implemented.")),
          )
        assertTrue(rejected.message.contains(worker.governedSpecPath))
        assertTrue(rejected.message.contains("changed or is unreadable"))
        assertTrue(rejected.message.contains("producer output is retained"))
        assertEquals(before, f.snapshot().toRecord())
        assertEquals(accounting, scope.snapshot)
        f.sourceDigest = f.binding.specDigest
        assertEquals(
          RequiredPhaseWrite.Acknowledged,
          recorder.complete(output(next, "satisfied", "The first requirement is implemented.")),
        )
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      }
    }
  }

  @Test
  fun `each criterion keeps its own failure count and its third unsuccessful cycle blocks`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(
              f.persistence,
              git,
            ).session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement is implemented. Tests remain for validation.")
        assertEquals("AC-002", scope.snapshot?.currentCriterionRef)
        assertFirstAuditReplay(f, recorder)
        audit(f, recorder, 2, false, "The second requirement has no execution caller.")
        assertFalse(
          recorder.complete(
            output(
              phase(f, "audit", 1),
              "satisfied",
              "The first requirement is implemented. Tests remain for validation.",
            ),
          ) == RequiredPhaseWrite.Acknowledged,
        )
        assertIncompleteCycleSettlesOnce(f, recorder)
        repair(f, recorder, 2)
        audit(f, recorder, 3, false, "The caller still permits a second semantic repair in one cycle.")
        assertEquals(2, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
        assertEquals(null, scope.snapshot?.unsuccessfulTotals?.get("AC-001"))
        assertEquals("AC-002", scope.snapshot?.currentCriterionRef)
        val last = phase(f, "audit_implement_fix", 3)
        assertTrue(recorder.start(last) == true)
        assertTrue(
          recorder.settleUnsuccessfulRepair(
            output(last, "advance", "The caller repair is incomplete."),
            AcceptanceCriterionRepairOutcomeKind.BLOCKED,
          ) == true,
        )
        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
        assertEquals(3, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
        assertEquals(WorkflowStatus.BLOCKED, f.snapshot().workflowStatus)
        assertFalse(recorder.start(phase(f, "audit_implement_fix", 4)) == true)
        assertEquals(3, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
      }
    }
  }

  @Test
  fun `clarified incomplete repair clears its report and persists the failed attempt before another cycle`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement holds.")
        audit(f, recorder, 2, false, "The second requirement needs a caller.")
        val first = phase(f, "audit_implement_fix", 1)
        assertTrue(recorder.start(first) == true)
        assertTrue(
          recorder.complete(
            output(first, "advance", "Only part of the caller is connected."),
          ) == RequiredPhaseWrite.Acknowledged,
        )
        assertNotNull(scope.snapshot?.pendingClarificationQuestion)
        audit(f, recorder, 3, false, "The caller still needs production work.")
        assertNull(scope.snapshot?.pendingClarificationQuestion)
        assertEquals("The caller still needs production work.", scope.snapshot?.outcomes?.last()?.evidenceRef)
        assertTrue(requireNotNull(scope.currentCriterionContext).contains("The caller still needs production work."))
        assertFalse(requireNotNull(scope.currentCriterionContext).contains("Only part of the caller is connected."))
        assertEquals(1, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
        assertEquals(
          FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
          decodeImplementationAttemptsFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(f.snapshot().artifacts),
          ).last().status,
        )
        assertEquals("audit_plan_fix", f.snapshot().currentStepId)
        assertTrue(recorder.start(phase(f, "audit_implement_fix", 2)) == true)
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, scope.snapshot?.stage)
        assertNull(scope.snapshot?.pendingClarificationQuestion)
        assertEquals("audit_implement_fix", f.snapshot().currentStepId)
      }
    }
  }

  @Test
  fun `duplicate starts preserve the live ledger and completed attempts cannot reopen`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement holds.")
        val closed = scope.snapshot
        val closedLedger = decodePhaseLedger(f.snapshot().artifacts)
        assertFalse(recorder.start(phase(f, "audit", 1)) == true)
        assertEquals(closed, scope.snapshot)
        assertEquals(closedLedger, decodePhaseLedger(f.snapshot().artifacts))
        audit(f, recorder, 2, false, "The second requirement needs a caller.")
        val first = phase(f, "audit_implement_fix", 1)
        assertTrue(recorder.start(first) == true)
        val reserved = scope.snapshot
        val liveLedger = decodePhaseLedger(f.snapshot().artifacts)
        assertTrue(recorder.start(first) == true)
        assertEquals(reserved, scope.snapshot)
        assertEquals(liveLedger, decodePhaseLedger(f.snapshot().artifacts))
      }
    }
  }

  @Test
  fun `a recovered live cycle survives projection recovery and a stale worker cannot acknowledge a new outcome`() {
    SerialRepairSqliteFixture().use { f ->
      lateinit var stale: AcceptanceCriterionRepairSession
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(
              f.persistence,
              git,
            ).session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        assertTrue(recorder.start(phase(f, "audit_implement_fix", 1)) == true)
        stale = recorder
      }
      val before = stale.accounting.snapshot
      assertFalse(
        stale.complete(
          output(phase(f, "audit_implement_fix", 1), "advance", "The caller was connected."),
        ) == RequiredPhaseWrite.Acknowledged,
      )
      assertEquals(before, stale.accounting.snapshot)
      f.owned { worker ->
        val recovered =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        assertEquals(before, recovered.snapshot)
        f.persistence.recoverProjection(f.root, f.workflowId, assertNotNull(recovered.binding))
        assertEquals(before, recovered.snapshot)
        assertTrue(
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(
              f.persistence,
              git,
            ).session(recovered, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
            .start(phase(f, "audit_implement_fix", 2)) == true,
        )
        assertEquals(before?.reservation?.reservationId, recovered.snapshot?.reservation?.reservationId)
        assertTrue(assertNotNull(recovered.snapshot).outcomes.isEmpty())
      }
    }
  }

  private fun assertIncompleteCycleSettlesOnce(
    f: SerialRepairSqliteFixture,
    recorder: AcceptanceCriterionRepairSession,
  ) {
    val first = phase(f, "audit_implement_fix", 1)
    assertTrue(recorder.start(first) == true)
    val reserved = assertNotNull(recorder.accounting.snapshot?.reservation)
    assertEquals("AC-002", reserved.criterionRef)
    assertTrue(
      recorder.settleUnsuccessfulRepair(
        output(first, "advance", "Only part of the caller is connected."),
        AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
      ) == true,
    )
    assertEquals(1, recorder.accounting.snapshot?.unsuccessfulTotals?.get("AC-002"))
    val incomplete = recorder.accounting.snapshot
    assertTrue(
      recorder.settleUnsuccessfulRepair(
        output(first, "advance", "Only part of the caller is connected."),
        AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
      ) == true,
    )
    assertEquals(incomplete, recorder.accounting.snapshot)
    val saved = f.snapshot().artifacts
    assertEquals(
      FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
      decodeImplementationAttemptsFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(saved),
      ).last().status,
    )
    assertEquals("audit_plan_fix", f.snapshot().currentStepId)
    assertEquals(1, decodePhaseRecords(saved)["audit_implement_fix"]?.attemptCount)
    assertTrue(decodePhaseLedger(saved).isNotEmpty())
  }

  private fun assertFirstAuditReplay(
    f: SerialRepairSqliteFixture,
    recorder: AcceptanceCriterionRepairSession,
  ) {
    val closedFirst = recorder.accounting.snapshot
    val firstLedger = decodePhaseLedger(f.snapshot().artifacts)
    assertTrue(
      recorder.complete(
        output(
          phase(f, "audit", 1),
          "satisfied",
          "The first requirement is implemented. Tests remain for validation.",
        ),
      ) == RequiredPhaseWrite.Acknowledged,
    )
    assertEquals(closedFirst, recorder.accounting.snapshot)
    assertEquals(firstLedger, decodePhaseLedger(f.snapshot().artifacts))
    assertTrue(assertNotNull(recorder.accounting.snapshot).outcomes.isEmpty())
  }

  private fun audit(
    f: SerialRepairSqliteFixture,
    recorder: AcceptanceCriterionRepairSession,
    attempt: Int,
    satisfied: Boolean,
    prose: String,
  ) {
    if (recorder.accounting.snapshot == null) {
      val discovery = phase(f, "audit", -1)
      assertTrue(recorder.start(discovery) == true)
      assertTrue(
        recorder.complete(
          output(
            discovery,
            "advance",
            "The full source audit found a missing caller.",
            f.audit().payload,
          ),
        ) == RequiredPhaseWrite.Acknowledged,
      )
      val initialAudit = phase(f, "audit", 0)
      assertTrue(recorder.start(initialAudit) == true)
      assertTrue(
        recorder.complete(
          output(initialAudit, "advance", "The full source audit found a missing caller."),
        ) == RequiredPhaseWrite.Acknowledged,
      )
    }
    val phase = phase(f, "audit", attempt)
    assertTrue(recorder.start(phase) == true)
    assertTrue(
      recorder.complete(
        output(phase, if (satisfied) "satisfied" else "advance", prose),
      ) == RequiredPhaseWrite.Acknowledged,
    )
  }

  private fun repair(
    f: SerialRepairSqliteFixture,
    recorder: AcceptanceCriterionRepairSession,
    attempt: Int,
  ) {
    val phase = phase(f, "audit_implement_fix", attempt)
    assertTrue(recorder.start(phase) == true)
    assertTrue(
      recorder.complete(
        output(phase, "satisfied", "Connected the missing production caller."),
      ) == RequiredPhaseWrite.Acknowledged,
    )
  }

  private fun phase(
    f: SerialRepairSqliteFixture,
    id: String,
    attempt: Int,
  ) = FeatureTaskRuntimePhaseStateRequest(
    f.workflowId,
    id,
    "running",
    if (id == "audit") attempt + 2 else attempt,
    "codex",
    false,
    mutating = id == "audit_implement_fix",
  )

  private fun output(
    phase: FeatureTaskRuntimePhaseStateRequest,
    verdict: String,
    prose: String,
    auditResult: Map<String, Any?>? = null,
  ): FeatureTaskRuntimePhaseStateRequest {
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phase.phaseId,
        "completed",
        "Audit conclusion",
        PhaseOutput(prose),
        verdict = verdict,
        runtimeRecord =
          auditResult?.let {
            mapOf(
              SharedPayloadKeys.PRODUCED_OUTPUTS to
                mapOf(Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT to it),
            )
          } ?: emptyMap(),
      )
    return phase.copy(
      status = "completed",
      finished = true,
      normalizedOutput = normalized,
      outputArtifact = normalized.canonicalJson,
    )
  }
}
