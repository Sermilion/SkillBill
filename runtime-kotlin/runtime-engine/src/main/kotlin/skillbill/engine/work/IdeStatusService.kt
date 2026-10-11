package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardResult
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailPhase
import skillbill.ports.idestatus.model.WorkDetailProblem
import skillbill.ports.idestatus.model.WorkDetailProblemCode
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailResult
import skillbill.ports.idestatus.model.WorkDetailRuntimeStatus
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@Inject
class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val manifestStore: GoalRunnerManifestStore,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val goalRunnerStatusService: GoalRunnerStatusService,
) {
  fun status(request: IdeStatusRequest): IdeStatusResult {
    val observedAt = request.observedAt ?: clock.instant()
    val identityResult = resolveRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort)
    if (identityResult is IdeStatusRepositoryResolution.Invalid) {
      return emit(IdeStatusProblemSnapshots.invalidRepositoryInput(observedAt, identityResult.message))
    }
    if (identityResult is IdeStatusRepositoryResolution.Missing) {
      return emit(IdeStatusProblemSnapshots.missingRepositoryIdentity(observedAt, identityResult.message))
    }
    val repositoryIdentity = (identityResult as IdeStatusRepositoryResolution.Ok).identity
    val repoRoot = identityResult.repoRoot

    if (!database.databaseExists()) {
      return emit(IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt))
    }

    val currentBranch = branchSource.checkedOutBranch(repoRoot)
    return try {
      database.read { unitOfWork ->
        val candidates =
          scopeToBranch(
            collectCandidates(unitOfWork, repositoryIdentity, currentBranch, observedAt, repoRoot),
            currentBranch,
            repoRoot,
          )
        val context =
          IdeStatusProjectionContext(
            unitOfWork = unitOfWork,
            repositoryIdentity = repositoryIdentity,
            branchCorrelation = currentBranch,
            observedAt = observedAt,
            repoRoot = repoRoot,
          )
        val selectable = candidates.filter { projector.readableForSelection(it, context) }
        val selected =
          IdeStatusSelectionPolicy.select(selectable, observedAt)
            ?: IdeStatusSelectionPolicy.select(candidates, observedAt)
            ?: return@read emit(
              IdeStatusProblemSnapshots.noMatchingWork(repositoryIdentity, observedAt, currentBranch),
            )
        emit(projector.project(candidate = selected, context = context))
      }
    } catch (error: SkillBillRuntimeException) {
      when {
        error.code == WorkflowFailureCode.INVALID_WORK_LIST_ROW ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible work-list record.",
            ),
          )
        error.isInvalidWorkflowStateFailure() ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible workflow record.",
            ),
          )
        else -> throw error
      }
    }
  }

  fun board(request: WorkBoardRequest): WorkBoardResult {
    val observedAt = request.observedAt ?: clock.instant()
    if (!database.databaseExists()) {
      return WorkBoardResult(observedAt, request.finishedWithin, emptyList(), absentDatabase = true)
    }
    return database.read { unitOfWork ->
      val work = resolvedWorkItems(canonicalWorkItems(unitOfWork.workList.list(limit = null)), unitOfWork)
      val finishedFrom = observedAt.minus(request.finishedWithin.duration)
      val standalone = unitOfWork.standalonePhaseStatuses.readBoardCandidates(finishedFrom, observedAt)
      val diagnostics = repositoryDiagnostics(work, standalone).toMutableList()
      val identities = (work.mapNotNull { it.repositoryIdentity } + standalone.map { it.repositoryIdentity }).distinct()
      val items = identities.flatMap { identity ->
        val root = decodeRepositoryRoot(identity) ?: return@flatMap emptyList()
        val resolved = ResolvedWorkCandidates(
          work.filter { it.repositoryIdentity == identity },
          standalone.filter { it.repositoryIdentity == identity },
        )
        val context = IdeStatusProjectionContext(unitOfWork, identity, null, observedAt, root)
        collectCandidates(unitOfWork, identity, null, observedAt, root, resolved).mapNotNull { candidate ->
          val item = projectBoardItem(candidate, context, work)
          val finishedAt = item.finishedAt
          when {
            !isFinished(item.snapshot) -> item
            finishedAt == null -> {
              diagnostics += WorkBoardDiagnostic(
                WorkBoardDiagnosticReason.MISSING_COMPLETION_TIME,
                identity,
                workflowId = candidate.workflowId.takeIf { candidate.standaloneStatus == null },
                invocationId = candidate.standaloneStatus?.invocationId,
              )
              null
            }
            finishedAt >= finishedFrom && finishedAt <= observedAt -> item
            else -> null
          }
        }
      }
      WorkBoardResult(observedAt, request.finishedWithin, items, diagnostics)
    }
  }

  private fun resolvedWorkItems(items: List<WorkItem>, unitOfWork: UnitOfWork): List<ResolvedWorkItem> =
    items.map { item ->
      val execution = unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
      ResolvedWorkItem(item, repositoryIdentityFor(item, unitOfWork, execution), execution)
    }

  private fun repositoryDiagnostics(
    work: List<ResolvedWorkItem>,
    standalone: List<StandalonePhaseStatusRecord>,
  ): List<WorkBoardDiagnostic> = buildList {
    work.filter { it.repositoryIdentity?.let(::decodeRepositoryRoot) == null }.forEach {
      add(repositoryIdentityDiagnostic(it.repositoryIdentity, workflowId = it.item.workflowId))
    }
    standalone.filter { decodeRepositoryRoot(it.repositoryIdentity) == null }.forEach {
      add(repositoryIdentityDiagnostic(it.repositoryIdentity, invocationId = it.invocationId))
    }
  }

  private fun projectBoardItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    work: List<ResolvedWorkItem>,
  ): WorkBoardItem {
    val snapshot = projector.project(candidate, context.copy(branchCorrelation = candidate.branchCorrelation))
    ideStatusValidator.validate(snapshot, "work-board:${candidate.workflowId}")
    val completion = if (isFinished(snapshot)) {
      completionEvidence(
        candidate.standaloneStatus,
        work.firstOrNull { it.item.workflowId == candidate.workflowId }?.item,
        context.unitOfWork,
      )
    } else {
      null to null
    }
    return WorkBoardItem(snapshot, context.repoRoot.toString(), completion.first, completion.second)
  }

  fun detail(request: WorkDetailRequest): WorkDetailResult {
    val observedAt = request.observedAt ?: clock.instant()
    if (!database.databaseExists()) {
      return detailProblem(observedAt, WorkDetailProblemCode.ABSENT_DATABASE, "Skill Bill database is not present.")
    }
    return database.read { unitOfWork ->
      val match = detailMatch(request.id, unitOfWork)
      if (match is WorkDetailMatch.Problem) {
        return@read WorkDetailResult(observedAt, problem = match.problem)
      }
      val found = match as WorkDetailMatch.Found
      val resolved = found.work
      val standalone = found.standalone
      val identity = standalone?.repositoryIdentity ?: resolved?.repositoryIdentity
      val root = identity?.let(::decodeRepositoryRoot)
      if (identity == null || root == null) {
        return@read detailProblem(
          observedAt,
          WorkDetailProblemCode.INVALID_REPOSITORY_IDENTITY,
          "Work item '${request.id}' has no usable repository identity.",
        )
      }
      val candidates = collectCandidates(
        unitOfWork,
        identity,
        null,
        observedAt,
        root,
        ResolvedWorkCandidates(listOfNotNull(resolved), listOfNotNull(standalone)),
      )
      val candidate = candidates.singleOrNull()
        ?: return@read detailProblem(
          observedAt,
          WorkDetailProblemCode.UNKNOWN_WORK_ITEM,
          "No projectable work item has id '${request.id}'.",
        )
      val context = IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
      WorkDetailResult(observedAt, item = projectDetailItem(candidate, context, resolved?.item))
    }
  }

  private fun detailMatch(id: String, unitOfWork: UnitOfWork): WorkDetailMatch {
    val exactStandalone = unitOfWork.standalonePhaseStatuses.readByInvocation(id)
    val matches = canonicalDetailMatches(unitOfWork.workList.list(limit = null).filter { it.workflowId == id })
    if (exactStandalone == null && matches.isEmpty()) {
      return WorkDetailMatch.Problem(
        WorkDetailProblem(WorkDetailProblemCode.UNKNOWN_WORK_ITEM, "No work item has id '$id'."),
      )
    }
    val resolved = resolvedWorkItems(matches, unitOfWork).singleOrNull()
    val merged = exactStandalone != null && resolved != null &&
      exactStandalone.workflowId == resolved.item.workflowId &&
      exactStandalone.repositoryIdentity == resolved.repositoryIdentity
    val matchCount = matches.size + if (exactStandalone != null && !merged) 1 else 0
    if (matchCount > 1) {
      return WorkDetailMatch.Problem(
        WorkDetailProblem(WorkDetailProblemCode.AMBIGUOUS_WORK_ITEM, "More than one work item has id '$id'."),
      )
    }
    val associated = resolved?.let { work ->
      unitOfWork.standalonePhaseStatuses.latestStandaloneExecution(work.item.workflowId)
        ?.takeIf { it.repositoryIdentity == work.repositoryIdentity }
    }
    return WorkDetailMatch.Found(resolved, exactStandalone ?: associated)
  }

  private fun detailProblem(observedAt: Instant, code: WorkDetailProblemCode, message: String): WorkDetailResult =
    WorkDetailResult(observedAt, problem = WorkDetailProblem(code, message))

  private fun projectDetailItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    item: WorkItem?,
  ): WorkDetailItem {
    val snapshot = projector.project(candidate, context)
    ideStatusValidator.validate(snapshot, "work-detail:${candidate.workflowId}")
    val standalone = candidate.standaloneStatus
    val completion =
      if (isFinished(snapshot)) completionEvidence(standalone, item, context.unitOfWork) else null to null
    val goalManifest = item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_GOAL }
      ?.issueKey?.let { manifestStore.loadDurableByIssueKey(it, context.repoRoot) }
      ?.takeIf { it.parentWorkflowId == candidate.workflowId }
    val runtimeWorkflowId = goalManifest?.let { state ->
      state.manifest.subtasks.firstOrNull { it.id == state.controlState.currentSubtaskId }?.workflowId
    } ?: item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME }?.workflowId
    val runtimeStatus = runtimeWorkflowId?.takeIf { standalone == null }?.let(::detailRuntimeStatus)
    val goalStatus = goalManifest?.let { manifest ->
      goalRunnerStatusService.status(
        GoalRunnerStatusRequest(issueKey = manifest.manifest.issueKey, repoRoot = context.repoRoot),
      )
        ?.takeIf { it.issueKey == manifest.manifest.issueKey }
    }
    return WorkDetailItem(
      snapshot = snapshot,
      repositoryRoot = context.repoRoot.toString(),
      stepPipeline = standalone?.let { standaloneStepPipeline(it.phaseId) }
        ?: runtimeStatus?.phases?.map { it.phaseId }.orEmpty(),
      finishedAt = completion.first,
      finishedAtSource = completion.second,
      goalManifest = goalManifest?.manifest,
      goalFinishPipeline = if (goalManifest == null) emptyList() else listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
      ),
      runtimeStatus = runtimeStatus,
      goalStatus = goalStatus,
    )
  }

  private fun detailRuntimeStatus(workflowId: String): WorkDetailRuntimeStatus? =
    featureTaskRuntimeStatusService.status(FeatureTaskRuntimeStatusRequest(workflowId))?.let { status ->
      WorkDetailRuntimeStatus(
        phases = status.phases.map { phase ->
          WorkDetailPhase(
            phase.phaseId, phase.status, phase.attemptCount, phase.resolvedAgentId, phase.finished,
            phase.executionOrigin, phase.continuationKind, phase.launchedModel, phase.launchedEffort,
          )
        },
        currentPhase = status.currentPhaseId,
        completeCount = status.completeCount,
        pendingCount = status.pendingCount,
        blockedCount = status.blockedCount,
      )
    }

  private fun isFinished(snapshot: IdeStatusSnapshot): Boolean =
    snapshot.lifecycleState == IdeStatusLifecycleState.TERMINAL ||
      snapshot.lifecycleState == IdeStatusLifecycleState.FAILED

  private fun repositoryIdentityFor(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    registration: IdeStatusWorkflowExecution? =
      unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId),
  ): String? {
    return when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.repositoryIdentity
          ?: registration?.repositoryIdentity
          ?: unitOfWork.standalonePhaseStatuses.latestStandaloneExecution(item.workflowId)?.repositoryIdentity
      WorkItemKind.FEATURE_GOAL -> {
        val controlIdentity = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
        controlIdentity ?: registration?.repositoryIdentity ?: goalChildRepositoryIdentity(item.workflowId, unitOfWork)
      }
      WorkItemKind.FEATURE_VERIFY ->
        registration?.repositoryIdentity ?: verifyIssueRepositoryIdentity(item, unitOfWork)
    }
  }

  private fun goalChildRepositoryIdentity(workflowId: String, unitOfWork: UnitOfWork): String? =
    unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(workflowId)
      .mapNotNull { unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(it)?.repositoryIdentity }
      .distinct()
      .singleOrNull()

  private fun verifyIssueRepositoryIdentity(item: WorkItem, unitOfWork: UnitOfWork): String? {
    val issueKey = item.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) ?: return null
    return unitOfWork.workList.list(limit = null)
      .asSequence()
      .filter { it.workflowId != item.workflowId && it.workflowKind != WorkItemKind.FEATURE_VERIFY }
      .filter { it.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) == issueKey }
      .mapNotNull { durableFamilyRepositoryIdentity(it, unitOfWork) }
      .distinct()
      .singleOrNull()
  }

  private fun durableFamilyRepositoryIdentity(item: WorkItem, unitOfWork: UnitOfWork): String? =
    when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.repositoryIdentity
          ?: unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)?.repositoryIdentity
      WorkItemKind.FEATURE_GOAL ->
        unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
          ?: unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)?.repositoryIdentity
          ?: goalChildRepositoryIdentity(item.workflowId, unitOfWork)
      WorkItemKind.FEATURE_VERIFY -> null
    }

  private fun canonicalDetailMatches(matches: List<WorkItem>): List<WorkItem> {
    if (matches.size != 2) return matches
    val goal = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_GOAL } ?: return matches
    val runtime = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME } ?: return matches
    val goalIssue = goal.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    val runtimeIssue = runtime.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    return if (goalIssue != null && goalIssue == runtimeIssue) listOf(goal) else matches
  }

  private fun canonicalWorkItems(workItems: List<WorkItem>): List<WorkItem> =
    workItems.groupBy { it.workflowId }.values.flatMap(::canonicalDetailMatches)

  private fun repositoryIdentityDiagnostic(
    identity: String?,
    workflowId: String? = null,
    invocationId: String? = null,
  ): WorkBoardDiagnostic = WorkBoardDiagnostic(
    reason = if (identity.isNullOrBlank()) WorkBoardDiagnosticReason.MISSING_REPOSITORY_IDENTITY
      else WorkBoardDiagnosticReason.INVALID_REPOSITORY_IDENTITY,
    repositoryIdentity = identity,
    workflowId = workflowId,
    invocationId = invocationId,
  )

  private fun completionEvidence(
    standalone: StandalonePhaseStatusRecord?,
    item: WorkItem?,
    unitOfWork: UnitOfWork,
  ): Pair<Instant?, WorkBoardCompletionSource?> {
    standalone?.finishedAt?.let { return it to WorkBoardCompletionSource.STANDALONE_FINISHED_AT }
    item ?: return null to null
    if (item.workflowKind == WorkItemKind.FEATURE_GOAL) {
      val completedAt = unitOfWork.goalRunnerControls.controlState(item.workflowId).goalCompletedAt
      if (completedAt != null) {
        val instant =
          parsePersistedInstantOrNull(completedAt)
            ?: throw invalidWorkflowStateSchemaError(
              "Goal runner control state has an invalid goal_completed_at timestamp.",
            )
        return instant to WorkBoardCompletionSource.GOAL_COMPLETION
      }
      return item.stateEnteredAt to
        if (item.stateEnteredAtEstimated) {
          WorkBoardCompletionSource.ESTIMATED_GOAL_STATE_ENTRY
        } else {
          WorkBoardCompletionSource.GOAL_STATE_ENTRY
        }
    }
    val family =
      if (item.workflowKind == WorkItemKind.FEATURE_VERIFY) WorkflowFamily.VERIFY else WorkflowFamily.TASK_RUNTIME
    return unitOfWork.workflowStates.get(family, item.workflowId)?.finishedAt
      ?.let { it to WorkBoardCompletionSource.WORKFLOW_COMPLETION } ?: (null to null)
  }

  private fun standaloneStepPipeline(step: String): List<String> =
    when (step) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR ->
        listOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR ->
        listOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN ->
        listOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        )
      else -> listOf(step)
    }

  fun toWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> = ideStatusValidator.toWirePayload(snapshot).toPayload()

  private fun scopeToBranch(
    candidates: List<IdeStatusCandidate>,
    branch: String?,
    repoRoot: Path,
  ): List<IdeStatusCandidate> {
    if (branch == null) return candidates
    if (protectedBranchName(branch) != null) return candidates
    return candidates.filter { candidate -> matchesBranch(candidate, branch, repoRoot) }
  }

  private fun matchesBranch(
    candidate: IdeStatusCandidate,
    branch: String,
    repoRoot: Path,
  ): Boolean =
    (
      candidate.branchCorrelation == null ||
        candidate.branchCorrelation == "HEAD" ||
        candidate.branchCorrelation == branch
    ) &&
      (
        candidate.standaloneStatus != null ||
          candidate.issueKey?.let { IdeStatusBranchScope.branchReferencesIssueKey(branch, it) } == true ||
          isPlanningOnBaseBranch(candidate, branch, repoRoot)
      )

  private fun isPlanningOnBaseBranch(
    candidate: IdeStatusCandidate,
    branch: String,
    repoRoot: Path,
  ): Boolean {
    val issueKey = candidate.issueKey
    if (issueKey == null ||
      candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_GOAL ||
      candidate.lifecycleState == IdeStatusLifecycleState.TERMINAL
    ) {
      return false
    }
    val state = manifestStore.loadDurableByIssueKey(issueKey, repoRoot)
    if (state == null || state.parentWorkflowId != candidate.workflowId || state.manifest.baseBranch != branch) {
      return false
    }
    val planning =
      manifestStore.planningStatus(
        state.parentWorkflowId,
        state.manifest.subtasks.filter { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }.map { it.id },
      ) ?: return false
    return planning.state != GoalPlanningStatusState.PREPARED
  }

  private fun collectCandidates(
    unitOfWork: UnitOfWork,
    repositoryIdentity: String,
    branch: String?,
    observedAt: Instant,
    repoRoot: Path,
    resolved: ResolvedWorkCandidates? = null,
  ): List<IdeStatusCandidate> {
    val work = resolved?.work?.map { it.item } ?: unitOfWork.workList.list(limit = null)
    val issueKeysWithGoals =
      work
        .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        .mapNotNull { it.issueKey?.uppercase() }
        .toSet()
    val workflowCandidates =
      work.mapNotNull { item ->
        if (isExcludedGoalChild(routeScopeFor(item, unitOfWork), item.issueKey, issueKeysWithGoals)) {
          null
        } else {
          toCandidate(
            item,
            unitOfWork,
            repositoryIdentity,
            branch,
            repoRoot,
            resolved?.work?.firstOrNull { it.item.workflowId == item.workflowId },
          )
        }
      }
    val standaloneCandidates =
      (
        resolved?.standalone ?: branch?.let { branchName ->
          unitOfWork.standalonePhaseStatuses.readEligible(repositoryIdentity, branchName, observedAt)
        }
      )?.map { stored ->
        val record = projectStandaloneLease(stored, observedAt)
        val lifecycle = standaloneLifecycle(record.lifecycleState)
        IdeStatusCandidate(
          workflowId = record.workflowId ?: "standalone:${record.executionId}",
          workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
          issueKey = record.issueKey,
          currentState = record.lifecycleState,
          lifecycleState = lifecycle,
          selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
          updatedAt = record.updatedAt,
          startedAt = record.startedAt,
          isGoalAuthoritative = false,
          execution =
            IdeStatusExecutionIdentity(
              scope = IdeStatusExecutionScope.STANDALONE_PHASE,
              executionId = record.executionId,
              statusStoreId = record.statusStoreId,
              runSequence = record.runSequence,
              statusRevision = record.statusRevision,
              invocationId = record.invocationId,
              phaseId = record.phaseId,
            ),
          branchCorrelation = record.branchCorrelation,
          standaloneStatus = record,
        )
      }.orEmpty()
    val standaloneWorkflowIds = standaloneCandidates.mapNotNull { it.standaloneStatus?.workflowId }.toSet()
    return workflowCandidates.filterNot { it.workflowId in standaloneWorkflowIds } + standaloneCandidates
  }

  private fun projectStandaloneLease(
    record: StandalonePhaseStatusRecord,
    observedAt: Instant,
  ): StandalonePhaseStatusRecord {
    if (record.phaseId == "monitor" ||
      standaloneLifecycle(record.lifecycleState) != IdeStatusLifecycleState.ACTIVE ||
      record.leaseExpiresAt.isAfter(observedAt)
    ) {
      return record
    }
    return record.copy(
      lifecycleState = "paused",
      currentStep = "runner_interrupted",
      currentActivity = null,
      activeDurationAsOf = null,
    )
  }

  private fun standaloneLifecycle(state: String): IdeStatusLifecycleState =
    when (state) {
      "active", "running" -> IdeStatusLifecycleState.ACTIVE
      "paused", "runner_interrupted" -> IdeStatusLifecycleState.PAUSED
      "blocked" -> IdeStatusLifecycleState.BLOCKED
      "failed" -> IdeStatusLifecycleState.FAILED
      "terminal", "completed", "success" -> IdeStatusLifecycleState.TERMINAL
      else -> IdeStatusLifecycleState.FAILED
    }

  private fun toCandidate(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    repositoryIdentity: String,
    branch: String?,
    repoRoot: Path,
    resolved: ResolvedWorkItem? = null,
  ): IdeStatusCandidate? {
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork, repositoryIdentity)
    val family = item.workflowKind.toIdeFamily()
    val lifecycle =
      family?.let { candidateFamily ->
        if (
          if (resolved != null) resolved.repositoryIdentity != repositoryIdentity
          else repositoryCorrelation.matches(item, candidateFamily) != true
        ) {
          null
        } else {
          IdeStatusSelectionPolicy.lifecycleFromDurableStateWire(item.currentState)
        }
      }
    if (family == null || lifecycle == null) return null
    val workflowExecution =
      if (resolved != null) resolved.workflowExecution
      else unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
    val branchCorrelation =
      goalBranchCorrelation(item, family, workflowExecution?.branchCorrelation, branch, repoRoot)
    val execution =
      workflowExecution
        ?.takeIf { branch == null || branchCorrelation == branch || branchCorrelation == "HEAD" }
        ?.let { record ->
          IdeStatusExecutionIdentity(
            scope = IdeStatusExecutionScope.WORKFLOW,
            executionId = record.executionId,
            statusStoreId = record.statusStoreId,
            runSequence = record.runSequence,
            statusRevision = record.statusRevision,
          )
        }
    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = livenessAnchors.authoritativeUpdatedAt(item, family) ?: item.stateEnteredAt,
      startedAt = item.startedAt,
      isGoalAuthoritative = family == IdeStatusWorkflowFamily.FEATURE_GOAL,
      execution = execution,
      branchCorrelation = branchCorrelation,
    )
  }

  private fun goalBranchCorrelation(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    registeredBranch: String?,
    currentBranch: String?,
    repoRoot: Path,
  ): String? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL || registeredBranch == null || currentBranch == null) {
      return registeredBranch
    }
    if (registeredBranch == currentBranch || registeredBranch == "HEAD") return registeredBranch
    val state = item.issueKey?.let { manifestStore.loadDurableByIssueKey(it, repoRoot) }
    val manifest =
      state?.manifest?.takeIf {
        state.parentWorkflowId == item.workflowId
      }
    val ownsBranch =
      manifest != null &&
        (manifest.featureBranch == currentBranch || manifest.stackBranches.any { it.branch == currentBranch })
    return if (ownsBranch) currentBranch else registeredBranch
  }

  private fun routeScopeFor(
    item: WorkItem,
    unitOfWork: UnitOfWork,
  ): FeatureTaskRouteScope? =
    when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.routeScope
      WorkItemKind.FEATURE_VERIFY,
      WorkItemKind.FEATURE_GOAL,
      -> null
    }

  private fun isExcludedGoalChild(
    routeScope: FeatureTaskRouteScope?,
    issueKey: String?,
    issueKeysWithGoals: Set<String>,
  ): Boolean = routeScope == FeatureTaskRouteScope.GOAL_CHILD && issueKey?.uppercase() in issueKeysWithGoals

  private fun emit(snapshot: IdeStatusSnapshot): IdeStatusResult {
    ideStatusValidator.validate(snapshot, sourceLabel = "ide-status")
    return IdeStatusResult(snapshot = snapshot, exitCode = snapshot.exitCode())
  }
}

