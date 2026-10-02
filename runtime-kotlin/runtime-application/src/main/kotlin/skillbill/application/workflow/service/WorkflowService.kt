package skillbill.application.workflow.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.clearDecompositionManifestProjectionFailure
import skillbill.application.decomposition.model.DecompositionManifestProjectionFailurePersistence
import skillbill.application.decomposition.model.RetryDecompositionManifestProjectionArgs
import skillbill.application.decomposition.persistDecompositionManifestProjectionFailure
import skillbill.application.decomposition.retryDecompositionManifestProjectionFromAuthoritativeState
import skillbill.application.workflow.decomposition.DecompositionWorkflowContinuation
import skillbill.application.workflow.decomposition.PendingDecompositionProjection
import skillbill.application.workflow.decomposition.continueExistingWorkflow
import skillbill.application.workflow.decomposition.resolveDecompositionProjectionOwner
import skillbill.application.workflow.model.BuildFeatureTaskExecutionIdentityArgs
import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.application.workflow.model.FeatureTaskIdentityRepairArgs
import skillbill.application.workflow.model.PersistOpenedWorkflowArgs
import skillbill.application.workflow.model.RepairFeatureTaskRuntimeIdentityArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.WorkflowPersistenceContext
import skillbill.application.workflow.persist.buildFeatureTaskExecutionIdentity
import skillbill.application.workflow.persist.buildUpdateOk
import skillbill.application.workflow.persist.generateWorkflowId
import skillbill.application.workflow.persist.incompleteFeatureTaskIdentityError
import skillbill.application.workflow.persist.persistOpenedWorkflow
import skillbill.application.workflow.persist.resolveEffectiveSessionId
import skillbill.application.workflow.persist.toWorkflowUpdateInput
import skillbill.application.workflow.persist.withGoalObservabilityArtifacts
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.isGoalContinuationChildWorkflow
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import java.time.Clock
import kotlin.random.Random

