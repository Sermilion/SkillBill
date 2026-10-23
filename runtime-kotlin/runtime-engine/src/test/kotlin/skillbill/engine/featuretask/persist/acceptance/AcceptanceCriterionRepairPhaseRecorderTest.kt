package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState
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

      override fun trackedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(emptyList())

      override fun repositoryOwnedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(emptyList())
    }

  @Test
  fun `spec drift before repair start retains the refusal without reserving or consuming an attempt`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        val repair = phase(f, "audit_implement_fix", 1)
        val before = f.snapshot().toRecord()
        val accounting = scope.snapshot
        f.sourceDigest = "e".repeat(64)

        val rejected = assertIs<RequiredPhaseWrite.Rejected>(recorder.start(repair))

        assertEquals(RequiredPhaseWriteKind.START, rejected.writeKind)
        assertEquals(repair.phaseId, rejected.phaseId)
        assertEquals(repair.attemptCount, rejected.attempt)
        assertTrue(rejected.message.contains(worker.governedSpecPath))
        assertTrue(rejected.message.contains("changed or is unreadable"))
        assertEquals(before, f.snapshot().toRecord())
        assertEquals(accounting, scope.snapshot)
        assertNull(scope.snapshot?.reservation)
        assertTrue(assertNotNull(scope.snapshot).outcomes.isEmpty())

        f.sourceDigest = f.binding.specDigest
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, scope.snapshot?.stage)
      }
    }
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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement is implemented.")
        val next = phase(f, "audit", 2)
        assertTrue(recorder.start(next) == RequiredPhaseWrite.Acknowledged)
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
  fun `an accepted catalog re-audits only the criteria that open QA findings name`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement is implemented.")
        val second = phase(f, "audit", 2)
        assertTrue(recorder.start(second) == RequiredPhaseWrite.Acknowledged)
        recorder.complete(output(second, "satisfied", "The second requirement is implemented."))
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)

        assertTrue(recorder.start(phase(f, "audit", 3)) == RequiredPhaseWrite.Acknowledged)
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)

        val receiptRef = "/home/qa/.skill-bill/qa/app-0123456789abcdef/q1/receipt.json"
        saveQaFindings(f, QaOpenFinding("F-1", listOf("AC-002"), listOf(receiptRef)))
        val reaudit = phase(f, "audit", 4)
        assertTrue(recorder.start(reaudit) == RequiredPhaseWrite.Acknowledged)
        val admitted = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, admitted.stage)
        assertEquals(listOf("AC-002"), admitted.pendingAuditRefs)
        assertEquals("AC-002", admitted.currentCriterionRef)
        assertEquals(
          AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
          admitted.criteria.single { it.canonicalRef == "AC-001" }.sourceDisposition,
        )

        assertEquals(
          RequiredPhaseWrite.Acknowledged,
          recorder.complete(output(reaudit, "satisfied", "The second requirement still holds after the QA repair.")),
        )
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      }
    }
  }

  private fun saveQaFindings(
    f: SerialRepairSqliteFixture,
    vararg findings: QaOpenFinding,
  ) {
    val state = QaRepairState(subtaskId = 1, openFindings = findings.toList(), lastReceiptTreeIdentity = "1".repeat(64))
    f.database.transaction { unit ->
      val record = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, f.workflowId))
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QA_REPAIR_STATE
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        record.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(record.artifacts + mapOf(family.entry(state.asQaRepairStateWireMap()))),
        ),
      )
    }
  }

  @Test
  fun `three rejected completed repair claims block even when verification reports progress`() =
    assertRejectedRepairBudget("completed")

  @Test
  fun `three rejected repair claims without a verdict block even when verification reports progress`() =
    assertRejectedRepairBudget(null)

  @Test
  fun `three rejected satisfied repair claims block even when verification reports progress`() =
    assertRejectedRepairBudget("satisfied")

  private fun assertRejectedRepairBudget(verdict: String?) {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, false, "The first requirement needs a caller.")

        repeat(3) { index ->
          val repair = phase(f, "audit_implement_fix", index + 1)
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
          assertEquals(
            RequiredPhaseWrite.Acknowledged,
            recorder.complete(output(repair, verdict, "The production repair is complete.")),
          )
          assertEquals(
            AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION,
            scope.snapshot?.stage,
          )
          assertNull(scope.snapshot?.pendingClarificationQuestion)
          val verification = phase(f, "audit", index + 2)
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(verification))
          val rejected = output(verification, "progress", "The claimed repair still loses the causal incident.")
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(rejected))
          assertEquals(index + 1, scope.snapshot?.unsuccessfulTotals?.get("AC-001"))
          val saved = scope.snapshot
          if (index < 2) assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(rejected))
          assertEquals(saved, scope.snapshot)
        }

        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
        assertEquals(WorkflowStatus.BLOCKED, f.snapshot().workflowStatus)
        assertFalse(recorder.start(phase(f, "audit_implement_fix", 4)) == RequiredPhaseWrite.Acknowledged)
        val restored = f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest)
        assertEquals(scope.snapshot, assertIs<AcceptanceCriterionRepairHydration.Present>(restored).state)
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
              f.workflows,
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
        assertTrue(recorder.start(last) == RequiredPhaseWrite.Acknowledged)
        assertTrue(
          recorder.settleUnsuccessfulRepair(
            output(last, "advance", "The caller repair is incomplete."),
            AcceptanceCriterionRepairOutcomeKind.BLOCKED,
          ) == true,
        )
        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
        assertEquals(3, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
        assertEquals(WorkflowStatus.BLOCKED, f.snapshot().workflowStatus)
        assertFalse(recorder.start(phase(f, "audit_implement_fix", 4)) == RequiredPhaseWrite.Acknowledged)
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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement holds.")
        audit(f, recorder, 2, false, "The second requirement needs a caller.")
        val first = phase(f, "audit_implement_fix", 1)
        assertTrue(recorder.start(first) == RequiredPhaseWrite.Acknowledged)
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
        assertTrue(recorder.start(phase(f, "audit_implement_fix", 2)) == RequiredPhaseWrite.Acknowledged)
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, scope.snapshot?.stage)
        assertNull(scope.snapshot?.pendingClarificationQuestion)
        assertEquals("audit_implement_fix", f.snapshot().currentStepId)
      }
    }
  }

  @Test
  fun `admitted partial work survives recovery with the same reservation and no failed outcome`() =
    assertPartialWorkSurvivesRecovery(directProgress = false)

  @Test
  fun `direct partial work survives recovery with the same reservation and incomplete attempt`() =
    assertPartialWorkSurvivesRecovery(directProgress = true)

  private fun assertPartialWorkSurvivesRecovery(directProgress: Boolean) {
    SerialRepairSqliteFixture().use { f ->
      var reservationId: String? = null
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        val repair = phase(f, "audit_implement_fix", 1)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        reservationId = scope.snapshot?.reservation?.reservationId
        val report =
          output(repair, if (directProgress) "progress" else "advance", "Only the first caller is connected.")
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(report))
        val continued =
          if (directProgress) {
            report
          } else {
            val admission = phase(f, "audit", 2)
            assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(admission))
            output(admission, "progress", "The second caller needs connection and source work can continue.")
          }
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(continued))
        val saved = scope.snapshot
        val ledger = decodePhaseLedger(f.snapshot().artifacts)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(continued))
        assertEquals(saved, scope.snapshot)
        assertEquals(ledger, decodePhaseLedger(f.snapshot().artifacts))
        assertEquals("audit_implement_fix", f.snapshot().currentStepId)
        assertEquals(
          FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
          decodeImplementationAttemptsFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(f.snapshot().artifacts),
          ).last().status,
        )
      }
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val state = assertNotNull(scope.snapshot)
        assertEquals(reservationId, state.reservation?.reservationId)
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, state.stage)
        assertNull(state.pendingClarificationQuestion)
        assertTrue(state.unsuccessfulTotals.isEmpty())
        assertTrue(state.outcomes.isEmpty())
        val recorder =
          requireNotNull(
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase(f, "audit_implement_fix", 2)))
        assertEquals(reservationId, scope.snapshot?.reservation?.reservationId)
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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows)
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, true, "The first requirement holds.")
        val closed = scope.snapshot
        val closedLedger = decodePhaseLedger(f.snapshot().artifacts)
        assertFalse(recorder.start(phase(f, "audit", 1)) == RequiredPhaseWrite.Acknowledged)
        assertEquals(closed, scope.snapshot)
        assertEquals(closedLedger, decodePhaseLedger(f.snapshot().artifacts))
        audit(f, recorder, 2, false, "The second requirement needs a caller.")
        val first = phase(f, "audit_implement_fix", 1)
        assertTrue(recorder.start(first) == RequiredPhaseWrite.Acknowledged)
        val reserved = scope.snapshot
        val liveLedger = decodePhaseLedger(f.snapshot().artifacts)
        assertTrue(recorder.start(first) == RequiredPhaseWrite.Acknowledged)
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
              f.workflows,
            ).session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        assertTrue(recorder.start(phase(f, "audit_implement_fix", 1)) == RequiredPhaseWrite.Acknowledged)
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
              f.workflows,
            ).session(recovered, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
            .start(phase(f, "audit_implement_fix", 2)) == RequiredPhaseWrite.Acknowledged,
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
    assertTrue(recorder.start(first) == RequiredPhaseWrite.Acknowledged)
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
      assertTrue(recorder.start(discovery) == RequiredPhaseWrite.Acknowledged)
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
      assertTrue(recorder.start(initialAudit) == RequiredPhaseWrite.Acknowledged)
      assertTrue(
        recorder.complete(
          output(initialAudit, "advance", "The full source audit found a missing caller."),
        ) == RequiredPhaseWrite.Acknowledged,
      )
    }
    val phase = phase(f, "audit", attempt)
    assertTrue(recorder.start(phase) == RequiredPhaseWrite.Acknowledged)
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
    assertTrue(recorder.start(phase) == RequiredPhaseWrite.Acknowledged)
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
    verdict: String?,
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
