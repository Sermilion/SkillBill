package skillbill.engine.goalrunner.planning.source

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.config.model.CompactionSettings
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.plan.PlanSubtasksReplan
import skillbill.engine.goalrunner.plan.StandalonePlanResult
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import skillbill.engine.goalrunner.planning.context.goalPlanningImmutableDecompositionHash
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.canonicalRepository
import skillbill.engine.goalrunner.planning.outcome.resolvedSubSpecPath
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

fun interface GoalPlanningSource {
  fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome
}

@Inject
class PlanWorkflowGoalPlanningSource(
  private val planRun: StandalonePlanRun,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  planner: FeatureTaskRuntimeDecompositionPlanner,
  private val legacyPreparations: GoalPlanningPreparationCheckpoint,
  private val replannedChildren: GoalReplannedChildCleanup,
) : GoalPlanningSource {
  private val snapshots = PlanBundleSnapshots(planner)

  override fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome {
    val scope = scopeOf(state, request)
    return if (scope.planned.isEmpty()) {
      GoalPlanningSweepOutcome.PreparedAll()
    } else {
      fromPlanWorkflow(scope) ?: fromLegacyTables(scope) ?: runPlanSkeleton(scope)
    }
  }

  private fun scopeOf(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ) = PlanningScope(
    state = state,
    request = request,
    identity =
      GoalPlanningIdentity(
        state.parentWorkflowId,
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey),
        repositories.repositoryIdentity(request.repoRoot),
      ),
    canonicalRepository = canonicalRepository(request.repoRoot, repositories),
  )

  private fun fromPlanWorkflow(scope: PlanningScope): GoalPlanningSweepOutcome.PreparedAll? =
    linkedPlan(scope)?.let { plan -> preparedFromPlan(scope, plan) }

  private fun preparedFromPlan(
    scope: PlanningScope,
    plan: LinkedPlan,
  ): GoalPlanningSweepOutcome.PreparedAll? {
    if (scope.pending.any { !plan.unitComplete(scope, it) }) return null
    val descriptors = scope.descriptors(recordedHash = { null }) ?: return null
    val provenance =
      GoalPlanningContractProvenance(
        parentSpecHash = plan.bundle.parentSpecSha256,
        decompositionManifestHash = goalPlanningImmutableDecompositionHash(scope.state.manifest),
        planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )
    return GoalPlanningSweepOutcome.PreparedAll(scope.identity, provenance, descriptors, plan.workflowId)
  }

  private fun LinkedPlan.unitComplete(
    scope: PlanningScope,
    subtask: DecompositionSubtask,
  ): Boolean {
    val unit = bundle.subtask(subtask.id) ?: return false
    val unitRecord = records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtask.id)]
    return snapshots.unitComplete(scope.canonicalRepository, unit, unitRecord)
  }

  private fun fromLegacyTables(scope: PlanningScope): GoalPlanningSweepOutcome.PreparedAll? =
    try {
      legacyPrepared(scope)
    } catch (_: SkillBillRuntimeException) {
      null
    }

  private fun legacyPrepared(scope: PlanningScope): GoalPlanningSweepOutcome.PreparedAll? {
    val shared =
      (legacyPreparations.findSharedPreplanResult(scope.identity) as? SharedGoalPreplanLookupResult.Found)
        ?.checkpoint
        ?: return null
    val storedHashes = legacyStoredHashes(scope) ?: return null
    val descriptors = scope.descriptors(recordedHash = storedHashes::get) ?: return null
    return GoalPlanningSweepOutcome.PreparedAll(scope.identity, shared.provenance, descriptors, null)
  }

  private fun legacyStoredHashes(scope: PlanningScope): Map<Int, String>? {
    val storedHashes = mutableMapOf<Int, String>()
    scope.planned.forEach { subtask ->
      val governedPath = scope.governedPathOf(subtask) ?: return null
      val stored = legacyPreparations.findStoredSubtaskPlan(scope.identity, subtask.id, governedPath)
      if (stored != null) storedHashes[subtask.id] = stored.subSpecHash
      val matches = stored != null && stored.manifestOrder == scope.orderOf(subtask)
      if (subtask in scope.pending && !(matches && stored.subSpecHash == scope.diskHashOf(subtask))) return null
    }
    return storedHashes
  }

  private fun runPlanSkeleton(scope: PlanningScope): GoalPlanningSweepOutcome {
    val request = scope.request
    val issueKey = scope.state.manifest.issueKey
    val agentId = request.configuredAgentOverrideId ?: request.invokedAgentId
    val inputFor = { specPath: String -> planInput(request, agentId, specPath) }
    val linked = linkedPlan(scope)
    val replanned =
      if (linked == null) {
        legacyDriftedIds(scope)
      } else {
        scope.pending.filterNot { linked.unitComplete(scope, it) }.mapTo(mutableSetOf()) { it.id }
      }
    val result =
      if (linked == null) {
        planRun.run(issueKey, null, request.repoRoot, agentId, inputFor)
          .forceReplanOf(replanned, scope, agentId, inputFor)
      } else {
        planRun.replanSubtasks(
          PlanSubtasksReplan(
            issueKey = issueKey,
            planWorkflowId = linked.workflowId,
            repoRoot = request.repoRoot,
            settledSubtaskIds = scope.settledIds,
            agentId = agentId,
          ),
          inputFor,
        )
      }
    return when (result) {
      is StandalonePlanResult.Completed ->
        loadPlan(result.workflowId)?.let { plan -> preparedFromPlan(scope, plan) }
          ?.withClearedChildren(scope, replanned)
          ?: scope.stopped(
            "Plan workflow '${result.workflowId}' settled, but a pending subtask still has no Implementation " +
              "Details in its governed spec.",
          )
      is StandalonePlanResult.Blocked ->
        scope.stopped("Planning blocked in plan workflow '${result.workflowId}': ${result.reason}")
      is StandalonePlanResult.Refused -> scope.stopped(result.reason)
    }
  }

  private fun planInput(
    request: GoalRunnerRunRequest,
    agentId: String,
    specPath: String,
  ) = FeatureTaskRuntimeRunInput(
    issueKey = request.issueKey,
    specPath = specPath,
    repoRoot = request.repoRoot,
    explicitWorkflowId = null,
    invokedAgentId = agentId,
    agentAssignment = FeatureTaskRuntimeAgentAssignment(),
    modelAssignment = request.modelAssignment,
    compactionSettings = CompactionSettings.DEFAULT,
    environment = emptyMap(),
    timeout = request.planningBudget ?: request.timeout,
    requestedCodeReviewMode = null,
    goalContinuation = null,
    operatorDecision = null,
    agentAddonSelection = request.agentAddonSelection,
    eventSink = FeatureTaskRuntimeRunEventSink.NONE,
  )

  private fun StandalonePlanResult.forceReplanOf(
    forcedSubtaskIds: Set<Int>,
    scope: PlanningScope,
    agentId: String,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult =
    if (this is StandalonePlanResult.Completed && forcedSubtaskIds.isNotEmpty()) {
      planRun.replanSubtasks(
        PlanSubtasksReplan(
          issueKey = scope.state.manifest.issueKey,
          planWorkflowId = workflowId,
          repoRoot = scope.request.repoRoot,
          settledSubtaskIds = scope.settledIds,
          agentId = agentId,
          forcedSubtaskIds = forcedSubtaskIds,
        ),
        inputFor,
      )
    } else {
      this
    }

  private fun legacyDriftedIds(scope: PlanningScope): Set<Int> =
    try {
      legacyDrift(scope)
    } catch (_: SkillBillRuntimeException) {
      emptySet()
    }

  private fun legacyDrift(scope: PlanningScope): Set<Int> {
    val shared = legacyPreparations.findSharedPreplanResult(scope.identity) as? SharedGoalPreplanLookupResult.Found
    if (shared?.checkpoint == null) return emptySet()
    return scope.pending.mapNotNullTo(mutableSetOf()) { subtask ->
      val governedPath = scope.governedPathOf(subtask) ?: return@mapNotNullTo null
      val stored =
        legacyPreparations.findStoredSubtaskPlan(scope.identity, subtask.id, governedPath)
          ?: return@mapNotNullTo null
      val matches = stored.manifestOrder == scope.orderOf(subtask) && stored.subSpecHash == scope.diskHashOf(subtask)
      subtask.id.takeUnless { matches }
    }
  }

  private fun GoalPlanningSweepOutcome.PreparedAll.withClearedChildren(
    scope: PlanningScope,
    replannedSubtaskIds: Set<Int>,
  ): GoalPlanningSweepOutcome.PreparedAll =
    if (replannedSubtaskIds.isEmpty()) {
      this
    } else {
      copy(state = replannedChildren.clear(scope.state, replannedSubtaskIds))
    }

  private fun linkedPlan(scope: PlanningScope): LinkedPlan? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.findCompletedPlanWorkflowId(
        scope.state.manifest.issueKey,
        scope.identity.repositoryIdentity,
      )?.let { workflowId ->
        linkedPlanOf(workflowId, unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId))
      }
    }

  private fun loadPlan(workflowId: String): LinkedPlan? =
    database.read { unitOfWork ->
      linkedPlanOf(workflowId, unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId))
    }

  private fun linkedPlanOf(
    workflowId: String,
    snapshot: WorkflowStateSnapshot?,
  ): LinkedPlan? {
    if (snapshot == null || snapshot.steps.none { it.stepId == PLAN_SUBTASKS_STEP_ID }) return null
    val bundle =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(snapshot.artifacts)
        ?.let(::decodePlanBundleFromArtifact)
        ?: return null
    return LinkedPlan(workflowId, bundle, decodePhaseRecords(snapshot.artifacts))
  }

  private fun PlanningScope.descriptors(recordedHash: (Int) -> String?): List<GovernedGoalSubtaskDescriptor>? =
    planned.map { subtask ->
      val governedPath = governedPathOf(subtask) ?: return null
      val hash =
        recordedHash(subtask.id)?.takeIf { subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE }
          ?: diskHashOf(subtask)
          ?: return null
      GovernedGoalSubtaskDescriptor(subtask.id, orderOf(subtask), governedPath, hash)
    }

  private fun PlanningScope.governedPathOf(subtask: DecompositionSubtask): String? =
    resolvedSubSpecPath(canonicalRepository, subtask.specPath, repositories)
      ?.let { path -> canonicalRepository.relativize(path).joinToString("/") }

  private fun PlanningScope.diskHashOf(subtask: DecompositionSubtask): String? {
    val path: Path = resolvedSubSpecPath(canonicalRepository, subtask.specPath, repositories) ?: return null
    return if (fileStore.isRegularFile(path)) sha256HexUtf8(fileStore.readText(path)) else null
  }

  private fun PlanningScope.orderOf(subtask: DecompositionSubtask): Int = planned.indexOfFirst { it.id == subtask.id }

  private fun PlanningScope.stopped(reason: String): GoalPlanningSweepOutcome.Stopped =
    GoalPlanningSweepOutcome.Stopped(
      issueKey = request.issueKey,
      currentSubtaskId = (pending.firstOrNull() ?: planned.first()).id,
      reason = GoalRunnerStopReason.BLOCKED,
      blockedReason = reason,
      lastResumableStep = PLAN_STEP_ID,
    )

  private data class LinkedPlan(
    val workflowId: String,
    val bundle: FeatureTaskRuntimePlanBundle,
    val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  )

  private class PlanningScope(
    val state: GoalRunnerManifestState,
    val request: GoalRunnerRunRequest,
    val identity: GoalPlanningIdentity,
    val canonicalRepository: Path,
  ) {
    val planned: List<DecompositionSubtask> =
      state.manifest.subtasks.filter { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }
    val pending: List<DecompositionSubtask> =
      planned.filter { it.status.decompositionStatus() != DecompositionStatus.COMPLETE }
    val settledIds: Set<Int> =
      state.manifest.subtasks
        .filter { it.status.decompositionStatus() in SETTLED_STATUSES }
        .mapTo(mutableSetOf()) { it.id }
  }

  private companion object {
    const val PLAN_STEP_ID = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
    const val PLAN_SUBTASKS_STEP_ID = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
    val SETTLED_STATUSES = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
  }
}
