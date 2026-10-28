package skillbill.engine.featuretask.lifecycle.core

import skillbill.contracts.JsonCodec
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.lang.Boolean.TYPE
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.lang.Double.TYPE as DoubleTYPE
import java.lang.Long.TYPE as LongTYPE

internal val featureTaskGitIntegrationSnapshotValidator: WorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }

internal class FeatureTaskGitIntegrationWorkflowRepository : WorkflowStateRepositoryDefaults() {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return JsonCodec.parseObjectOrNull(record.artifactsJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      .orEmpty()
  }

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    taskRuntimeRows[row.workflowId] = row
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

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) = null
}

internal class FeatureTaskGitIntegrationDatabase(
  private val repository: FeatureTaskGitIntegrationWorkflowRepository,
) : DatabaseSessionFactory {
  override fun acquireDatabasePathProtection() =
    DatabasePathProtectionResult.Unavailable("Protection is not provided by this test double.")

  override fun <T> owningTransaction(block: (UnitOfWork) -> T) =
    OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)

  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@FeatureTaskGitIntegrationDatabase.dbPath
      override val reviews: ReviewRepository = noopPort(ReviewRepository::class.java)
      override val learnings: LearningRepository = noopPort(LearningRepository::class.java)
      override val lifecycleTelemetry: LifecycleTelemetryRepository =
        noopPort(LifecycleTelemetryRepository::class.java)
      override val telemetryReconciliation: TelemetryReconciliationRepository =
        noopPort(TelemetryReconciliationRepository::class.java)
      override val telemetryOutbox: TelemetryOutboxRepository = noopPort(TelemetryOutboxRepository::class.java)
      override val workflowStates: WorkflowStateRepository = repository
      override val workList: WorkListRepository = noopPort(WorkListRepository::class.java)
      override val goalPlanningPreparations: GoalPlanningPreparationRepository =
        noopPort(GoalPlanningPreparationRepository::class.java)
      override val goalRunnerControls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository
    }
}

private fun <T> noopPort(type: Class<T>): T =
  type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
      defaultPortReturn(method)
    },
  )

private fun defaultPortReturn(method: Method): Any? =
  when {
    method.returnType == Void.TYPE -> null
    List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
    Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
    method.returnType == TYPE -> false
    method.returnType == Integer.TYPE -> 0
    method.returnType == LongTYPE -> 0L
    method.returnType == DoubleTYPE -> 0.0
    else -> null
  }
