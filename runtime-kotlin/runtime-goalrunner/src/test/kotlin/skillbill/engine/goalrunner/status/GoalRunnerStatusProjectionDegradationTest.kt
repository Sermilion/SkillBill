package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.runloop.observability.ledgerDetailForTest
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerPhaseConclusion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskRuntimePhaseLedgerPage
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerStatusProjectionDegradationTest {
  @Test
  fun `malformed audit state degrades its own source while legacy versions remain readable`() {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
    val record = auditRecord()
    val conclusion = GoalRunnerPhaseConclusion("satisfied", null, null, 1)
    val diagnostics = RecordingStatusDiagnostics()
    val tracker = GoalRunnerStatusDurableReadTracker(diagnostics)
    val degradation = SubtaskReadDegradation(tracker)
    val malformed =
      projectSubtaskAudit(
        DurableWorkflowArtifacts.fromMap(mapOf(family.entry("malformed"))),
        record,
        conclusion,
        degradation,
      )

    assertEquals(GoalRunnerSubtaskAuditOutcome.PASSED, malformed?.outcome)
    assertEquals("subtask_audit_criteria", degradation.source)
    assertTrue(tracker.degraded)
    assertTrue(diagnostics.warnings.single().contains("goal-status.subtask_audit_criteria"))

    listOf(
      mapOf("legacy" to true),
      mapOf(
        AcceptanceCriterionRepairPayloadKeys.CONTRACT_VERSION to
          ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
      ),
    ).forEach { payload ->
      val legacyTracker = GoalRunnerStatusDurableReadTracker(RecordingStatusDiagnostics())
      val legacyDegradation = SubtaskReadDegradation(legacyTracker)
      val projected =
        projectSubtaskAudit(
          DurableWorkflowArtifacts.fromMap(mapOf(family.entry(payload))),
          record,
          conclusion,
          legacyDegradation,
        )
      assertEquals(GoalRunnerSubtaskAuditOutcome.PASSED, projected?.outcome)
      assertNull(projected?.metCount)
      assertEquals(emptyList(), projected?.criteria)
      assertEquals(1, projected?.attemptTotal)
      assertNull(legacyDegradation.source)
      assertFalse(legacyTracker.degraded)
    }
  }

  @Test
  fun `audit outcome never treats an unknown verdict as passing`() {
    val record = auditRecord()
    assertEquals(GoalRunnerSubtaskAuditOutcome.PASSED, subtaskAuditOutcome(record, null, "satisfied"))
    assertEquals(GoalRunnerSubtaskAuditOutcome.FAILED, subtaskAuditOutcome(record, null, "advance"))
    assertEquals(
      GoalRunnerSubtaskAuditOutcome.UNKNOWN,
      subtaskAuditOutcome(record, null, "future_verdict"),
    )
    assertEquals(GoalRunnerSubtaskAuditOutcome.UNKNOWN, subtaskAuditOutcome(record, null, null))
    assertEquals(
      GoalRunnerSubtaskAuditOutcome.RUNNING,
      subtaskAuditOutcome(auditRecord(WorkflowStepStatus.RUNNING), null, "satisfied"),
    )
    assertEquals(
      GoalRunnerSubtaskAuditOutcome.FAILED,
      subtaskAuditOutcome(auditRecord(WorkflowStepStatus.BLOCKED), null, "satisfied"),
    )
  }

  @Test
  fun `attempt ledger failure records the seam and marks the projection degraded`() {
    val diagnostics = RecordingStatusDiagnostics()
    val projection =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1)),
          outcomeStore = RecordingOutcomeStore(),
          ports =
            GoalRunnerStatusTestPorts(
              attemptLedgerStore =
                object : GoalRunnerAttemptLedgerStore {
                  override fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary =
                    error("attempt ledger unavailable")
                },
              diagnostics = diagnostics,
            ),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )

    assertTrue(projection.degradedDurableRead)
    assertEquals(0, projection.blockedAttemptCount)
    assertEquals(
      "seam=goal-status.attempt_ledger value_expected=ledger_summary value_used=degraded",
      diagnostics.warnings.single(),
    )
  }

  @Test
  fun `a subtask phase result failure records its seam`() {
    val diagnostics = RecordingStatusDiagnostics()
    val manifest =
      manifest(subtaskCount = 1).let { original ->
        original.copy(subtasks = original.subtasks.map { it.copy(status = "complete", workflowId = "wfl-unknown") })
      }

    requireNotNull(
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest),
        outcomeStore = RecordingOutcomeStore(),
        ports = GoalRunnerStatusTestPorts(diagnostics = diagnostics),
      ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
    )

    assertTrue(diagnostics.warnings.any { it.startsWith("seam=goal-status.subtask_phase_results ") })
  }

  @Test
  fun `journal and audit retry read failures omit values and name both seams`() {
    val diagnostics = RecordingStatusDiagnostics()
    val throwingJournalDatabase =
      StatusJournalDatabaseSessionFactory(
        object : WorktreeEditJournalRepository {
          override fun append(
            workflowId: String,
            tick: WorktreeEditTick,
          ) = error("unused")

          override fun latestTick(workflowId: String): WorktreeEditTick? = error("journal unavailable")

          override fun deleteTicksBefore(
            workflowId: String,
            recordedAt: Instant,
          ): Int = error("unused")
        },
      )
    val ledgerFailingStates =
      object : WorkflowStateRepository by InMemoryWorkflowStates() {
        override fun readFeatureTaskPhaseLedgerPage(
          workflowId: String,
          offset: Int,
          limit: Int,
        ): FeatureTaskRuntimePhaseLedgerPage? = error("ledger unavailable")

        override fun getFeatureTaskWorkflowAsMode(
          workflowId: String,
          mode: FeatureTaskWorkflowMode,
        ): WorkflowStateRecord? = error("ledger unavailable")

        override fun get(
          family: WorkflowFamily,
          workflowId: String,
        ): WorkflowStateSnapshot? = error("ledger unavailable")
      }
    val projection =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore =
            InMemoryGoalManifestStore(
              manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-child"),
            ),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder =
            testPhaseRecorder(
              FakeDatabaseSessionFactory(ledgerFailingStates),
              testWorkflowSnapshotValidator,
            ),
          database = throwingJournalDatabase,
          ports = GoalRunnerStatusTestPorts(diagnostics = diagnostics),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )

    assertNull(projection.latestWorktreeEdit)
    assertNull(projection.auditAcRetryCount)
    assertTrue(projection.degradedDurableRead)
    assertTrue(
      diagnostics.warnings.any {
        it == "seam=goal-status.worktree_edit_journal value_expected=latest_tick value_used=omitted"
      },
    )
    assertTrue(
      diagnostics.warnings.any {
        it == "seam=goal-status.audit_ac_retry_count value_expected=ledger_count value_used=omitted"
      },
    )
  }

  @Test
  fun `latest worktree edit and audit retry count project from measured durable state`() {
    val childWorkflowId = "wfl-child-measured"
    val journal =
      InMemoryStatusWorktreeEditJournalRepository().apply {
        append(
          childWorkflowId,
          WorktreeEditTick(
            recordedAt = Instant.parse("2026-09-18T12:00:00Z"),
            phaseId = "implement",
            source = WorktreeEditSource.WORKTREE_PROBE,
            entries =
              ('a'..'g').map { letter ->
                GoalObservabilityFileDiffStat(
                  path = "$letter.kt",
                  insertions = 1,
                  deletions = 2,
                )
              },
          ),
        )
      }
    val phaseDatabase = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val phaseRecorder = testPhaseRecorder(phaseDatabase, testWorkflowSnapshotValidator)
    phaseRecorder.openTestWorkflow(childWorkflowId, "session-measured")
    repeat(2) {
      phaseRecorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          workflowId = childWorkflowId,
          action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          attemptCount = 1,
          resolvedAgentId = "codex",
          blockedReason =
            FeatureTaskRuntimeContinuationKind.AUDIT_AC_RETRY.ledgerDetailForTest(),
          fixLoopIteration = 1,
        ),
      )
    }

    val measured =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore =
            InMemoryGoalManifestStore(
              manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = childWorkflowId),
            ),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = phaseRecorder,
          database = StatusJournalDatabaseSessionFactory(journal),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )
    val summary = requireNotNull(measured.latestWorktreeEdit)
    assertEquals(listOf("a.kt", "b.kt", "c.kt", "d.kt", "e.kt"), summary.pathSample)
    assertEquals(7, summary.netInsertions)
    assertEquals(14, summary.netDeletions)
    assertEquals(2, measured.auditAcRetryCount)
  }

  @Test
  fun `empty journal and non-audit ledger omit worktree edits and audit retry count`() {
    val childWorkflowId = "wfl-child-unmeasured"
    val zeroRetryDatabase = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val zeroRetryRecorder = testPhaseRecorder(zeroRetryDatabase, testWorkflowSnapshotValidator)
    zeroRetryRecorder.openTestWorkflow(childWorkflowId, "session-zero")
    zeroRetryRecorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = childWorkflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        attemptCount = 1,
        resolvedAgentId = "codex",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    val unmeasured =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore =
            InMemoryGoalManifestStore(
              manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = childWorkflowId),
            ),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = zeroRetryRecorder,
          database = StatusJournalDatabaseSessionFactory(InMemoryStatusWorktreeEditJournalRepository()),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )
    assertNull(unmeasured.latestWorktreeEdit)
    assertNull(unmeasured.auditAcRetryCount)
    assertFalse(unmeasured.degradedDurableRead)
  }
}

