package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapOrigin
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.interaction.unknownScenarios
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairPhaseRecorderScenarios(
  private val verifyUnknownScenarios: (DatabaseSessionFactory, String) -> Unit,
) {
  private val git =
    object : WorkflowGitOperations by NoopWorkflowGitOperations {
      override fun repositoryFingerprint(repoRoot: Path) = WorkflowGitOperationResult.Ok("current-source")

      override fun trackedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(emptyList())

      override fun repositoryOwnedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(emptyList())
    }

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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
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

  fun `a broken trace reopens the owner and routes it to audit planning`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, recorder) = tracePendingSession(f, worker)

        val trace = phase(f, "audit", 3)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(trace))
        val settled =
          recorder.complete(output(trace, "advance", "Interaction IS-001: broken owner AC-002 trace src/Flow.kt:10"))

        assertEquals(RequiredPhaseWrite.Acknowledged, settled)
        val reopened = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, reopened.stage)
        assertEquals(AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED, reopened.mode)
        assertEquals("AC-002", reopened.currentCriterionRef)
        assertEquals(listOf("IS-001"), reopened.interactionTrace.brokenScenarioRefs)
        assertEquals("audit", f.snapshot().currentStepId)
        val discovery = phase(f, "audit", 4)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(discovery))
        assertEquals(
          RequiredPhaseWrite.Acknowledged,
          recorder.complete(
            output(
              discovery,
              "advance",
              "Open criteria: AC-002\nAC-002.G1: Restore the interaction caller @ src/Flow.kt:10",
            ),
          ),
        )
        assertEquals("audit_plan_fix", f.snapshot().currentStepId)
      }
    }
  }

  fun `a trace that stays malformed after one re-ask degrades to unknown with a warning`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, recorder) = tracePendingSession(f, worker)

        val trace = phase(f, "audit", 3)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(trace))
        val malformed = output(trace, "advance", "No scenario lines at all.")
        assertIs<RequiredPhaseWrite.Rejected>(recorder.complete(malformed))
        assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, scope.snapshot?.stage)

        scope.recordTraceReask(AcceptanceCriterionInteractionTraceRound.scopeKey(assertNotNull(scope.snapshot)))
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(malformed))

        val degraded = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, degraded.stage)
        assertEquals(listOf("IS-001"), degraded.interactionTrace.unknownScenarioRefs)
        assertEquals(listOf("malformed audit output"), degraded.unknownScenarios().map { it.reason })

        verifyUnknownScenarios(f.database, f.workflowId)
        assertTrue(f.warnings.any { it.contains("seam=interaction_trace") && it.contains("record_kind=degraded") })
      }
    }
  }

  fun `a repair that edits a cited file schedules a recheck that survives continuation reload and replay`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope = openScope(f, worker)
        val recorder = recorderFor(f, worker, scope)
        audit(f, recorder, 1, true, "AC-001 is implemented at src/Flow.kt:10.")
        audit(f, recorder, 2, false, "AC-002 still needs a caller.")

        val repair = phase(f, "audit_implement_fix", 2)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        val progress =
          output(repair, "satisfied", "The production caller for AC-002 is connected.")
            .copy(fileManifestIntroduced = listOf("src/Flow.kt"))
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(progress))
        val scheduled = assertNotNull(scope.snapshot)
        assertEquals(listOf("AC-001"), scheduled.interactionTrace.recheckRefs)
        assertEquals(scheduled, openScope(f, worker).snapshot)

        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(progress))
        assertEquals(scheduled, scope.snapshot)
        assertEquals(scheduled, openScope(f, worker).snapshot)
      }
    }
  }

  fun `a recheck only trace settles holds with fresh evidence and a regressed line reopens the criterion`() {
    mapOf(
      "holds" to AcceptanceCriterionRepairStage.ACCEPTED,
      "regressed" to AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
    ).forEach { (verdict, stage) ->
      SerialRepairSqliteFixture().use { f ->
        f.owned { worker ->
          val scope = openScope(f, worker)
          val recorder = recorderFor(f, worker, scope)
          audit(f, recorder, 1, true, "AC-001 is implemented at src/Flow.kt:10.")
          audit(f, recorder, 2, false, "AC-002 still needs a caller.")
          val repair = phase(f, "audit_implement_fix", 2)
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
          val repaired =
            output(repair, "satisfied", "Connected the caller.").copy(fileManifestIntroduced = listOf("src/Flow.kt"))
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(repaired))
          audit(f, recorder, 3, true, "AC-002 is implemented at src/Other.kt:3.")
          val pending = assertNotNull(scope.snapshot)
          assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, pending.stage)
          assertEquals(listOf("AC-001"), pending.interactionTrace.pendingRechecks)
          assertEquals(emptyList(), pending.interactionTrace.pendingScenarioRefs)

          val trace = phase(f, "audit", 4)
          assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(trace))
          if (verdict == "holds") {
            scope.recordTraceReask(AcceptanceCriterionInteractionTraceRound.scopeKey(pending))
            assertIs<RequiredPhaseWrite.Rejected>(recorder.complete(output(trace, "advance", "No decision given.")))
            assertEquals(listOf("AC-001"), assertNotNull(scope.snapshot).interactionTrace.pendingRechecks)
          }
          val settled =
            recorder.complete(
              output(
                trace,
                "advance",
                "Recheck AC-001: $verdict evidence src/Flow.kt:11\nRecheck AC-002: holds evidence src/Other.kt:3",
              ),
            )

          assertEquals(RequiredPhaseWrite.Acknowledged, settled)
          assertTrue(f.warnings.any { it.contains("record_kind=out_of_scope") && it.contains("AC-002") })
          val state = assertNotNull(scope.snapshot)
          assertEquals(stage, state.stage)
          assertEquals(emptyList(), state.interactionTrace.pendingRechecks)
          if (verdict == "holds") {
            assertEquals("src/Flow.kt:11", state.criteria.single { it.canonicalRef == "AC-001" }.closureEvidenceRef)
          } else {
            assertEquals("AC-001", state.currentCriterionRef)
            assertEquals("audit", f.snapshot().currentStepId)
            assertTrue(state.chargedAttempts.isEmpty())
            val discovery = phase(f, "audit", 5)
            assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(discovery))
            assertEquals(
              RequiredPhaseWrite.Acknowledged,
              recorder.complete(
                output(
                  discovery,
                  "advance",
                  "Open criteria: AC-001\nAC-001.G1: Restore the caller @ src/Flow.kt:11",
                ),
              ),
            )
            assertEquals("audit_plan_fix", f.snapshot().currentStepId)
          }
        }
      }
    }
  }

  fun `a malformed Recheck without evidence is a relaunchable re-ask rather than a hard schema block`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope = openScope(f, worker)
        val recorder = recorderFor(f, worker, scope)
        audit(f, recorder, 1, true, "AC-001 is implemented at src/Flow.kt:10.")
        audit(f, recorder, 2, false, "AC-002 still needs a caller.")
        val repair = phase(f, "audit_implement_fix", 2)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        val repaired =
          output(repair, "satisfied", "Connected the caller.").copy(fileManifestIntroduced = listOf("src/Flow.kt"))
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(repaired))
        audit(f, recorder, 3, true, "AC-002 is implemented at src/Other.kt:3.")
        val pending = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, pending.stage)
        assertEquals(listOf("AC-001"), pending.interactionTrace.pendingRechecks)
        assertEquals(emptyList(), pending.interactionTrace.pendingScenarioRefs)

        val reason =
          AcceptanceCriterionInteractionTraceRound.reaskRejection(
            scope,
            "The completeness audit is settled as satisfied.",
          )
        assertNotNull(reason)
        assertTrue(FeatureTaskRuntimeFailureCode.INVALID_INTERACTION_TRACE_AUDIT_OUTPUT.name in reason)
        assertTrue("Recheck AC-001" in reason)
        assertTrue(AcceptanceCriterionInteractionTraceRound.shouldRelaunch(scope, outputGateFailures = 0))
        assertFalse(AcceptanceCriterionInteractionTraceRound.shouldRelaunch(scope, outputGateFailures = 1))
        assertFalse(AcceptanceCriterionInteractionTraceRound.shouldRelaunch(null, outputGateFailures = 0))
      }
    }
  }

  private fun openScope(
    f: SerialRepairSqliteFixture,
    worker: AcquiredFeatureTaskRuntimeWorker,
  ) = AcceptanceCriterionRepairRunState(
    f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
    f.binding,
  )

  private fun recorderFor(
    f: SerialRepairSqliteFixture,
    worker: AcquiredFeatureTaskRuntimeWorker,
    scope: AcceptanceCriterionRepairRunState,
  ): AcceptanceCriterionRepairSession =
    requireNotNull(
      AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
        .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
    )

  internal fun tracePendingSession(
    f: SerialRepairSqliteFixture,
    worker: AcquiredFeatureTaskRuntimeWorker,
  ): Pair<AcceptanceCriterionRepairRunState, AcceptanceCriterionRepairSession> {
    val binding =
      f.binding.copy(
        interactionScenarios =
          listOf(
            InteractionScenario("IS-001", listOf("AC-001", "AC-002"), emptyList(), "entry", "pre", "steps", "out"),
          ),
      )

    fun open() =
      AcceptanceCriterionRepairRunState(
        f.persistence.inspectExisting(worker, binding.authoredCriteria, binding.specDigest),
        binding,
      )

    val accepting = open()
    val acceptor =
      requireNotNull(
        AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
          .session(accepting, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
      )
    val first = phase(f, "audit", 1)
    assertEquals(RequiredPhaseWrite.Acknowledged, acceptor.start(first))
    assertEquals(
      RequiredPhaseWrite.Acknowledged,
      acceptor.complete(output(first, "satisfied", "All production requirements are implemented.")),
    )
    assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, accepting.snapshot?.stage)

    assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
      f.persistence.prepareBoundInteractionTrace(worker, binding, f.root),
    )
    val scope = open()
    assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, scope.snapshot?.stage)
    val recorder =
      requireNotNull(
        AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
          .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
      )
    return scope to recorder
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

  fun `progress repair settlement reaches targeted verification with changed paths`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val scope = openScope(f, worker)
        val recorder = recorderFor(f, worker, scope)
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        val repair = phase(f, "audit_implement_fix", 1)
        val completed =
          output(repair, "progress", "One caller is connected; other work remains.")
            .copy(fileManifestIntroduced = listOf("src/Foo.kt"))

        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(completed))

        assertEquals(
          AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION,
          scope.snapshot?.stage,
        )
        assertEquals(listOf("src/Foo.kt"), scope.snapshot?.repairChangedPaths)
        assertTrue(assertNotNull(scope.snapshot).chargedAttempts.isEmpty())
        val saved = scope.snapshot
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(completed))
        assertEquals(saved, scope.snapshot)
      }
    }
  }

  fun `a retained repair clarification resumes through verification without losing its evidence`() {
    SerialRepairSqliteFixture().use { f ->
      val retainedReport = "Only one caller is connected. The remaining caller still needs repair."
      f.owned { worker ->
        val scope = openScope(f, worker)
        val recorder = recorderFor(f, worker, scope)
        audit(f, recorder, 1, false, "The first requirement needs a caller.")
        val repair = phase(f, "audit_implement_fix", 1)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(repair))
        val reserved = assertNotNull(scope.snapshot)
        assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
          f.persistence.write(
            worker,
            f.binding,
            AcceptanceCriterionRepairCommit(
              ExpectedAcceptanceCriterionRepairState(
                reserved.revision,
                reserved.currentCriterionRef,
                reserved.reservation?.reservationId,
              ),
              AcceptanceCriterionRepairMutation.Clarification(retainedReport),
              output(repair, null, retainedReport).copy(fileManifestIntroduced = listOf("src/Foo.kt")),
              null,
              stepRoles = recorder.steps,
            ),
            f.root,
          ),
        )
      }
      f.owned { worker ->
        val scope = openScope(f, worker)
        val recorder = recorderFor(f, worker, scope)
        val reserved = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, reserved.stage)
        val audit = phase(f, "audit", 2)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(audit))
        val completed = output(audit, "progress", "The retained repair needs independent verification.")
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(completed))
        val verifying = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION, verifying.stage)
        assertEquals(reserved.reservation?.reservationId, verifying.reservation?.reservationId)
        assertEquals(retainedReport, verifying.completedRepairEvidenceRef)
        assertEquals(listOf("src/Foo.kt"), verifying.repairChangedPaths)
        assertEquals(reserved.chargedAttempts, verifying.chargedAttempts)
        assertEquals(reserved.outcomes, verifying.outcomes)
        assertNull(verifying.pendingClarificationQuestion)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(completed))
        assertEquals(verifying, openScope(f, worker).snapshot)
      }
    }
  }

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
              f.diagnostics,
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
        assertBlockedCycleSettlesOnce(f, recorder)
        repair(f, recorder, 2)
        audit(f, recorder, 3, false, "The caller still permits a second semantic repair in one cycle.")
        assertEquals(2, scope.snapshot?.chargedAttempts?.get("AC-002"))
        assertEquals(null, scope.snapshot?.chargedAttempts?.get("AC-001"))
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
        assertEquals(3, scope.snapshot?.chargedAttempts?.get("AC-002"))
        assertEquals(WorkflowStatus.BLOCKED, f.snapshot().workflowStatus)
        assertFalse(recorder.start(phase(f, "audit_implement_fix", 4)) == RequiredPhaseWrite.Acknowledged)
        assertEquals(3, scope.snapshot?.chargedAttempts?.get("AC-002"))
      }
    }
  }

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
            AcceptanceCriterionRepairPhaseRecorder(f.persistence, git, f.workflows, f.diagnostics)
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

  fun `recovered cycles survive projection recovery and stale workers cannot acknowledge new outcomes`() {
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
              f.diagnostics,
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
              f.diagnostics,
            ).session(recovered, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
            .start(phase(f, "audit_implement_fix", 2)) == RequiredPhaseWrite.Acknowledged,
        )
        assertEquals(before?.reservation?.reservationId, recovered.snapshot?.reservation?.reservationId)
        assertTrue(assertNotNull(recovered.snapshot).outcomes.isEmpty())
      }
    }
  }

  private fun assertBlockedCycleSettlesOnce(
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
        AcceptanceCriterionRepairOutcomeKind.BLOCKED,
      ) == true,
    )
    assertEquals(1, recorder.accounting.snapshot?.chargedAttempts?.get("AC-002"))
    val incomplete = recorder.accounting.snapshot
    assertTrue(
      recorder.settleUnsuccessfulRepair(
        output(first, "advance", "Only part of the caller is connected."),
        AcceptanceCriterionRepairOutcomeKind.BLOCKED,
      ) == true,
    )
    assertEquals(incomplete, recorder.accounting.snapshot)
    val saved = f.snapshot().artifacts
    assertEquals(
      FeatureTaskRuntimeImplementationAttemptStatus.BLOCKED,
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
      val criteria =
        f.admission.criteria.map { criterion ->
          criterion.copy(
            sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
            gaps =
              listOf(
                AcceptanceCriterionGap(
                  "${criterion.canonicalRef}.G1",
                  "Missing production caller",
                  "src/Foo.kt:1",
                  AcceptanceCriterionGapStatus.OPEN,
                  AcceptanceCriterionGapOrigin.DISCOVERY,
                  null,
                ),
              ),
          )
        }
      val admitted = assertNotNull(AcceptanceCriterionRepairState.admit(f.admission.copy(criteria = criteria)))
      val wire =
        admitted.asRepairWireMap() +
          mapOf(
            Keys.MODE to AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED.wireValue,
            Keys.STAGE to AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED.wireValue,
            Keys.PENDING_AUDIT_REFS to criteria.map { it.canonicalRef },
          )
      val state =
        FeatureTaskRuntimeWorkflowArtifactMap.from(wire)
          .toAcceptanceCriterionRepairState("focused audit fixture")
      val snapshot = f.snapshot()
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
      f.database.transaction {
        it.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          snapshot.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                snapshot.artifacts +
                  family.entry(state.asRepairWireMap()),
              ),
          ),
        )
      }
      recorder.accounting.acknowledgeCommitted(state)
    }
    val before = assertNotNull(recorder.accounting.snapshot)
    val verifying = before.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val report =
      if (verifying) {
        (
          listOf(prose) +
            before.criteria.single { it.canonicalRef == before.currentCriterionRef }.gaps
              .filter { it.status == AcceptanceCriterionGapStatus.OPEN }
              .map { "${it.id}: ${if (satisfied) "closed" else "open"} @ src/Foo.kt:1" }
        ).joinToString("\n")
      } else {
        prose
      }
    val phase = phase(f, "audit", attempt)
    assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase))
    assertTrue(
      recorder.complete(
        output(phase, if (satisfied) "satisfied" else "advance", report),
      ) == RequiredPhaseWrite.Acknowledged,
    )
  }

  private fun repair(
    f: SerialRepairSqliteFixture,
    recorder: AcceptanceCriterionRepairSession,
    attempt: Int,
  ) {
    val phase = phase(f, "audit_implement_fix", attempt)
    assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase))
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
  ): FeatureTaskRuntimePhaseStateRequest {
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phase.phaseId,
        "completed",
        "Audit conclusion",
        PhaseOutput(prose),
        verdict = verdict,
      )
    return phase.copy(
      status = "completed",
      finished = true,
      normalizedOutput = normalized,
      outputArtifact = normalized.canonicalJson,
    )
  }
}
