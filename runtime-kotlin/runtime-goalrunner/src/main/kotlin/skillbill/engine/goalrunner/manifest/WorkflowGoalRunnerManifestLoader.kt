package skillbill.engine.goalrunner.manifest

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.model.SpecBundleScope
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowIdForProjectSpec
import skillbill.application.workflow.decomposition.findDecomposedParentOrCorruptFallback
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.findOccupyingDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.importedPlanStepUpdates
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.application.workflow.decomposition.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.decomposition.withImportedPlan
import skillbill.application.workflow.persist.generateWorkflowId
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentAdoption
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.OccupyingGoalParent
import skillbill.engine.goalrunner.plan.GoalPlanningCheckoutOwnership
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.withImportedGoalQualityReadiness
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

@Inject
public class WorkflowGoalRunnerManifestLoader(
  private val database: DatabaseSessionFactory,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val clock: Clock,
  private val random: Random,
  private val projectStore: ProjectStorePort,
  private val parentProjection: GoalParentProjectionWriter,
  private val worktrees: GoalWorktreeResources,
  private val ownership: GoalPlanningCheckoutOwnership,
  private val workflowAgentAssignmentStore: WorkflowAgentAssignmentStore,
) {
  private val engine = WorkflowEngine()

  internal fun isIncompletePlan(workflowId: String): Boolean =
    database.read { unit ->
      val record = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
      record != null && record.workflowStatus != WorkflowStatus.COMPLETED
    }

  internal fun reconcileReservation(
    owner: GoalPlanningOwnership,
    sourceRoot: Path,
  ) {
    val failure = ownership.reconcileReservation(owner.normalizedIssueKey, sourceRoot, owner)
    if (failure != null) {
      throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, failure.reason)
    }
  }

  internal fun recoverPlanningCheckout(
    owner: GoalPlanningOwnership,
    sourceRoot: Path,
  ) {
    val result =
      worktrees.createOrAdopt(
        sourceRoot,
        owner.normalizedIssueKey,
        owner.ownedPath,
        owner.initialCommit,
        owner,
      )
    if (result is GoalWorktreeResult.Failed) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA,
        "Planning checkout ${owner.ownedPath} could not be recovered before adoption: ${result.reason}",
      )
    }
  }

  internal fun findProjectedManifest(
    repoRoot: Path,
    issueKey: String,
    recoverPending: Boolean = true,
  ) = resolveDecompositionManifest(
    scope = SpecBundleScope(repoRoot, projectStore.resolve(repoRoot)),
    issueKey = issueKey,
    fileStore = decompositionManifestStore,
    validator = decompositionManifestValidator,
    recoverPending = recoverPending,
  )

  internal fun loadOccupyingFromWorkflowStore(
    issueKey: String,
    currentProjectedManifest: DecompositionManifest? = null,
    repositoryIdentity: String? = null,
  ): OccupyingGoalParent? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.findOccupyingDecomposedParentWorkflow(
          issueKey,
          currentProjectedManifest,
          repositoryIdentity,
        ) ?: return@read null
      val state = manifestStateForParent(unitOfWork, record) ?: return@read null
      OccupyingGoalParent(state, record.workflowStatus.workflowStatus() ?: WorkflowStatus.PAUSED)
    }

  internal fun loadFromWorkflowStore(
    issueKey: String,
    currentProjectedManifest: DecompositionManifest? = null,
    repositoryIdentity: String? = null,
  ): GoalRunnerManifestState? =
    database.read { unitOfWork ->
      loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest, repositoryIdentity)
    }

  internal fun loadFromWorkflowStoreIfPresent(
    issueKey: String,
    currentProjectedManifest: DecompositionManifest? = null,
    repositoryIdentity: String? = null,
  ): GoalRunnerManifestState? =
    database.readIfPresent { unitOfWork ->
      loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest, repositoryIdentity)
    }

  internal fun loadFromWorkflowUnitOfWork(
    unitOfWork: UnitOfWork,
    issueKey: String,
    currentProjectedManifest: DecompositionManifest?,
    repositoryIdentity: String?,
  ): GoalRunnerManifestState? =
    manifestStateForParent(
      unitOfWork,
      unitOfWork.workflowStates.findDecomposedParentWorkflow(
        issueKey,
        currentProjectedManifest,
        repositoryIdentity,
      ),
    )

  private fun manifestStateForParent(
    unitOfWork: UnitOfWork,
    record: WorkflowStateRecord?,
  ): GoalRunnerManifestState? {
    record ?: return null
    val snapshot = record.toSnapshot()
    val manifest = snapshot.decompositionRuntime() ?: return null
    return GoalRunnerManifestState(
      parentWorkflowId = snapshot.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      manifest = manifest,
      controlState = unitOfWork.goalRunnerControls.controlState(snapshot.workflowId),
    )
  }

  internal fun importFromManifestProjection(
    manifest: DecompositionManifest,
    repositoryIdentities: List<String?> = listOf(null),
    independentParent: Boolean = false,
    planningOwner: GoalPlanningOwnership? = null,
  ): GoalRunnerManifestState? {
    var pendingAdoption: WorkflowAgentAssignmentAdoption? = null
    var committed = false
    return try {
      database
        .transaction { unitOfWork ->
          importInTransaction(
            unitOfWork,
            manifest,
            GoalParentImportSelection(repositoryIdentities, independentParent, planningOwner),
          ) { pendingAdoption = it }
        }.also { committed = true }
    } finally {
      pendingAdoption?.let { workflowAgentAssignmentStore.recordAdoptionOutcome(it, committed) }
    }
  }

  private fun importInTransaction(
    unitOfWork: UnitOfWork,
    manifest: DecompositionManifest,
    selection: GoalParentImportSelection,
    onRoutingAdoption: (WorkflowAgentAssignmentAdoption) -> Unit,
  ): GoalRunnerManifestState {
    val repositoryIdentities = selection.repositoryIdentities
    val independentParent = selection.independentParent
    val planningOwner = selection.planningOwner
    if (independentParent && planningOwner == null) {
      refuseConcurrentPlanningOwner(unitOfWork, repositoryIdentities, manifest.issueKey)
    }
    val selectedOwnedPlan = selectedOwnedPlan(unitOfWork, planningOwner, manifest.issueKey, repositoryIdentities)
    val existingRecord =
      if (independentParent) {
        null
      } else {
        repositoryIdentities.firstNotNullOfOrNull { identity ->
          unitOfWork.workflowStates.findDecomposedParentOrCorruptFallback(manifest.issueKey, manifest, identity)
        }
      }
    existingRecord?.requireRuntimeModeForEngineWrite()
    val existing = existingRecord?.toSnapshot()
    val attachCompletedPlan = existing == null && (!independentParent || selectedOwnedPlan != null)
    val base =
      existing ?: engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix, clock, random),
        WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix,
        "plan",
      )
    val (planWorkflowId, qualityPlanWorkflowId) =
      completedPlanIds(unitOfWork, manifest, repositoryIdentities, attachCompletedPlan, selectedOwnedPlan)
    val planningTime = unitOfWork.planWorkflowTiming(planWorkflowId)
    val imported =
      importedParent(
        base.copy(startedAt = planningTime.startedAt ?: base.startedAt),
        manifest,
        existing != null,
        planWorkflowId,
        qualityPlanWorkflowId,
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      imported.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
    adoptPlanningRouting(unitOfWork, planWorkflowId, imported.workflowId)?.let(onRoutingAdoption)
    if (existing == null) {
      val control = unitOfWork.goalRunnerControls.controlState(imported.workflowId)
      unitOfWork.goalRunnerControls.persistControlState(
        imported.workflowId,
        control.copy(activeDurationMs = planningTime.activeDurationMs),
      )
    }
    val saved = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, imported.workflowId) ?: imported
    return GoalRunnerManifestState(
      parentWorkflowId = saved.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      manifest = saved.decompositionRuntime() ?: manifest,
      controlState = unitOfWork.goalRunnerControls.controlState(saved.workflowId),
    )
  }

  private fun importedParent(
    base: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    existing: Boolean,
    planWorkflowId: String?,
    qualityPlanWorkflowId: String?,
  ): WorkflowStateSnapshot =
    engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      base,
      WorkflowUpdateInput(
        workflowStatus = WorkflowStatus.PAUSED,
        currentStepId = "plan",
        stepUpdates = if (existing) null else importedPlanStepUpdates(planWorkflowId),
        artifactsPatch =
          parentProjection.artifacts(manifest, base.artifacts).withImportedPlan(planWorkflowId).let { patch ->
            if (existing) patch else patch.withImportedGoalQualityReadiness(manifest, qualityPlanWorkflowId)
          },
        sessionId = base.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )

  private fun adoptPlanningRouting(
    unitOfWork: UnitOfWork,
    sourceWorkflowId: String?,
    targetWorkflowId: String,
  ): WorkflowAgentAssignmentAdoption? {
    if (sourceWorkflowId == null) return null
    return when (val routing = workflowAgentAssignmentStore.inspect(unitOfWork, sourceWorkflowId)) {
      WorkflowAgentAssignmentInspection.Legacy -> {
        null
      }

      is WorkflowAgentAssignmentInspection.Present -> {
        workflowAgentAssignmentStore.adopt(unitOfWork, sourceWorkflowId, targetWorkflowId)
      }

      is WorkflowAgentAssignmentInspection.Problem -> {
        throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
      }
    }
  }

  private fun completedPlanIds(
    unitOfWork: UnitOfWork,
    manifest: DecompositionManifest,
    repositoryIdentities: List<String?>,
    attachCompletedPlan: Boolean,
    selectedOwnedPlan: String?,
  ): Pair<String?, String?> {
    val planWorkflowId =
      if (attachCompletedPlan) {
        val activeOwner =
          repositoryIdentities.filterNotNull().any { identity ->
            unitOfWork.goalPlanningOwnership.findByIssue(identity, normalizeRequiredIssueKey(manifest.issueKey)) != null
          }
        selectedOwnedPlan ?: if (activeOwner) {
          null
        } else {
          repositoryIdentities.firstNotNullOfOrNull { identity ->
            unitOfWork.workflowStates.findCompletedPlanWorkflowId(manifest.issueKey, identity)
          }
        }
      } else {
        null
      }
    val qualityPlanWorkflowId =
      if (attachCompletedPlan) {
        planWorkflowId ?: unitOfWork.workflowStates.findCompletedPlanWorkflowIdForProjectSpec(manifest)
      } else {
        null
      }
    return planWorkflowId to qualityPlanWorkflowId
  }

  private fun refuseConcurrentPlanningOwner(
    unitOfWork: UnitOfWork,
    repositoryIdentities: List<String?>,
    issueKey: String,
  ) {
    val active =
      repositoryIdentities.filterNotNull().firstNotNullOfOrNull { identity ->
        unitOfWork.goalPlanningOwnership
          .findByIssue(identity, normalizeRequiredIssueKey(issueKey))
          ?.takeUnless {
            it.state == GoalPlanningOwnership.State.TRANSFERRED ||
              it.state == GoalPlanningOwnership.State.SUPERSEDED
          }
      }
    if (active != null) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA,
        "Planning checkout ${active.ownedPath} acquired an owner during intake. Preserve it and retry adoption.",
      )
    }
  }

  private fun selectedOwnedPlan(
    unitOfWork: UnitOfWork,
    planningOwner: GoalPlanningOwnership?,
    issueKey: String,
    repositoryIdentities: List<String?>,
  ): String? =
    planningOwner?.let { expected ->
      val current = unitOfWork.goalPlanningOwnership.findByWorkflow(expected.workflowId)
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, expected.workflowId)
      val executionIdentity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(expected.workflowId)
      val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
      val ownerMatches =
        current == expected && expected.state == GoalPlanningOwnership.State.INITIALIZED &&
          unitOfWork.goalPlanningOwnership.findByIssue(expected.repositoryIdentity, normalizedIssueKey) == expected &&
          Files.isDirectory(expected.ownedPath)
      val repositoryMatches =
        expected.normalizedIssueKey == normalizedIssueKey &&
          expected.repositoryIdentity in repositoryIdentities.filterNotNull() &&
          unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(expected.workflowId) == null
      val workflowMatches =
        record != null && record.toRecord().isPlanWorkflow() &&
          executionIdentity?.repositoryIdentity == expected.repositoryIdentity &&
          executionIdentity.normalizedIssueKey == normalizedIssueKey
      if (!ownerMatches || !repositoryMatches || !workflowMatches) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA,
          "Planning checkout ${expected.ownedPath} changed before adoption. Preserve it and reconcile " +
            "its ownership, workflow and live worker before retrying.",
        )
      }
      expected.workflowId
    }

  internal fun readProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? =
    when {
      shouldRefreshFromCompleteProjection(stored, projected) -> {
        requireNotNull(stored).copy(
          manifest = requireNotNull(projected),
          repoRoot = repoRoot,
        )
      }

      stored != null -> {
        stored.copy(repoRoot = repoRoot)
      }

      projected != null -> {
        GoalRunnerManifestState(
          parentWorkflowId = "",
          dbPath = "",
          manifest = projected,
          repoRoot = repoRoot,
        )
      }

      else -> {
        null
      }
    }

  internal fun shouldRefreshFromCompleteProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
  ): Boolean =
    stored != null &&
      projected != null &&
      projected.isCompleteGoalProjection() &&
      !stored.manifest.isCompleteGoalProjection()
}

