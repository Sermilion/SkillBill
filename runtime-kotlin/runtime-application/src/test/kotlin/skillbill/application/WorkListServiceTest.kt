package skillbill.application

import skillbill.application.work.WorkListService
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkListServiceTest {
  @Test
  fun `work list drops an unreadable task-runtime row and returns the remaining items`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workListRuntimeRecord("wftr-invalid-snapshot").copy(workflowName = "bill-feature-implement"),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    workflows.saveFeatureTaskWorkflow(workListRuntimeRecord("wftr-readable"), FeatureTaskWorkflowMode.RUNTIME)
    val validator =
      object : WorkflowSnapshotValidator {
        override fun validate(
          snapshot: WorkflowStateSnapshot,
          slug: String,
        ) {
          if (snapshot.workflowId == "wftr-invalid-snapshot") {
            throw invalidWorkflowStateSchemaError("Workflow '$slug' fails snapshot validation.")
          }
        }
      }
    val service =
      WorkListService(
        database =
          WorkListDatabase(
            workflows = workflows,
            work =
              listOf(
                workListItem("wftr-invalid-snapshot"),
                workListItem("wftr-readable"),
              ),
          ),
        workflowSnapshotValidator = validator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
      )

    val result = service.list()

    assertEquals(listOf("wftr-readable"), result.work.map { it.workflowId })
  }

  @Test
  fun `work list resolves every workflow snapshot through one family-keyed lookup`() {
    val delegate = InMemoryWorkflowStates()
    val workflows = BatchingWorkflowStates(delegate)
    val work =
      buildList {
        repeat(901) { index ->
          val workflowId = "wftr-batch-$index"
          delegate.saveFeatureTaskWorkflow(
            WorkflowStateRecord(
              workflowId = workflowId,
              sessionId = "ftr-batch-$index",
              workflowName = "bill-feature-task",
              contractVersion = "0.1",
              workflowStatus = WorkflowStatus.RUNNING.wireValue,
              currentStepId = "implement",
              stepsJson = "[]",
              artifactsJson = "{}",
              startedAt = "2026-05-01T12:00:00Z",
              updatedAt = "2026-05-01T12:00:00Z",
              finishedAt = null,
            ),
            FeatureTaskWorkflowMode.RUNTIME,
          )
          add(
            WorkItem(
              issueKey = "SKILL-117",
              workflowKind = WorkItemKind.FEATURE_TASK_RUNTIME,
              workflowId = workflowId,
              startedAt = Instant.parse("2026-05-01T12:00:00Z"),
              currentState = "running",
              stateEnteredAt = Instant.parse("2026-05-01T12:00:00Z"),
              stateEnteredAtEstimated = false,
            ),
          )
        }
      }
    val service =
      WorkListService(
        database = WorkListDatabase(workflows = workflows, work = work),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
      )

    val result = service.list()

    assertEquals(901, result.work.size)
    assertEquals(listOf(901), workflows.snapshotLookupSizes)
  }
}

private fun workListRuntimeRecord(workflowId: String): WorkflowStateRecord =
  WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "ftr-117",
    workflowName = "bill-feature-task",
    contractVersion = "0.1",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    currentStepId = "preplan",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = "2026-05-01T12:00:00Z",
    updatedAt = "2026-05-01T12:00:00Z",
    finishedAt = null,
  )

private fun workListItem(workflowId: String): WorkItem =
  WorkItem(
    issueKey = "SKILL-117",
    workflowKind = WorkItemKind.FEATURE_TASK_RUNTIME,
    workflowId = workflowId,
    startedAt = Instant.parse("2026-05-01T12:00:00Z"),
    currentState = "running",
    stateEnteredAt = Instant.parse("2026-05-01T12:00:00Z"),
    stateEnteredAtEstimated = false,
  )

private class WorkListDatabase(
  private val workflows: WorkflowStateRepository,
  private val work: List<WorkItem>,
) : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = Path.of("/fake/work-list.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = Path.of("/fake/work-list.db")
      override val workflowStates = workflows
      override val workList: WorkListRepository =
        object : WorkListRepository {
          override fun list(limit: Int?): List<WorkItem> = limit?.let(work::take) ?: work
        }
      override val learnings: LearningRepository
        get() = error("Not exercised by WorkListServiceTest.")
      override val reviews: ReviewRepository
        get() = error("Not exercised by WorkListServiceTest.")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("Not exercised by WorkListServiceTest.")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("Not exercised by WorkListServiceTest.")
      override val telemetryOutbox: TelemetryOutboxRepository
        get() = error("Not exercised by WorkListServiceTest.")
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private class BatchingWorkflowStates(
  private val delegate: WorkflowStateRepository,
) : WorkflowStateRepository by delegate {
  val snapshotLookupSizes = mutableListOf<Int>()

  override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> {
    snapshotLookupSizes += workflowIds.size
    return delegate.getAll(family, workflowIds)
  }
}
