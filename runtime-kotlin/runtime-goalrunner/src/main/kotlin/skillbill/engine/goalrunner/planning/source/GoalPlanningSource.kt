package skillbill.engine.goalrunner.planning.source

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.planning.planBundleImmutableManifestHash
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.plan.PlanSubtasksReplan
import skillbill.engine.goalrunner.plan.StandalonePlanLaunch
import skillbill.engine.goalrunner.plan.StandalonePlanResult
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.canonicalStore
import skillbill.engine.goalrunner.planning.outcome.resolvedGovernedPath
import skillbill.engine.goalrunner.planning.outcome.resolvedSubSpecPath
import skillbill.engine.goalrunner.telemetry.GoalPlanningProgressRecorder
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException
import java.nio.file.Path

public fun interface GoalPlanningSource {
  public fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome
}

@Inject
public class PlanWorkflowGoalPlanningSource(
  private val planRun: StandalonePlanRun,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  private val planner: FeatureTaskRuntimeDecompositionPlanner,
  private val legacyPreparations: GoalPlanningPreparationCheckpoint,
  private val replannedChildren: GoalReplannedChildCleanup,
  private val progressRecorder: GoalPlanningProgressRecorder,
  private val projectStore: ProjectStorePort,
  private val feasibilityCodec: PlanBundleFeasibilityStateCodec,
  private val workflowAgentAssignmentStore: WorkflowAgentAssignmentStore,
) : GoalPlanningSource {
  private val snapshots = PlanBundleSnapshots(planner)

  override fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome {
    val routingInspection = workflowAgentAssignmentStore.inspect(state.parentWorkflowId)
    val admittedRouting =
      request.workflowAgentAssignments
        ?: when (val routing = routingInspection) {
          WorkflowAgentAssignmentInspection.Legacy -> {
            null
          }

          is WorkflowAgentAssignmentInspection.Present -> {
            routing.assignments
          }

          is WorkflowAgentAssignmentInspection.Problem -> {
            throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
          }
        }
    val admittedRequest =
      request.copy(
        workflowAgentAssignments = admittedRouting,
        workflowAgentAssignmentsFingerprint =
          request.workflowAgentAssignmentsFingerprint
            ?: (routingInspection as? WorkflowAgentAssignmentInspection.Present)?.fingerprint,
      )
    val scope = scopeOf(state, admittedRequest)
    return if (scope.planned.isEmpty()) {
      GoalPlanningSweepOutcome.PreparedAll()
    } else {
      selectedPlanProblem(scope)?.let { return scope.stopped(it) }
      selectedFeasibilityProblem(scope)?.let { return scope.stopped(it) }
      fromPlanWorkflow(scope) ?: runPlanSkeleton(scope)
    }
  }

  private fun scopeOf(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ) = PlanningScope(
    state = state,
    request = request,
    sourceRepository = request.repoRoot,
    identity =
      GoalPlanningIdentity(
        state.parentWorkflowId,
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey),
        state.controlState.repositoryIdentity ?: goalRepositoryIdentity(request.sourceRepoRoot, repositories),
      ),
    store = canonicalStore(projectStore.resolve(request.sourceRepoRoot), repositories),
    selectedPlanId =
      database.read { unit ->
        unit.workflowStates
          .get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
          ?.step(PLAN_STEP_ID)
          ?.planWorkflowId
      },
  )

  private fun fromPlanWorkflow(scope: PlanningScope): GoalPlanningSweepOutcome.PreparedAll? =
    linkedPlan(scope)?.let { plan -> preparedFromPlan(scope, plan) }

  private fun preparedFromPlan(
    scope: PlanningScope,
    plan: LinkedPlan,
  ): GoalPlanningSweepOutcome.PreparedAll? {
    val bundle = plan.bundle
    if (bundle == null || scope.pending.any { !plan.unitComplete(scope, it) } ||
      plan.feasibilityState == null
    ) {
      return null
    }
    val snapshot =
      PlanBundleFeasibility.capture(
        planner,
        scope.sourceRepository,
        scope.state.manifest.issueKey,
        { scope.checkoutPath(it) },
      )
    val feasibilityState = requireNotNull(plan.feasibilityState)
    if (!PlanBundleFeasibility.approved(
        feasibilityState,
        snapshot,
        plan.records[PLAN_SUBTASKS_STEP_ID],
        PLAN_SUBTASKS_STEP_ID,
      )
    ) {
      return null
    }
    val descriptors = scope.descriptors(recordedHash = { null }) ?: return null
    val provenance =
      GoalPlanningContractProvenance(
        parentSpecHash = bundle.parentSpecSha256,
        decompositionManifestHash = planBundleImmutableManifestHash(scope.state.manifest),
        planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )
    return GoalPlanningSweepOutcome.PreparedAll(scope.identity, provenance, descriptors, plan.workflowId)
  }

  private fun LinkedPlan.unitComplete(
    scope: PlanningScope,
    subtask: DecompositionSubtask,
  ): Boolean {
    val unit = bundle?.subtask(subtask.id) ?: return false
    val unitRecord = records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtask.id)]
    return snapshots.unitComplete(
      scope.sourceRepository,
      unit.copy(specPath = scope.checkoutPath(unit.specPath)),
      unitRecord,
    )
  }

  private fun runPlanSkeleton(scope: PlanningScope): GoalPlanningSweepOutcome {
    val request = scope.request
    val issueKey = scope.state.manifest.issueKey
    val agentId = request.configuredAgentOverrideId ?: request.invokedAgentId
    val progress = progressRecorder.sink(scope.state.parentWorkflowId, issueKey, agentId)
    val inputFor = { specPath: String ->
      planInput(request, specPath).copy(
        eventSink = progress,
        activityParentWorkflowId = scope.state.parentWorkflowId,
      )
    }
    val linked = linkedPlan(scope)
    val replanned =
      if (linked == null) {
        legacyDriftedIds(scope)
      } else {
        scope.pending.filterNot { linked.unitComplete(scope, it) }.mapTo(mutableSetOf()) { it.id }
      }
    val result =
      if (linked == null) {
        planRun
          .run(
            issueKey,
            null,
            request.sourceRepoRoot,
            StandalonePlanLaunch(agentId, IdeStatusLaunchScope.GOAL, executionRoot = request.repoRoot),
            inputFor,
          ).forceReplanOf(replanned, scope, agentId, inputFor)
      } else {
        planRun.replanSubtasks(
          PlanSubtasksReplan(
            issueKey = issueKey,
            planWorkflowId = linked.workflowId,
            repoRoot = request.repoRoot,
            sourceRoot = request.sourceRepoRoot,
            bundleRoot = request.repoRoot,
            settledSubtaskIds = scope.settledIds,
            agentId = agentId,
            parentWorkflowId = scope.state.parentWorkflowId,
            feasibilityAdoption =
              feasibilityNeedsReview(scope, linked) ||
                linked.feasibilityState?.mode == PlanBundleFeasibilityMode.APPROVED,
          ),
          inputFor,
        )
      }
    return when (result) {
      is StandalonePlanResult.Completed -> {
        loadPlan(result.workflowId)
          ?.let { plan -> preparedFromPlan(scope, plan) }
          ?.withClearedChildren(scope, replanned)
          ?: scope.stopped(
            "Plan workflow '${result.workflowId}' settled, but a pending subtask still has no Implementation " +
              "Details in its governed spec.",
          )
      }

      is StandalonePlanResult.Blocked -> {
        scope.stopped("Planning blocked in plan workflow '${result.workflowId}': ${result.reason}")
      }

      is StandalonePlanResult.Refused -> {
        scope.stopped(result.reason)
      }
    }
  }

  private fun feasibilityNeedsReview(
    scope: PlanningScope,
    linked: LinkedPlan,
  ): Boolean {
    return linked.let { plan ->
      val state = plan.feasibilityState ?: return@let true
      val snapshot =
        PlanBundleFeasibility.capture(
          planner,
          scope.sourceRepository,
          scope.state.manifest.issueKey,
          { scope.checkoutPath(it) },
        )
      !PlanBundleFeasibility.approved(
        state,
        snapshot,
        plan.records[PLAN_SUBTASKS_STEP_ID],
        PLAN_SUBTASKS_STEP_ID,
      )
    }
  }

  private fun planInput(
    request: GoalRunnerRunRequest,
    specPath: String,
  ) = FeatureTaskRuntimeRunInput(
    issueKey = request.issueKey,
    specPath = specPath,
    repoRoot = request.repoRoot,
    explicitWorkflowId = null,
    invokedAgentId = request.invokedAgentId,
    agentAssignment = request.agentAssignment,
    workflowAgentAssignments = request.workflowAgentAssignments,
    modelAssignment = request.modelAssignment,
    compactionSettings = request.compactionSettings,
    environment = emptyMap(),
    timeout = request.timeout,
    requestedCodeReviewMode = null,
    goalContinuation = null,
    operatorDecision = null,
    agentAddonSelection = request.agentAddonSelection,
    eventSink = FeatureTaskRuntimeRunEventSink.NONE,
    routingContextPrepared = request.routingContextPrepared,
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
          sourceRoot = scope.request.sourceRepoRoot,
          settledSubtaskIds = scope.settledIds,
          agentId = agentId,
          forcedSubtaskIds = forcedSubtaskIds,
          feasibilityAdoption = true,
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
      val states = unitOfWork.workflowStates
      scope.selectedPlanId?.let { workflowId ->
        linkedPlanOf(workflowId, states.get(WorkflowFamily.TASK_RUNTIME, workflowId))
          ?: throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA,
            "Selected plan '$workflowId' is unavailable at ${scope.sourceRepository}. Preserve it and retry recovery.",
          )
      }
    }

  private fun selectedPlanProblem(scope: PlanningScope): String? =
    database.read { unit ->
      val states = unit.workflowStates
      val selected = scope.selectedPlanId ?: return@read null
      val snapshot = states.get(WorkflowFamily.TASK_RUNTIME, selected)
      val identity = states.getFeatureTaskExecutionIdentity(selected)
      val plan = linkedPlanOf(selected, snapshot)
      val identityMatches =
        identity?.repositoryIdentity == scope.identity.repositoryIdentity &&
          identity.normalizedIssueKey == scope.identity.normalizedIssueKey &&
          scope.governsParentSpec(identity.governedSpecPath)
      val workflowMatches = snapshot != null && snapshot.toRecord().isPlanWorkflow()
      val unoccupied = states.getFeatureTaskRuntimeWorkerOwnership(selected) == null
      val selectionMatches = workflowMatches && identityMatches
      if (!selectionMatches || !unoccupied || plan == null) {
        "Selected plan '$selected' or its evidence at ${scope.sourceRepository} " +
          "is missing, mismatched or occupied. " +
          "Preserve the checkout and reconcile the plan identity, evidence and worker before retrying."
      } else if (scope.pending.any { subtask ->
          plan.records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtask.id)]?.status ==
            WorkflowStepStatus.COMPLETED && scope.diskHashOf(subtask) == null
        }
      ) {
        "Selected plan '$selected' has changed or missing subtask evidence at ${scope.sourceRepository}. " +
          "Preserve the checkout and restore its accepted evidence before retrying."
      } else {
        null
      }
    }

  private fun PlanningScope.governsParentSpec(governedSpecPath: String): Boolean =
    resolvedGovernedPath(sourceRepository, store, checkoutPath(governedSpecPath), repositories) ==
      resolvedGovernedPath(sourceRepository, store, checkoutPath(state.manifest.parentSpecPath), repositories)

  private fun selectedFeasibilityProblem(scope: PlanningScope): String? {
    val plan = scope.selectedPlanId?.let { linkedPlan(scope) } ?: return null
    val feasibility = plan.feasibilityState ?: return null
    if (feasibility.mode != PlanBundleFeasibilityMode.APPROVED ||
      scope.pending.any { scope.diskHashOf(it) != null && !plan.unitComplete(scope, it) }
    ) {
      return null
    }
    val approved =
      try {
        val snapshot =
          PlanBundleFeasibility.capture(
            planner,
            scope.sourceRepository,
            scope.state.manifest.issueKey,
            { scope.checkoutPath(it) },
          )
        PlanBundleFeasibility.approved(
          feasibility,
          snapshot,
          plan.records[PLAN_SUBTASKS_STEP_ID],
          PLAN_SUBTASKS_STEP_ID,
        )
      } catch (_: IOException) {
        false
      } catch (_: SkillBillRuntimeException) {
        false
      }
    return if (approved) {
      null
    } else {
      "Selected plan '${plan.workflowId}' has missing or changed feasibility evidence " +
        "at ${scope.sourceRepository}. " +
        "Preserve the checkout and restore the accepted bundle before retrying."
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
    if (snapshot == null) return null
    val feasibilityFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
    val feasibilityState =
      if (feasibilityFamily.contains(snapshot.artifacts)) {
        feasibilityCodec.decode(feasibilityFamily.value(snapshot.artifacts), "$workflowId/${feasibilityFamily.label()}")
      } else {
        null
      }
    if (snapshot.steps.none { it.stepId == PLAN_SUBTASKS_STEP_ID }) return null
    val bundle =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE
        .value(snapshot.artifacts)
        ?.let(::decodePlanBundleFromArtifact)
    return LinkedPlan(workflowId, bundle, decodePhaseRecords(snapshot.artifacts), feasibilityState)
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
    subtask.specPath.takeIf(String::isNotBlank)?.let { specPath ->
      val governed =
        resolveFeatureTaskGovernedSpecPath(
          repositories,
          projectStore,
          sourceRepository,
          Path.of(checkoutPath(specPath)),
        )
      (governed as? FeatureTaskGovernedSpecPathResult.Ok)?.relativePath
    }

  private fun PlanningScope.diskHashOf(subtask: DecompositionSubtask): String? {
    val path: Path =
      resolvedSubSpecPath(sourceRepository, store, checkoutPath(subtask.specPath), repositories) ?: return null
    return if (fileStore.isRegularFile(path)) sha256HexUtf8(fileStore.readText(path)) else null
  }

  private fun PlanningScope.orderOf(subtask: DecompositionSubtask): Int = planned.indexOfFirst { it.id == subtask.id }

  private fun PlanningScope.checkoutPath(value: String): String {
    val path = Path.of(value).normalize()
    val originalRoot = request.sourceRepoRoot.toAbsolutePath().normalize()
    return if (path.isAbsolute && path.startsWith(originalRoot) && !path.startsWith(store.specsRoot)) {
      sourceRepository.resolve(originalRoot.relativize(path)).toString()
    } else {
      value
    }
  }

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
    val bundle: FeatureTaskRuntimePlanBundle?,
    val records: Map<String, FeatureTaskRuntimePhaseRecord>,
    val feasibilityState: FeatureTaskRuntimePlanBundleFeasibilityState?,
  )

  private class PlanningScope(
    val state: GoalRunnerManifestState,
    val request: GoalRunnerRunRequest,
    val identity: GoalPlanningIdentity,
    val sourceRepository: Path,
    val store: ProjectStoreLocation,
    val selectedPlanId: String?,
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
