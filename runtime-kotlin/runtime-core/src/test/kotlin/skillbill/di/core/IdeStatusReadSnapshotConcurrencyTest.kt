package skillbill.di.core

import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.work.IdeStatusProjector
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.NoopIdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeStatusReadSnapshotConcurrencyTest {
  private val observedAt: Instant = Instant.parse("2026-08-06T12:00:00Z")

  @Test
  fun `a writer commit landing anywhere during collection never hides a live running goal`() {
    (1..INTERLEAVE_POINTS).forEach { interleaveAfterCall ->
      val fixture = fixture("ide-status-snapshot-$interleaveAfterCall")

      val result = fixture.status(interleaveAfterCall)

      assertTrue(
        fixture.interleaved,
        "Interleave point $interleaveAfterCall never fired; the collection issued fewer statements.",
      )
      assertNull(
        result.snapshot.problem?.code,
        "Interleave point $interleaveAfterCall reported ${result.snapshot.problem?.code} for a live goal.",
      )
      assertEquals(GOAL_WORKFLOW_ID, result.snapshot.workflowId)
      assertEquals(0, result.exitCode)
    }
  }

  @Test
  fun `the interleaved mutation is outcome-changing when it lands before the snapshot opens`() {
    val fixture = fixture("ide-status-snapshot-control")
    fixture.clearGoalBinding()

    val result = fixture.status(interleaveAfterCall = null)

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(0, result.exitCode)
  }

  private fun fixture(prefix: String): SnapshotFixture {
    val home = Files.createTempDirectory("$prefix-home")
    val repoRoot = Files.createTempDirectory("$prefix-repo")
    Files.createDirectory(repoRoot.resolve(".git"))
    Files.writeString(repoRoot.resolve(".git").resolve("HEAD"), "ref: refs/heads/feat/$ISSUE_KEY-snapshot\n")
    val dbPath = home.resolve("metrics.db")
    val database =
      sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = dbPath.toString(), environment = emptyMap())
    val identity = "$REPOSITORY_IDENTITY_PREFIX${repoRoot.toRealPath()}"
    seed(dbPath, database, identity)
    return SnapshotFixture(home, repoRoot, dbPath, database, observedAt)
  }

  private fun seed(
    dbPath: Path,
    database: SQLiteDatabaseSessionFactory,
    repositoryIdentity: String,
  ) {
    ensureTestDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, first_started_at, status, state_entered_at, state_entered_at_estimated
        ) VALUES (?, ?, ?, 'running', ?, 0)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, GOAL_WORKFLOW_ID)
        statement.setString(2, ISSUE_KEY)
        statement.setString(3, "2026-08-06T08:00:00Z")
        statement.setString(4, "2026-08-06T11:00:00Z")
        statement.executeUpdate()
      }
    }
    database.transaction { unitOfWork ->

      unitOfWork.workflowStates.saveFeatureTaskWorkflow(foreignChildWorkflow(), FeatureTaskWorkflowMode.RUNTIME)
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(foreignChildIdentity())
      unitOfWork.goalRunnerControls.persistControlState(
        GOAL_WORKFLOW_ID,
        GoalRunnerControlState(repositoryIdentity = repositoryIdentity),
      )
    }
  }

  private fun foreignChildWorkflow(): WorkflowStateRecord =
    WorkflowStateRecord(
      workflowId = FOREIGN_CHILD_WORKFLOW_ID,
      sessionId = "ftr-$FOREIGN_CHILD_WORKFLOW_ID",
      workflowName = "bill-feature-task",
      contractVersion = "1.0",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "implement",
      stepsJson = "[]",
      artifactsJson = "{}",
      startedAt = "2026-08-06T09:00:00Z",
      updatedAt = "2026-08-06T09:00:00Z",
      finishedAt = null,
      issueKey = ISSUE_KEY,
      mode = FeatureTaskWorkflowMode.RUNTIME,
    )

  private fun foreignChildIdentity(): FeatureTaskExecutionIdentity =
    FeatureTaskExecutionIdentity(
      workflowId = FOREIGN_CHILD_WORKFLOW_ID,
      normalizedIssueKey = ISSUE_KEY,
      repositoryIdentity = "${REPOSITORY_IDENTITY_PREFIX}/other-repo",
      governedSpecPath = "spec.md",
      mode = FeatureTaskWorkflowMode.RUNTIME,
      routeScope = FeatureTaskRouteScope.GOAL_CHILD,
    )

  private companion object {
    const val ISSUE_KEY = "SKILL-999"
    const val GOAL_WORKFLOW_ID = "goal-snapshot"
    const val FOREIGN_CHILD_WORKFLOW_ID = "wfl-foreign-child"

    const val REPOSITORY_IDENTITY_PREFIX = "repo-root-realpath-v1:"

    const val INTERLEAVE_POINTS = 5
  }
}