private fun auditRecord(status: WorkflowStepStatus = WorkflowStepStatus.COMPLETED) =
  FeatureTaskRuntimePhaseRecord(
    phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    status = status,
    attemptCount = 1,
    startedAt = Instant.EPOCH,
    resolvedAgentId = "codex",
  )

private class RecordingStatusDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private class InMemoryStatusWorktreeEditJournalRepository : WorktreeEditJournalRepository {
  private val latest = mutableMapOf<String, WorktreeEditTick>()

  override fun append(
    workflowId: String,
    tick: WorktreeEditTick,
  ) {
    latest[workflowId] = tick
  }

  override fun latestTick(workflowId: String): WorktreeEditTick? = latest[workflowId]

  override fun deleteTicksBefore(
    workflowId: String,
    recordedAt: Instant,
  ): Int = 0
}

private class StatusJournalDatabaseSessionFactory(
  private val journal: WorktreeEditJournalRepository,
) : DatabaseSessionFactory {
  override fun acquireDatabasePathProtection() =
    DatabasePathProtectionResult.Unavailable("Protection is not provided by this test double.")

  private val dbPath = Path.of("/fake/goal-status-worktree-edit.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T> =
    OwningTransactionResult.Committed(transaction(block))

  private fun unit(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@StatusJournalDatabaseSessionFactory.dbPath
      override val worktreeEditJournal: WorktreeEditJournalRepository = journal
      override val workflowStates: WorkflowStateRepository = InMemoryWorkflowStates()
      override val learnings: LearningRepository
        get() = error("unused")
      override val reviews: ReviewRepository
        get() = error("unused")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("unused")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("unused")
      override val telemetryOutbox: TelemetryOutboxRepository
        get() = error("unused")
      override val workList: WorkListRepository = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}