internal fun resolveRepositoryIdentity(
  repoRootArg: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): IdeStatusRepositoryResolution {
  val resolvedStart =
    runCatching {
      repositoryEnclosingRootPort.canonicalPath(Path.of(repoRootArg))
    }.getOrNull()
      ?: return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
  val gitRoot =
    findGitRoot(resolvedStart, repositoryEnclosingRootPort)
      ?: return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  val canonicalGitRoot = repositoryEnclosingRootPort.canonicalPath(gitRoot)
  val identity = goalRepositoryIdentity(canonicalGitRoot, repositoryEnclosingRootPort)
  return if (
    identity.isBlank() ||
    !identity.startsWith(FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
  ) {
    IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  } else {
    IdeStatusRepositoryResolution.Ok(identity = identity, repoRoot = canonicalGitRoot)
  }
}

private fun findGitRoot(
  start: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  var candidate: Path? = start
  while (candidate != null) {
    if (repositoryEnclosingRootPort.optionalRealPath(candidate.resolve(".git")) != null) return candidate
    candidate = candidate.parent
  }
  return null
}

private fun decodeRepositoryRoot(identity: String): Path? =
  try {
    val prefix = FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX
    if (!identity.startsWith(prefix)) return null
    Path.of(identity.removePrefix(prefix)).takeIf { it.isAbsolute }
  } catch (_: InvalidPathException) {
    null
  }

private data class ResolvedWorkItem(
  val item: WorkItem,
  val repositoryIdentity: String?,
  val workflowExecution: IdeStatusWorkflowExecution?,
)

private data class ResolvedWorkCandidates(
  val work: List<ResolvedWorkItem>,
  val standalone: List<StandalonePhaseStatusRecord>,
)

private sealed interface WorkDetailMatch {
  data class Found(val work: ResolvedWorkItem?, val standalone: StandalonePhaseStatusRecord?) : WorkDetailMatch

  data class Problem(val problem: WorkDetailProblem) : WorkDetailMatch
}
