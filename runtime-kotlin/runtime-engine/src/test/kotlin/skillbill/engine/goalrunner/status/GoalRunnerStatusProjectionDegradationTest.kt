package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.db.DatabaseSessionFactory
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
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
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
  fun `journal and audit retry read failures omit values and name both seams`() {
    val diagnostics = RecordingStatusDiagnostics()
    val throwingJournalDatabase =
      object : DatabaseSessionFactory {
        private val dbPath = Path.of("/fake/goal-status-journal-fail.db")

        override fun resolveDbPath(): Path = dbPath

        override fun databaseExists(): Boolean = true

        override fun <T> read(block: (UnitOfWork) -> T): T = error("journal unavailable")

        override fun <T> readIfPresent(block: (UnitOfWork) -> T): T? = error("journal unavailable")

        override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = error("unused")

        override fun <T> transaction(block: (UnitOfWork) -> T): T = error("unused")
      }
    val ledgerFailingStates =
      object : WorkflowStateRepository by InMemoryWorkflowStates() {
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
            FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX +
              FeatureTaskRuntimeContinuationKind.AUDIT_AC_RETRY.wireValue,
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

private class RecordingStatusDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

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

  override fun trimToCap(
    workflowId: String,
    maxRows: Int,
  ): Int = 0
}

private class StatusJournalDatabaseSessionFactory(
  private val journal: WorktreeEditJournalRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-status-worktree-edit.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@StatusJournalDatabaseSessionFactory.dbPath
      override val worktreeEditJournal: WorktreeEditJournalRepository = journal
      override val workflowStates: WorkflowStateRepository
        get() = error("unused")
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
