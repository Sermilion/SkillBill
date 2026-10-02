package skillbill.engine.worktreeedit

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreeEditJournalWriterTest {
  @Test
  fun `observe persists changed paths once, skips runtime-private paths, and dedups across restart`() {
    val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
    val repository = InMemoryWorktreeEditJournalRepository()
    val database = JournalDatabaseSessionFactory(repository)
    val git =
      ScriptedWorkflowGitOperations(
        WorkflowWorktreeNumstatResult(
          status = WorkflowGitOperationStatus.OK,
          files =
            listOf(
              GoalObservabilityFileDiffStat("src/A.kt", insertions = 2, deletions = 1),
              GoalObservabilityFileDiffStat(".skill-bill/config.yaml", insertions = 4, deletions = 0),
            ),
        ),
      )
    val writer = WorktreeEditJournalWriter(database, clock, NoopJournalDiagnostics, git)
    val repoRoot = Path.of("/tmp/worktree-edit-journal")
    val observer = writer.observer(repoRoot, { "wfl-a" }, { "implement" })

    observer.observe()
    assertEquals(1, repository.appends.size)
    val first = repository.appends.single()
    assertEquals("wfl-a", first.workflowId)
    assertEquals("implement", first.tick.phaseId)
    assertEquals(WorktreeEditSource.WORKTREE_PROBE, first.tick.source)
    assertEquals(
      listOf(GoalObservabilityFileDiffStat("src/A.kt", insertions = 2, deletions = 1)),
      first.tick.entries,
    )

    observer.observe()
    assertEquals(1, repository.appends.size)

    val restarted = WorktreeEditJournalWriter(database, clock, NoopJournalDiagnostics, git)
    restarted.observer(repoRoot, { "wfl-a" }, { "implement" }).observe()
    assertEquals(1, repository.appends.size)

    git.result =
      WorkflowWorktreeNumstatResult(
        status = WorkflowGitOperationStatus.OK,
        files = listOf(GoalObservabilityFileDiffStat("src/B.kt", insertions = 3, deletions = 0)),
      )
    observer.observe()
    assertEquals(2, repository.appends.size)
    assertEquals(
      listOf(GoalObservabilityFileDiffStat("src/B.kt", insertions = 3, deletions = 0)),
      repository.appends.last().tick.entries,
    )
  }

  @Test
  fun `cap truncation emits diagnostics and store failures stay inside the observer`() {
    val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
    val repoRoot = Path.of("/tmp/worktree-edit-journal-cap")
    val git =
      ScriptedWorkflowGitOperations(
        WorkflowWorktreeNumstatResult(
          status = WorkflowGitOperationStatus.OK,
          files = listOf(GoalObservabilityFileDiffStat("src/A.kt", insertions = 1, deletions = 0)),
        ),
      )

    val capDiagnostics = RecordingJournalDiagnostics()
    val cappingRepository =
      object : WorktreeEditJournalRepository {
        override fun append(
          workflowId: String,
          tick: WorktreeEditTick,
        ) = Unit

        override fun latestTick(workflowId: String): WorktreeEditTick? = null

        override fun trimToCap(
          workflowId: String,
          maxRows: Int,
        ): Int = 3
      }
    WorktreeEditJournalWriter(
      JournalDatabaseSessionFactory(cappingRepository),
      clock,
      capDiagnostics,
      git,
    ).observer(repoRoot, { "wfl-cap" }, { "implement" }).observe()
    assertEquals(1, capDiagnostics.warnings.size)
    assertTrue(capDiagnostics.warnings.single().contains("seam=worktree_edit_journal_cap"))

    val persistDiagnostics = RecordingJournalDiagnostics()
    val failingRepository =
      object : WorktreeEditJournalRepository {
        override fun append(
          workflowId: String,
          tick: WorktreeEditTick,
        ) {
          error("journal write failed")
        }

        override fun latestTick(workflowId: String): WorktreeEditTick? = null

        override fun trimToCap(
          workflowId: String,
          maxRows: Int,
        ): Int = 0
      }
    WorktreeEditJournalWriter(
      JournalDatabaseSessionFactory(failingRepository),
      clock,
      persistDiagnostics,
      git,
    ).observer(repoRoot, { "wfl-fail" }, { "implement" }).observe()
    assertEquals(1, persistDiagnostics.warnings.size)
    assertTrue(persistDiagnostics.warnings.single().contains("seam=worktree_edit_journal_persist"))
  }

  @Test
  fun `sqlite busy write failure records failure without publishing a tick`() {
    val repository = InMemoryWorktreeEditJournalRepository()
    val diagnostics = RecordingJournalDiagnostics()
    val database = JournalDatabaseSessionFactory(repository, busyWrites = 3)
    val git =
      ScriptedWorkflowGitOperations(
        WorkflowWorktreeNumstatResult(
          status = WorkflowGitOperationStatus.OK,
          files = listOf(GoalObservabilityFileDiffStat("src/A.kt", insertions = 1, deletions = 0)),
        ),
      )

    WorktreeEditJournalWriter(
      database,
      Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
      diagnostics,
      git,
    ).observer(Path.of("/tmp/worktree-edit-journal-busy"), { "wfl-busy" }, { "implement" }).observe()

    assertEquals(1, database.writeAttempts)
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(diagnostics.warnings.single().contains("seam=worktree_edit_journal_persist"))
    assertTrue(repository.appends.isEmpty())
  }
}

private class ScriptedWorkflowGitOperations(
  var result: WorkflowWorktreeNumstatResult,
) : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult = result
}

private class InMemoryWorktreeEditJournalRepository : WorktreeEditJournalRepository {
  data class Appended(val workflowId: String, val tick: WorktreeEditTick)

  val appends = mutableListOf<Appended>()
  private val latest = mutableMapOf<String, WorktreeEditTick>()

  override fun append(
    workflowId: String,
    tick: WorktreeEditTick,
  ) {
    appends += Appended(workflowId, tick)
    latest[workflowId] = tick
  }

  override fun latestTick(workflowId: String): WorktreeEditTick? = latest[workflowId]

  override fun trimToCap(
    workflowId: String,
    maxRows: Int,
  ): Int = 0
}

private class JournalDatabaseSessionFactory(
  private val journal: WorktreeEditJournalRepository,
  private val busyWrites: Int = 0,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/worktree-edit-journal.db")
  private val writes = AtomicInteger(0)
  val writeAttempts: Int
    get() = writes.get()

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T {
    val write = writes.incrementAndGet()
    if (write <= busyWrites) {
      error("self-managed write lock contended")
    }
    return block(unit())
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@JournalDatabaseSessionFactory.dbPath
      override val worktreeEditJournal: WorktreeEditJournalRepository = journal
      override val workflowStates: WorkflowStateRepository
        get() = error("Workflow states are not exercised.")
      override val learnings: LearningRepository
        get() = error("Learnings are not exercised.")
      override val reviews: ReviewRepository
        get() = error("Reviews are not exercised.")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("Lifecycle telemetry is not exercised.")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("Telemetry reconciliation is not exercised.")
      override val telemetryOutbox: TelemetryOutboxRepository
        get() = error("Telemetry outbox is not exercised.")
      override val workList: WorkListRepository
        get() = error("Work list is not exercised.")
      override val goalPlanningPreparations: GoalPlanningPreparationRepository
        get() = error("Goal planning preparations are not exercised.")
      override val goalRunnerControls: GoalRunnerControlRepository
        get() = error("Goal runner controls are not exercised.")
    }
}

private object NoopJournalDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private class RecordingJournalDiagnostics : RuntimeDiagnostics {
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