@Inject
class WorkflowService(
  private val database: DatabaseSessionFactory,
  private val gitOperations: WorkflowGitOperations,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestWriter: DecompositionManifestWriter,
  private val repositoryRoot: RepositoryRoot,
  private val goalObservabilityEventValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val runtimeDiagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  private val workflowIdRandom = Random.Default
  private val engine = WorkflowEngine()
  private val featureTaskAbandon =
    WorkflowServiceFeatureTaskAbandon(engine, clock, ::repositoryCheckpointIdentity)
  private val blockedPhaseRetry =
    WorkflowServiceBlockedPhaseRetry(
      engine,
      decompositionManifestValidator,
      decompositionManifestStore,
      decompositionManifestWriter,
      repositoryRoot,
      runtimeDiagnostics,
      clock,
      ::repositoryCheckpointIdentity,
    )
  private val featureTaskIdentityRepair =
    WorkflowServiceFeatureTaskIdentityRepair(engine, clock, ::repositoryCheckpointIdentity)

  private fun repositoryCheckpointIdentity(): String {
    val resolved = gitOperations.repositoryFingerprint(repositoryRoot.path)
    check(resolved is WorkflowGitOperationResult.Ok) { resolved.error }
    return resolved.value.orEmpty()
  }

  fun open(args: WorkflowServiceOpenArgs): WorkflowOpenResult {
    incompleteFeatureTaskIdentityError(args)?.let { return it }
    val family = args.kind.workflowFamily()
    val stepId = args.currentStepId ?: family.definition.defaultInitialStepId
    val workflowId = generateWorkflowId(family.definition.workflowIdPrefix, clock, workflowIdRandom)
    val effectiveSessionId =
      resolveEffectiveSessionId(
        args.kind,
        args.sessionId,
        family.definition,
        workflowId,
      )
    val hasIdentityCoordinates = args.repositoryIdentity != null || args.governedSpecPath != null
    val executionIdentity =
      buildFeatureTaskExecutionIdentity(
        BuildFeatureTaskExecutionIdentityArgs(
          kind = args.kind,
          hasIdentityCoordinates = hasIdentityCoordinates,
          workflowId = workflowId,
          issueKey = args.issueKey,
          repositoryIdentity = args.repositoryIdentity,
          governedSpecPath = args.governedSpecPath,
          routeScope = args.routeScope,
        ),
      )
    return try {
      persistOpenedWorkflow(
        PersistOpenedWorkflowArgs(
          family = family,
          workflowId = workflowId,
          effectiveSessionId = effectiveSessionId,
          stepId = stepId,
          issueKey = args.issueKey,
          executionIdentity = executionIdentity,
          executionPlan = args.executionPlan,
          engine = engine,
          workflowSnapshotValidator = workflowSnapshotValidator,
          repositoryCheckpointIdentity = ::repositoryCheckpointIdentity,
          database = database,
        ),
      ).withGoalObservability()
    } catch (error: InvalidWorkflowStateSchemaError) {
      WorkflowOpenResult.Error(workflowId, error.message.orEmpty())
    }
  }

  private fun WorkflowOpenResult.withGoalObservability(): WorkflowOpenResult =
    when (this) {
      is WorkflowOpenResult.Ok -> copy(goalObservability = goalObservabilityOf(snapshot))
      is WorkflowOpenResult.Error -> this
    }

  private fun goalObservabilityOf(snapshot: WorkflowSnapshotView): GoalObservabilityEvent? =
    goalObservabilityLatestEventFromArtifacts(snapshot.artifacts)

  fun update(
    kind: WorkflowFamilyKind,
    request: WorkflowUpdateRequest,
  ): WorkflowUpdateResult {
    val family = kind.workflowFamily()
    val input =
      try {
        request.toWorkflowUpdateInput().copy(terminalInstant = clock.instant())
      } catch (error: InvalidWorkflowStateSchemaError) {
        return WorkflowUpdateResult.Error(request.workflowId, error.message.orEmpty())
      }
    val persisted =
      try {
        database.transaction { unitOfWork ->
          persistUpdate(family, request, input, unitOfWork)
        }
      } catch (error: InvalidWorkflowStateSchemaError) {
        return WorkflowUpdateResult.Error(request.workflowId, error.message.orEmpty())
      }
    persisted.pendingProjection?.let { pending ->
      reconcileDecompositionManifestProjectionAfterCommit(pending)
    }
    return persisted.result
  }

  fun retryDecompositionManifestProjection(workflowId: String): DecompositionManifestProjectionOutcome =
    retryDecompositionManifestProjectionFromAuthoritativeState(
      RetryDecompositionManifestProjectionArgs(
        database = database,
        engine = engine,
        decompositionManifestWriter = decompositionManifestWriter,
        decompositionManifestValidator = decompositionManifestValidator,
        decompositionManifestStore = decompositionManifestStore,
        repoRoot = repositoryRoot.path,
        workflowId = workflowId,
      ),
    )

  private fun persistUpdate(
    family: WorkflowFamily,
    request: WorkflowUpdateRequest,
    input: WorkflowUpdateInput,
    unitOfWork: UnitOfWork,
  ): WorkflowUpdatePersistence {
    val existing =
      unitOfWork.workflowStates.get(family, request.workflowId)
        ?: return WorkflowUpdatePersistence(
          WorkflowUpdateResult.Error(
            request.workflowId,
            "Unknown workflow_id '${request.workflowId}'.",
          ),
          pendingProjection = null,
        )
    workflowSnapshotValidator.validate(existing, family.definition.workflowName)
    val runtimeInput =
      family.withDecompositionRuntime(
        DecompositionRuntimeWriteArgs(
          existing = existing,
          input = input,
          planningResult = request.planningResult,
          workflowId = request.workflowId,
          validator = decompositionManifestValidator,
          fileStore = decompositionManifestStore,
          repoRoot = repositoryRoot.path,
          manifestWriter = decompositionManifestWriter,
        ),
      )
    val effectiveInput =
      runtimeInput.input.withGoalObservabilityArtifacts(
        existing = existing,
        workflowId = request.workflowId,
        validator = goalObservabilityEventValidator,
        gitOperations = gitOperations,
        repoRoot = repositoryRoot.path,
      )
    val updatedRecord = engine.updateRecord(family.definition, existing, effectiveInput)
    workflowSnapshotValidator.validate(updatedRecord, family.definition.workflowName)
    unitOfWork.workflowStates.save(family, updatedRecord)
    val updated = unitOfWork.workflowStates.get(family, request.workflowId) ?: updatedRecord
    if (runtimeInput.updated) {
      engine.syncDecompositionParentRuntime(
        family,
        updated,
        request.workflowId,
        unitOfWork,
        decompositionManifestValidator,
      )
    }
    return WorkflowUpdatePersistence(
      result =
        buildUpdateOk(
          engine,
          family.definition,
          updated,
          effectiveInput,
          WorkflowPersistenceContext(
            dbPath = unitOfWork.dbPath.toString(),
            repositoryCheckpointIdentity = ::repositoryCheckpointIdentity,
          ),
        ),
      pendingProjection = pendingDecompositionProjection(runtimeInput, updated, request, unitOfWork),
    )
  }

  private fun pendingDecompositionProjection(
    runtimeInput: DecompositionRuntimeInput,
    updated: WorkflowStateSnapshot,
    request: WorkflowUpdateRequest,
    unitOfWork: UnitOfWork,
  ): PendingDecompositionProjection? {
    if (!runtimeInput.updated) return null
    val ownerWorkflowId = resolveDecompositionProjectionOwner(updated, unitOfWork)
    if (ownerWorkflowId == null) {
      runtimeDiagnostics.warning(
        "seam=decomposition_projection_settlement value_expected=projection_owner_workflow_id " +
          "value_used=absent workflow_id=${request.workflowId}",
      )
      return null
    }
    return PendingDecompositionProjection(
      ownerWorkflowId = ownerWorkflowId,
      artifacts = updated.artifacts,
    )
  }

  fun abandonFeatureTaskRuntime(
    workflowId: String,
    reason: String,
  ): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Abandonment reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    return database.transaction { unitOfWork ->
      val existingRecord =
        unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)
          ?: return@transaction WorkflowUpdateResult.Error(
            workflowId,
            "Unknown feature-task workflow_id '$workflowId'.",
            unitOfWork.dbPath.toString(),
          )
      when (existingRecord.mode) {
        FeatureTaskWorkflowMode.RUNTIME ->
          featureTaskAbandon.abandonRuntimeFeatureTask(
            unitOfWork,
            existingRecord.toSnapshot(),
            normalizedReason,
          )
        FeatureTaskWorkflowMode.PROSE, null ->
          featureTaskAbandon.abandonLegacyProseFeatureTask(
            unitOfWork,
            existingRecord,
            normalizedReason,
          )
      }
    }
  }

  fun retryBlockedFeatureTaskRuntimePhase(
    workflowId: String,
    phaseId: String,
    reason: String,
  ): WorkflowUpdateResult = blockedPhaseRetry.retry(database, workflowId, phaseId, reason)

  fun repairFeatureTaskRuntimeIdentity(args: RepairFeatureTaskRuntimeIdentityArgs): WorkflowUpdateResult {
    val workflowId = args.workflowId
    val normalizedReason = args.reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Identity-repair reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    val normalizedIssueKey = requireNotNull(normalizeIssueKey(args.issueKey)).uppercase()
    return database.transaction { unitOfWork ->
      featureTaskIdentityRepair.repair(
        FeatureTaskIdentityRepairArgs(
          unitOfWork = unitOfWork,
          workflowId = workflowId,
          normalizedIssueKey = normalizedIssueKey,
          repositoryIdentity = args.repositoryIdentity,
          governedSpecPath = args.governedSpecPath,
          normalizedReason = normalizedReason,
        ),
      )
    }
  }

  fun get(
    kind: WorkflowFamilyKind,
    workflowId: String,
  ): WorkflowGetResult =
    database.read { unitOfWork ->
      val family = kind.workflowFamily()
      val record =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return@read WorkflowGetResult.Error(
            workflowId,
            "Unknown workflow_id '$workflowId'.",
            unitOfWork.dbPath.toString(),
          )
      workflowSnapshotValidator.validate(record, family.definition.workflowName)
      val snapshot = engine.snapshotView(family.definition, record)
      WorkflowGetResult.Ok(
        workflowId = record.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = snapshot,
        goalObservability = goalObservabilityOf(snapshot),
      )
    }

  fun list(
    kind: WorkflowFamilyKind,
    limit: Int = DEFAULT_LIST_LIMIT,
  ): WorkflowListResult =
    database.read { unitOfWork ->
      val family = kind.workflowFamily()
      val rows = unitOfWork.workflowStates.list(family, limit)
      WorkflowListResult(
        dbPath = unitOfWork.dbPath.toString(),
        workflowCount = rows.size,
        workflows =
          rows.map {
            workflowSnapshotValidator.validate(it, family.definition.workflowName)
            engine.summaryView(family.definition, it)
          },
      )
    }

  fun latest(kind: WorkflowFamilyKind): WorkflowLatestResult =
    database.read { unitOfWork ->
      val family = kind.workflowFamily()
      val record =
        unitOfWork.workflowStates.latest(family)
          ?: return@read WorkflowLatestResult.Error(
            dbPath = unitOfWork.dbPath.toString(),
            error = "No ${family.humanName} workflows found.",
          )
      workflowSnapshotValidator.validate(record, family.definition.workflowName)
      WorkflowLatestResult.Ok(
        dbPath = unitOfWork.dbPath.toString(),
        summary = engine.summaryView(family.definition, record),
      )
    }

  fun resume(
    kind: WorkflowFamilyKind,
    workflowId: String,
  ): WorkflowResumeResult =
    database.read { unitOfWork ->
      val family = kind.workflowFamily()
      val record =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return@read WorkflowResumeResult.Error(
            workflowId,
            "Unknown workflow_id '$workflowId'.",
            unitOfWork.dbPath.toString(),
          )
      workflowSnapshotValidator.validate(record, family.definition.workflowName)
      WorkflowResumeResult.Ok(
        workflowId = record.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        resume = engine.resumeView(family.definition, record),
      )
    }

  fun continueWorkflow(
    kind: WorkflowFamilyKind,
    workflowId: String,
    subtaskId: Int? = null,
  ): WorkflowContinueResult {
    var pendingProjection: PendingDecompositionProjection? = null
    val result =
      database.transaction { unitOfWork ->
        val family = kind.workflowFamily()
        var record = unitOfWork.workflowStates.get(family, workflowId)
        if (record == null && family == WorkflowFamily.TASK_RUNTIME) {
          val resolved =
            DecompositionWorkflowContinuation(
              engine,
              gitOperations,
              decompositionManifestValidator,
              decompositionManifestStore,
              repositoryRoot.path,
              decompositionManifestWriter,
              clock,
              workflowIdRandom,
            ).continueDecomposedParentByIssueKey(workflowId, unitOfWork, subtaskId)
          pendingProjection = mergePendingProjection(pendingProjection, resolved)
          return@transaction resolved.result
        }
        record ?: return@transaction WorkflowContinueResult.UnknownWorkflow(
          dbPath = unitOfWork.dbPath.toString(),
          workflowId = workflowId,
        )
        workflowSnapshotValidator.validate(record, family.definition.workflowName)
        val continuation =
          engine.continueExistingWorkflow(
            family,
            record,
            unitOfWork,
            ContinueExistingWorkflowArgs(
              validator = decompositionManifestValidator,
              fileStore = decompositionManifestStore,
              repoRoot = repositoryRoot.path,
              manifestWriter = decompositionManifestWriter,
              repositoryCheckpointIdentity = ::repositoryCheckpointIdentity,
            ),
          )
        pendingProjection = mergePendingProjection(pendingProjection, continuation)
          ?: currentParentProjectionForChild(record, unitOfWork)
        continuation.result
      }
    pendingProjection?.let { pending ->
      reconcileDecompositionManifestProjectionAfterCommit(pending)
    }
    return result
  }

  private fun mergePendingProjection(
    existing: PendingDecompositionProjection?,
    continuation: ContinuationStepResult,
  ): PendingDecompositionProjection? {
    val artifacts = continuation.projectionArtifacts ?: return existing
    val ownerWorkflowId =
      continuation.projectionOwnerWorkflowId?.takeIf(String::isNotBlank)
        ?: return existing
    return PendingDecompositionProjection(ownerWorkflowId, artifacts)
  }

  private fun currentParentProjectionForChild(
    childRecord: WorkflowStateSnapshot,
    unitOfWork: UnitOfWork,
  ): PendingDecompositionProjection? {
    if (!childRecord.isGoalContinuationChildWorkflow()) return null
    val ownerWorkflowId =
      resolveDecompositionProjectionOwner(
        childRecord,
        unitOfWork,
      )
    if (ownerWorkflowId == null) {
      runtimeDiagnostics.warning(
        "seam=decomposition_projection_settlement value_expected=projection_owner_workflow_id " +
          "value_used=absent workflow_id=${childRecord.workflowId}",
      )
      return null
    }
    val ownerRecord = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, ownerWorkflowId)
    if (ownerRecord == null) {
      runtimeDiagnostics.warning(
        "seam=decomposition_projection_settlement value_expected=workflow_row " +
          "value_used=absent owner_workflow_id=$ownerWorkflowId",
      )
      return null
    }
    return PendingDecompositionProjection(ownerWorkflowId, ownerRecord.artifacts)
  }

  private fun reconcileDecompositionManifestProjectionAfterCommit(pending: PendingDecompositionProjection) {
    val ownerWorkflowId = pending.ownerWorkflowId.trim()
    if (ownerWorkflowId.isEmpty()) {
      runtimeDiagnostics.warning(
        "seam=decomposition_projection_settlement value_expected=projection_owner_workflow_id value_used=blank",
      )
      return
    }
    when (
      val outcome =
        decompositionManifestWriter.writeProjectionFromWorkflowState(
          repositoryRoot.path,
          pending.artifacts,
          decompositionManifestValidator,
          decompositionManifestStore,
        )
    ) {
      is DecompositionManifestProjectionOutcome.Written ->
        database.transaction { unitOfWork ->
          when (clearDecompositionManifestProjectionFailure(engine, unitOfWork, ownerWorkflowId)) {
            DecompositionManifestProjectionFailurePersistence.PERSISTED -> Unit
            DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT ->
              runtimeDiagnostics.warning(
                "seam=decomposition_projection_settlement value_expected=workflow_row " +
                  "value_used=absent owner_workflow_id=$ownerWorkflowId",
              )
          }
        }
      is DecompositionManifestProjectionOutcome.Failed ->
        database.transaction { unitOfWork ->
          when (
            persistDecompositionManifestProjectionFailure(engine, unitOfWork, ownerWorkflowId, outcome)
          ) {
            DecompositionManifestProjectionFailurePersistence.PERSISTED -> Unit
            DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT ->
              runtimeDiagnostics.warning(
                "seam=decomposition_projection_settlement value_expected=workflow_row " +
                  "value_used=absent owner_workflow_id=$ownerWorkflowId",
              )
          }
        }
      DecompositionManifestProjectionOutcome.Absent -> Unit
    }
  }
}

private data class WorkflowUpdatePersistence(
  val result: WorkflowUpdateResult,
  val pendingProjection: PendingDecompositionProjection?,
)