private class SnapshotFixture(
  private val home: Path,
  private val repoRoot: Path,
  private val dbPath: Path,
  private val database: SQLiteDatabaseSessionFactory,
  private val observedAt: Instant,
) {
  var interleaved: Boolean = false
    private set

  fun clearGoalBinding() {
    database.transaction { unitOfWork ->
      unitOfWork.goalRunnerControls.persistControlState("goal-snapshot", GoalRunnerControlState())
    }
  }

  fun status(interleaveAfterCall: Int?): IdeStatusResult {
    val instrumented =
      InterleavingDatabase(database, interleaveAfterCall) {
        interleaved = true

        clearGoalBinding()
      }
    return service(instrumented).status(
      IdeStatusRequest(repoRoot = repoRoot.toString(), observedAt = observedAt),
    )
  }

  private fun service(database: DatabaseSessionFactory): IdeStatusService {
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = home),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    return IdeStatusService(
      database = database,
      projector =
        IdeStatusProjector(
          workflowSnapshotValidator = NoopSnapshotValidator,
          goalRunnerStatusService = component.goalRunnerStatusService,
          featureTaskRuntimeStatusService = component.featureTaskRuntimeStatusService,
          diagnostics = NoopRuntimeDiagnostics,
        ),
      ideStatusValidator = NoopIdeStatusValidator,
      branchSource = CheckedOutBranchSource { "feat/SKILL-999-snapshot" },
      clock = Clock.fixed(observedAt, ZoneOffset.UTC),
      repositoryEnclosingRootPort = CanonicalRepositoryRoot,
      manifestStore = EmptyManifestStore,
    )
  }
}

private object EmptyManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = error("Unused in the IDE status snapshot fixture.")

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = error("Unused in the IDE status snapshot fixture.")

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = error("Unused in the IDE status snapshot fixture.")

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState =
    error("Unused in the IDE status snapshot fixture.")
}

private object NoopSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

private class InterleavingDatabase(
  private val delegate: DatabaseSessionFactory,
  private val interleaveAfterCall: Int?,
  private val onInterleave: () -> Unit,
) : DatabaseSessionFactory by delegate {
  override fun <T> read(block: (UnitOfWork) -> T): T {
    var calls = 0
    val trigger: () -> Unit = {
      calls += 1
      if (calls == interleaveAfterCall) onInterleave()
    }
    return delegate.read { unitOfWork -> block(InterleavingUnitOfWork(unitOfWork, trigger)) }
  }
}

private class InterleavingUnitOfWork(
  private val delegate: UnitOfWork,
  private val trigger: () -> Unit,
) : UnitOfWork by delegate {
  override val workList: WorkListRepository = CountingWorkList(delegate.workList, trigger)
  override val workflowStates: WorkflowStateRepository = CountingWorkflowStates(delegate.workflowStates, trigger)
  override val goalRunnerControls: GoalRunnerControlRepository =
    CountingGoalRunnerControls(delegate.goalRunnerControls, trigger)
}

private class CountingWorkList(
  private val delegate: WorkListRepository,
  private val trigger: () -> Unit,
) : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> = delegate.list(limit).also { trigger() }
}

private class CountingWorkflowStates(
  private val delegate: WorkflowStateRepository,
  private val trigger: () -> Unit,
) : WorkflowStateRepository by delegate {
  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    delegate.getFeatureTaskExecutionIdentity(workflowId).also { trigger() }

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ) = delegate.findGoalChildFeatureTaskCandidates(normalizedIssueKey, repositoryIdentity).also { trigger() }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    delegate.countGoalChildIdentities(normalizedIssueKey).also { trigger() }
}

private class CountingGoalRunnerControls(
  private val delegate: GoalRunnerControlRepository,
  private val trigger: () -> Unit,
) : GoalRunnerControlRepository by delegate {
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    delegate.controlState(parentWorkflowId).also { trigger() }
}
