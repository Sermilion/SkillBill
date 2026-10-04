package skillbill.application

import java.nio.file.Path
import java.time.Instant
import skillbill.application.work.WorkListService
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkListServiceTest {
  @Test
  fun `work list invokes the workflow snapshot validation read seam before returning a workflow row`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = "wftr-invalid-snapshot",
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
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    val validator =
      object : WorkflowSnapshotValidator {
        override fun validate(
          snapshot: WorkflowStateSnapshot,
          slug: String,
        ): Unit = throw invalidWorkflowStateSchemaError("Workflow '$slug' fails snapshot validation.")
      }
    val service =
      WorkListService(
        database =
          WorkListDatabase(
            workflows = workflows,
            work =
              listOf(
                WorkItem(
                  issueKey = "SKILL-117",
                  workflowKind = WorkItemKind.FEATURE_TASK_RUNTIME,
                  workflowId = "wftr-invalid-snapshot",
                  startedAt = Instant.parse("2026-05-01T12:00:00Z"),
                  currentState = "running",
                  stateEnteredAt = Instant.parse("2026-05-01T12:00:00Z"),
                  stateEnteredAtEstimated = false,
                ),
              ),
          ),
        workflowSnapshotValidator = validator,
      )

    assertFailsWith<SkillBillRuntimeException> {
      service.list()
    }.also {
      assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code)
    }
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
      )

    val result = service.list()

    assertEquals(901, result.work.size)
    assertEquals(listOf(901), workflows.snapshotLookupSizes)
  }
}

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
