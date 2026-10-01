package skillbill.engine.goalrunner.planning.recovery

import org.junit.jupiter.api.Test
import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalPlanningRefreshLivenessTest {
  private val now = Instant.parse("2026-08-11T00:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  @Test
  fun `no child workflow id is IDLE without consulting a parent lease`() {
    val harness = RefreshLivenessHarness(clock)
    val state = manifestState(childWorkflowId = null)

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state))
    assertEquals(
      ExecutionLiveness.IDLE,
      resolveChildExecutionLiveness(
        state.manifest.subtasks.first(),
        harness.recorder.phaseQuery,
        clock,
        NoopRuntimeDiagnostics,
      ),
    )
  }

  @Test
  fun `blank child workflow id is IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    val state = manifestState(childWorkflowId = "  ")

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state))
  }

  @Test
  fun `current child with unexpired RUNTIME lease is LIVE`() {
    val harness = RefreshLivenessHarness(clock)
    harness.seedRuntimeChild("wfl-child", expiresAt = now.plusSeconds(60).toString())
    val state = manifestState(childWorkflowId = "wfl-child")

    assertEquals(ExecutionLiveness.LIVE, harness.liveness.resolve(state))
    assertEquals(
      "Goal 'SKILL-56' is live; refuse shared-preplan refresh while the current child run is active.",
      refuseRefreshReason("SKILL-56", ExecutionLiveness.LIVE),
    )
  }

  @Test
  fun `current child with expired RUNTIME lease is IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    harness.seedRuntimeChild("wfl-child", expiresAt = now.minusSeconds(1).toString())
    val state = manifestState(childWorkflowId = "wfl-child")

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state))
    assertNull(refuseRefreshReason("SKILL-56", ExecutionLiveness.IDLE))
  }

  @Test
  fun `current child whose workflow mode is not RUNTIME is UNKNOWN`() {
    val harness = RefreshLivenessHarness(clock)

    val state = manifestState(childWorkflowId = "wfl-missing")

    assertEquals(ExecutionLiveness.UNKNOWN, harness.liveness.resolve(state))
    assertTrue(
      refuseRefreshReason("SKILL-56", ExecutionLiveness.UNKNOWN)!!
        .contains("unknown execution liveness"),
    )
  }

  @Test
  fun `corrupt child row degrades to UNKNOWN and records the seam`() {
    val harness = RefreshLivenessHarness(clock)
    harness.repository.readFailure = IllegalStateException("malformed feature_task_runtime row")
    val state = manifestState(childWorkflowId = "wfl-child")

    assertEquals(ExecutionLiveness.UNKNOWN, harness.liveness.resolve(state))
    assertNotNull(refuseRefreshReason("SKILL-56", ExecutionLiveness.UNKNOWN))
    val warning = harness.diagnostics.warnings.single()
    assertTrue(warning.contains("goal-planning.child_execution_liveness"), warning)
    assertTrue(warning.contains("expected live_or_idle"), warning)
    assertTrue(warning.contains("used unknown"), warning)
  }

  @Test
  fun `interrupt during the child read is rethrown with the interrupt flag set`() {
    val harness = RefreshLivenessHarness(clock)
    harness.repository.readFailure = InterruptedException("read interrupted")
    val state = manifestState(childWorkflowId = "wfl-child")
    Thread.interrupted()

    assertFailsWith<InterruptedException> { harness.liveness.resolve(state) }
    assertTrue(Thread.interrupted())
    assertTrue(harness.diagnostics.warnings.isEmpty())
  }

  @Test
  fun `intent naming a missing subtask falls through to IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    val base = manifest(subtaskCount = 1)
    val state =
      GoalRunnerManifestState(
        parentWorkflowId = "wfl-parent",
        dbPath = "/tmp/refresh-liveness.db",
        manifest = base.copy(currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 9, action = "resume")),
      )

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state))
  }

  private fun manifestState(childWorkflowId: String?): GoalRunnerManifestState {
    val base = manifest(subtaskCount = 1)
    val subtask =
      base.subtasks.single().let { row ->
        if (childWorkflowId == null) row else row.copy(workflowId = childWorkflowId)
      }
    return GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = "/tmp/refresh-liveness.db",
      manifest =
        base.copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks = listOf(subtask),
        ),
    )
  }
}

private class RefreshLivenessHarness(clock: Clock) {
  val repository = SeedableRefreshLivenessWorkflowStates()
  private val database = SeedableRefreshLivenessDatabase(repository)
  val recorder =
    featureTaskRuntimePhaseRecorder(
      database,
      NoopRefreshLivenessSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )
  val diagnostics = RecordingRefreshLivenessDiagnostics()
  val liveness = ChildAwareGoalPlanningRefreshLiveness(recorder.phaseQuery, clock, diagnostics)

  fun seedRuntimeChild(
    workflowId: String,
    expiresAt: String,
  ) {
    repository.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "session",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = "implement",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    repository.seedOwnership(workflowId, expiresAt)
  }
}

private class RecordingRefreshLivenessDiagnostics : RuntimeDiagnostics {
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

private object NoopRefreshLivenessSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

private class SeedableRefreshLivenessDatabase(
  private val repository: SeedableRefreshLivenessWorkflowStates,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-planning-refresh-liveness.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@SeedableRefreshLivenessDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by refresh liveness tests")
      override val learnings: LearningRepository get() = error("unused by refresh liveness tests")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by refresh liveness tests")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("unused by refresh liveness tests")
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by refresh liveness tests")
      override val workflowStates: WorkflowStateRepository = repository
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private class SeedableRefreshLivenessWorkflowStates : WorkflowStateRepositoryDefaults() {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val ownershipRows = linkedMapOf<String, FeatureTaskRuntimeWorkerOwnership>()
  var readFailure: Throwable? = null

  fun seedOwnership(
    workflowId: String,
    expiresAt: String,
  ) {
    ownershipRows[workflowId] =
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = workflowId,
        ownerToken = "owner-token-123456",
        generation = 1,
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1234,
        processBirthToken = "birth-1234",
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        phaseId = "implement",
        phaseAttempt = 1,
        heartbeatAt = "2026-08-11T00:00:00Z",
        expiresAt = expiresAt,
      )
  }

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ) = emptyList<FeatureTaskWorkflowCandidate>()

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? {
    readFailure?.let { failure -> throw failure }
    return taskRuntimeRows[workflowId]
  }

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = saveRecord(family, snapshot.toRecord(taskRuntimeRows[snapshot.workflowId]))

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    taskRuntimeRows[record.workflowId] = record
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = taskRuntimeRows[workflowId]?.toSnapshot()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    taskRuntimeRows.values.toList().asReversed().take(limit).map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    ownershipRows[workflowId]
}