private val SETTLED_OPERATOR_OR_CHILD_STATUSES: Set<DecompositionStatus> =
  setOf(DecompositionStatus.COMPLETE, DecompositionStatus.COMPLETED_NO_CHANGE)

internal fun mergeConcurrentGoalProgress(
  persisted: DecompositionManifest,
  incoming: DecompositionManifest,
): DecompositionManifest {
  val persistedById = persisted.subtasks.associateBy { it.id }
  val mergedSubtasks =
    incoming.subtasks.map { candidate ->
      val current = persistedById[candidate.id]
      if (
        current?.status.decompositionStatus() in SETTLED_OPERATOR_OR_CHILD_STATUSES &&
        candidate.status.decompositionStatus() !in SETTLED_OPERATOR_OR_CHILD_STATUSES
      ) {
        current ?: candidate
      } else {
        candidate
      }
    }
  val merged = incoming.copy(subtasks = mergedSubtasks)
  return if (
    persisted.currentSubtaskIntent.subtaskId > 0 &&
    merged.subtasks
      .firstOrNull { it.id == persisted.currentSubtaskIntent.subtaskId }
      ?.status
      .decompositionStatus() == DecompositionStatus.COMPLETE &&
    merged.currentSubtaskIntent.subtaskId == persisted.currentSubtaskIntent.subtaskId
  ) {
    merged.copy(currentSubtaskIntent = persisted.currentSubtaskIntent).withParentStatus()
  } else {
    merged.withParentStatus()
  }
}

internal fun DecompositionManifest.isCompleteGoalProjection(): Boolean =
  status.decompositionStatus() == DecompositionStatus.COMPLETE &&
    currentSubtaskIntent.action == "complete" &&
    subtasks.all { subtask ->
      when (subtask.status.decompositionStatus()) {
        DecompositionStatus.SKIPPED, DecompositionStatus.COMPLETED_NO_CHANGE -> true
        DecompositionStatus.COMPLETE -> !subtask.commitSha.isNullOrBlank()
        else -> false
      }
    }

internal fun projectionStillReusableParentHistory(
  projected: DecompositionManifest,
  storedParent: GoalRunnerManifestState,
): Boolean {
  val storedManifest = storedParent.manifest
  return projected.isCompleteGoalProjection() ||
    projected == storedManifest ||
    projected.subtasks.any { subtask ->
      val workflowId = subtask.workflowId?.takeIf(String::isNotBlank) ?: return@any false
      storedManifest.subtasks.any { it.workflowId == workflowId }
    }
}

private data class GoalParentImportSelection(
  val repositoryIdentities: List<String?>,
  val independentParent: Boolean,
  val planningOwner: GoalPlanningOwnership?,
)
