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
import skillbill.engine.work.control.workControlEligible
import skillbill.engine.work.control.workDisplayGroup
import skillbill.engine.work.control.workDisplayPriority
import skillbill.engine.work.control.workLiveSection
import skillbill.engine.work.control.workRequiresAttention
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.engine.work.observation.workerOwnership
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.engine.work.pagination.includesDetailList
import skillbill.engine.work.pagination.invalidWorkPaginationRequest
import skillbill.engine.work.pagination.workBoardNextCursor
import skillbill.engine.work.pagination.workBoardPagePosition
import skillbill.engine.work.pagination.workDetailPagePosition
import skillbill.engine.work.pagination.workDetailPagedResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentityFamily
import skillbill.idestatus.model.IdeStatusRepositoryIdentityPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentitySource
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardResult
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailPhase
import skillbill.ports.idestatus.model.WorkDetailProblem
import skillbill.ports.idestatus.model.WorkDetailProblemCode
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailResult
import skillbill.ports.idestatus.model.WorkDetailRuntimeStatus
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkGoalSubtaskPage
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
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
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
            collectCandidates(unitOfWork, identityResult, currentBranch, observedAt),
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
        emit(projector.project(candidate = selected, context = context).snapshot)
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
    val position = workBoardPagePosition(request, clock.instant())
    val observedAt = position.observedAt
    if (!database.databaseExists()) {
      return WorkBoardResult(
        observedAt,
        request.finishedWithin,
        emptyList(),
        absentDatabase = true,
        pageSize = request.pageSize,
      )
    }
    return database.read { unitOfWork ->
      val observation = unitOfWork.workList.observation()
      if (request.cursor != null && position.observation != observation) {
        invalidWorkPaginationRequest("Work changed. Refresh the board.")
      }
      val finishedFrom = observedAt.minus(request.finishedWithin.duration)
      val diagnostics = mutableListOf<WorkBoardDiagnostic>()
      val items = mutableListOf<WorkBoardItem>()
      var afterKey = position.afterKey
      var exhausted = false
      var examined = 0
      val budget = request.pageSize * BOARD_CANDIDATE_BATCH_BUDGET
      while (items.size < request.pageSize && examined < budget && !exhausted) {
        val batchSize = minOf(request.pageSize, budget - examined)
        val rows =
          unitOfWork.workList.boardCandidates(
            WorkBoardCandidateQuery(finishedFrom, observedAt, afterKey, batchSize + 1),
          )
        var consumed = 0
        for (row in rows.take(batchSize)) {
          consumed++
          examined++
          afterKey = row.key
          projectBoardCandidate(row.id, unitOfWork, observedAt, finishedFrom, diagnostics)?.let(items::add)
          if (items.size == request.pageSize) break
        }
        exhausted = consumed == rows.size
      }
      val nextCursor =
        afterKey?.takeIf { !exhausted }?.let {
          workBoardNextCursor(request, observedAt, it, observation)
        }
      WorkBoardResult(
        observedAt,
        request.finishedWithin,
        items,
        diagnostics,
        pageSize = request.pageSize,
        nextCursor = nextCursor,
        observation = observation,
        candidatesExamined = examined,
      )
    }
  }

  private fun projectBoardCandidate(
    id: String,
    unit: UnitOfWork,
    observedAt: Instant,
    finishedFrom: Instant,
    diagnostics: MutableList<WorkBoardDiagnostic>,
  ): WorkBoardItem? {
    val found = detailMatch(id, unit) as? WorkDetailMatch.Found ?: return null
    val identity = found.standalone?.repositoryIdentity ?: found.work?.repositoryIdentity
    val root = identity?.let(::decodeRepositoryRoot)
    if (identity == null || root == null) {
      diagnostics += repositoryIdentityDiagnostic(identity, workflowId = id)
      return null
    }
    val work = listOfNotNull(found.work)
    val candidates =
      collectCandidates(
        unit,
        IdeStatusRepositoryResolution.Ok(identity, root),
        null,
        observedAt,
        ResolvedWorkCandidates(work, listOfNotNull(found.standalone)),
      )
    return candidates.singleOrNull()?.let { candidate ->
      projectRetainedBoardItem(
        candidate,
        IdeStatusProjectionContext(unit, identity, null, observedAt, root),
        work,
        finishedFrom,
        diagnostics,
      )
    }
  }

  fun projectBoardSelection(
    ids: List<String>,
    request: WorkBoardRequest,
  ): WorkBoardResult {
    require(ids.size <= request.pageSize)
    val observedAt = request.observedAt ?: clock.instant()
    return database.read { unitOfWork ->
      val diagnostics = mutableListOf<WorkBoardDiagnostic>()
      val items =
        ids.distinct().mapNotNull { id ->
          val found = selectedMatch(id, unitOfWork)
          val work = found.work
          val standalone = found.standalone
          val identity = standalone?.repositoryIdentity ?: work?.repositoryIdentity
          val root = identity?.let(::decodeRepositoryRoot)
          if (identity == null || root == null) {
            diagnostics += repositoryIdentityDiagnostic(identity, workflowId = id)
            return@mapNotNull null
          }
          val candidates =
            collectCandidates(
              unitOfWork,
              IdeStatusRepositoryResolution.Ok(identity, root),
              null,
              observedAt,
              ResolvedWorkCandidates(listOfNotNull(work), listOfNotNull(standalone)),
            )
          val candidate =
            candidates.singleOrNull()
              ?: throw invalidWorkListRowError("Selected work candidate cannot be projected.")
          val context = IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
          projectBoardItem(candidate, context, listOfNotNull(work))
        }
      WorkBoardResult(
        observedAt,
        request.finishedWithin,
        items,
        diagnostics,
        pageSize = request.pageSize,
        observation = unitOfWork.workList.observation(),
      )
    }
  }

  private fun selectedMatch(
    id: String,
    unitOfWork: UnitOfWork,
  ): WorkDetailMatch.Found =
    detailMatch(id, unitOfWork) as? WorkDetailMatch.Found
      ?: throw invalidWorkListRowError("Selected work candidate is unavailable or ambiguous.")

  private fun projectRetainedBoardItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    work: List<ResolvedWorkItem>,
    finishedFrom: Instant,
    diagnostics: MutableList<WorkBoardDiagnostic>,
  ): WorkBoardItem? {
    val item = projectBoardItem(candidate, context, work)
    if (!isFinished(item.snapshot)) return item
    val finishedAt = item.finishedAt
    if (finishedAt == null) {
      diagnostics +=
        WorkBoardDiagnostic(
          WorkBoardDiagnosticReason.MISSING_COMPLETION_TIME,
          context.repositoryIdentity,
          workflowId = candidate.workflowId.takeIf { candidate.standaloneStatus == null },
          invocationId = candidate.standaloneStatus?.invocationId,
        )
      return null
    }
    return item.takeIf { finishedAt >= finishedFrom && finishedAt <= context.observedAt }
  }

  private fun resolvedWorkItems(
    items: List<WorkItem>,
    unitOfWork: UnitOfWork,
  ): List<ResolvedWorkItem> =
    items.map { item ->
      val execution = unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
      ResolvedWorkItem(item, repositoryIdentityFor(item, unitOfWork, execution), execution)
    }

  private fun projectBoardItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    work: List<ResolvedWorkItem>,
  ): WorkBoardItem {
    val projection = projector.project(candidate, context.copy(branchCorrelation = candidate.branchCorrelation))
    val snapshot = projection.snapshot
    ideStatusValidator.validate(snapshot, "work-board:${candidate.workflowId}")
    val completion =
      if (isFinished(snapshot)) {
        completionEvidence(
          candidate.standaloneStatus,
          work.firstOrNull { it.item.workflowId == candidate.workflowId }?.item,
          context.unitOfWork,
        )
      } else {
        null to null
      }
    return WorkBoardItem(
      snapshot,
      context.repoRoot.toString(),
      completion.first,
      completion.second,
      context.unitOfWork.goalRunnerControls.controlState(candidate.workflowId).executionLease,
      context.unitOfWork.workList.observation(),
      snapshot.updatedAt.plus(IdeStatusFreshnessClassifier.FRESH_WINDOW).plusNanos(1),
      workLiveSection(snapshot, completion.first),
      workControlEligible(snapshot),
      workRequiresAttention(snapshot),
      workDisplayGroup(snapshot, completion.first),
      workDisplayPriority(snapshot, completion.first),
      projection.exactPauseReason,
      context.unitOfWork.databaseFileObservation,
    )
  }

  fun detail(request: WorkDetailRequest): WorkDetailResult {
    val position = workDetailPagePosition(request, clock.instant())
    val observedAt = position.observedAt
    if (!database.databaseExists()) {
      return detailProblem(observedAt, WorkDetailProblemCode.ABSENT_DATABASE, "Skill Bill database is not present.")
        .copy(pageSize = request.pageSize)
    }
    val result =
      database.read { unitOfWork ->
        if (request.cursor != null && position.observation != unitOfWork.workList.observation()) {
          invalidWorkPaginationRequest("Work changed. Refresh the detail.")
        }
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
        val candidates =
          collectCandidates(
            unitOfWork,
            IdeStatusRepositoryResolution.Ok(identity, root),
            null,
            observedAt,
            ResolvedWorkCandidates(listOfNotNull(resolved), listOfNotNull(standalone)),
          )
        val candidate =
          candidates.singleOrNull()
            ?: return@read detailProblem(
              observedAt,
              WorkDetailProblemCode.UNKNOWN_WORK_ITEM,
              "No projectable work item has id '${request.id}'.",
            )
        val context = IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
        WorkDetailResult(
          observedAt,
          item = projectDetailItem(candidate, context, resolved?.item, request, position),
          observation = unitOfWork.workList.observation(),
        )
      }
    return workDetailPagedResult(result, request, position)
  }

  private fun detailMatch(
    id: String,
    unitOfWork: UnitOfWork,
  ): WorkDetailMatch {
    val exactStandalone = unitOfWork.standalonePhaseStatuses.readByInvocation(id)
    val matches = canonicalDetailMatches(unitOfWork.workList.findByWorkflowId(id))
    if (exactStandalone == null && matches.isEmpty()) {
      return WorkDetailMatch.Problem(
        WorkDetailProblem(WorkDetailProblemCode.UNKNOWN_WORK_ITEM, "No work item has id '$id'."),
      )
    }
    val resolved = resolvedWorkItems(matches, unitOfWork).singleOrNull()
    val merged =
      exactStandalone != null && resolved != null &&
        exactStandalone.workflowId == resolved.item.workflowId &&
        exactStandalone.repositoryIdentity == resolved.repositoryIdentity
    val matchCount = matches.size + if (exactStandalone != null && !merged) 1 else 0
    if (matchCount > 1) {
      return WorkDetailMatch.Problem(
        WorkDetailProblem(WorkDetailProblemCode.AMBIGUOUS_WORK_ITEM, "More than one work item has id '$id'."),
      )
    }
    val associated =
      resolved?.let { work ->
        unitOfWork.standalonePhaseStatuses.latestStandaloneExecution(work.item.workflowId)
          ?.takeIf { it.repositoryIdentity == work.repositoryIdentity }
      }
    return WorkDetailMatch.Found(resolved, exactStandalone ?: associated)
  }

  private fun detailProblem(
    observedAt: Instant,
    code: WorkDetailProblemCode,
    message: String,
  ): WorkDetailResult = WorkDetailResult(observedAt, problem = WorkDetailProblem(code, message))

  private fun projectDetailItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    item: WorkItem?,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): WorkDetailItem {
    val offset = position.offset
    val pageSize = request.pageSize
    val projection = projector.project(candidate, context)
    val snapshot = projection.snapshot
    ideStatusValidator.validate(snapshot, "work-detail:${candidate.workflowId}")
    val standalone = candidate.standaloneStatus
    val completion =
      if (isFinished(snapshot)) completionEvidence(standalone, item, context.unitOfWork) else null to null
    val goal = item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_GOAL }
    val goalPage = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = false)
    val validationPage = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = true)
    val goalStatus = goal?.let { work -> detailGoalStatus(work, validationPage, context) }
    val dependencies = detailDependencies(goal, context.unitOfWork.workList, request, offset)
    val runtimeWorkflowId =
      goalStatus?.currentChildWorkflowId
        ?: item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME }?.workflowId
    val runtimeStatus = runtimeWorkflowId?.takeIf { standalone == null }?.let(::detailRuntimeStatus)
    val history =
      if (request.includesDetailList(WorkDetailList.EXECUTIONS)) {
        workExecutionHistory(
          context.unitOfWork,
          standalone,
          candidate.workflowId,
          WorkDetailPagePosition(context.observedAt, offset),
          pageSize + 1,
        )
      } else {
        emptyList()
      }
    val moreLists =
      detailContinuationLists(
        history.size > pageSize,
        (goalPage?.totalCount ?: 0) > offset + pageSize,
        (validationPage?.totalCount ?: 0) > offset + pageSize,
        (runtimeStatus?.phases?.size ?: 0) > offset + pageSize && (request.includesDetailList(WorkDetailList.PHASES)),
      )
    val allMoreLists = if (dependencies?.nextOffset == null) moreLists else moreLists + WorkDetailList.DEPENDENCIES
    return WorkDetailItem(
      snapshot = snapshot,
      executionHistory = history.take(pageSize),
      repositoryRoot = context.repoRoot.toString(),
      stepPipeline =
        standalone?.let { standaloneStepPipeline(it.phaseId) }
          ?: runtimeStatus?.phases?.map { it.phaseId }.orEmpty(),
      finishedAt = completion.first,
      finishedAtSource = completion.second,
      goalManifest = goalPage?.manifest,
      goalFinishPipeline = if (goalPage == null) emptyList() else GOAL_FINISH_PIPELINE,
      runtimeStatus = detailRuntimePage(runtimeStatus, request, offset),
      goalStatus = goalStatus,
      pageOffset = offset,
      pageSize = pageSize,
      hasMore = allMoreLists.isNotEmpty(),
      moreLists = allMoreLists,
      dependencies = dependencies?.entries.orEmpty(),
      freshnessDeadline = snapshot.updatedAt.plus(IdeStatusFreshnessClassifier.FRESH_WINDOW).plusNanos(1),
      observation = context.unitOfWork.workList.observation(),
      liveSection = workLiveSection(snapshot, completion.first),
      controlEligible = workControlEligible(snapshot),
      requiresAttention = workRequiresAttention(snapshot),
      displayGroup = workDisplayGroup(snapshot, completion.first),
      displayPriority = workDisplayPriority(snapshot, completion.first),
      exactPauseReason = projection.exactPauseReason,
      databaseFileObservation = context.unitOfWork.databaseFileObservation,
    )
  }

  private fun detailRuntimePage(
    status: WorkDetailRuntimeStatus?,
    request: WorkDetailRequest,
    offset: Int,
  ) = status?.copy(
    phases =
      if (request.includesDetailList(WorkDetailList.PHASES)) {
        status.phases.drop(offset).take(request.pageSize)
      } else {
        emptyList()
      },
  )

  private fun detailDependencies(
    goal: WorkItem?,
    workList: WorkListRepository,
    request: WorkDetailRequest,
    offset: Int,
  ) = goal?.takeIf { request.includesDetailList(WorkDetailList.DEPENDENCIES) }
    ?.let { workList.goalDependencyPage(it.workflowId, offset, request.pageSize) }

  private fun detailGoalPage(
    goal: WorkItem?,
    workList: WorkListRepository,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
    completedOnly: Boolean,
  ): WorkGoalSubtaskPage? {
    val list = if (completedOnly) WorkDetailList.VALIDATION else WorkDetailList.SUBTASKS
    return goal?.takeIf { request.includesDetailList(list) }
      ?.let { work ->
        workList.goalSubtaskPage(work.workflowId, position.offset, request.pageSize, completedOnly)?.let { page ->
          val projected =
            goalRunnerStatusService.reconcileSubtaskPage(
              work.workflowId,
              GoalRunnerStatusRequest(requireNotNull(work.issueKey)),
              page.manifest.subtasks,
            )
          val visible =
            if (completedOnly) {
              projected.filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETE }
            } else {
              projected
            }
          page.copy(manifest = page.manifest.copy(subtasks = visible))
        }
      }
  }

  private fun detailGoalStatus(
    work: WorkItem,
    validationPage: WorkGoalSubtaskPage?,
    context: IdeStatusProjectionContext,
  ) = goalRunnerStatusService.statusForWorkflow(
    work.workflowId,
    GoalRunnerStatusRequest(
      issueKey = requireNotNull(work.issueKey),
      repoRoot = context.repoRoot,
      pruneCheckpointRefs = false,
      completedSubtaskValidationIds =
        validationPage?.manifest?.subtasks.orEmpty().mapTo(mutableSetOf()) { it.id },
    ),
  )
    ?.takeIf { it.issueKey == work.issueKey }

  private fun detailContinuationLists(
    moreExecutions: Boolean,
    moreSubtasks: Boolean,
    moreValidation: Boolean,
    morePhases: Boolean,
  ): Set<WorkDetailList> =
    buildSet {
      if (moreExecutions) add(WorkDetailList.EXECUTIONS)
      if (moreSubtasks) add(WorkDetailList.SUBTASKS)
      if (moreValidation) add(WorkDetailList.VALIDATION)
      if (morePhases) add(WorkDetailList.PHASES)
    }

  private fun detailRuntimeStatus(workflowId: String): WorkDetailRuntimeStatus? =
    featureTaskRuntimeStatusService.status(FeatureTaskRuntimeStatusRequest(workflowId))?.let { status ->
      WorkDetailRuntimeStatus(
        phases =
          status.phases.map { phase ->
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
    snapshot.lifecycleState in setOf(IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.CANCELED) ||
      snapshot.lifecycleState == IdeStatusLifecycleState.FAILED

  private fun repositoryIdentityFor(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    registration: IdeStatusWorkflowExecution? =
      unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId),
  ): String? {
    val family =
      when (item.workflowKind) {
        WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusRepositoryIdentityFamily.TASK
        WorkItemKind.FEATURE_GOAL -> IdeStatusRepositoryIdentityFamily.GOAL
        WorkItemKind.FEATURE_VERIFY -> IdeStatusRepositoryIdentityFamily.VERIFY
      }
    val evidence =
      buildMap {
        put(IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION, registration?.repositoryIdentity)
        when (family) {
          IdeStatusRepositoryIdentityFamily.TASK -> {
            put(
              IdeStatusRepositoryIdentitySource.EXECUTION_IDENTITY,
              unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.repositoryIdentity,
            )
            put(
              IdeStatusRepositoryIdentitySource.STANDALONE_REGISTRATION,
              unitOfWork.standalonePhaseStatuses.latestStandaloneExecution(item.workflowId)?.repositoryIdentity,
            )
          }
          IdeStatusRepositoryIdentityFamily.GOAL -> {
            put(
              IdeStatusRepositoryIdentitySource.CONTROL,
              unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity,
            )
            put(
              IdeStatusRepositoryIdentitySource.GOAL_CHILDREN,
              goalChildRepositoryIdentity(item.workflowId, unitOfWork),
            )
          }
          IdeStatusRepositoryIdentityFamily.VERIFY ->
            put(IdeStatusRepositoryIdentitySource.ISSUE_UNIQUE, verifyIssueRepositoryIdentity(item, unitOfWork))
        }
      }
    return IdeStatusRepositoryIdentityPolicy.resolve(family, evidence)
  }

  private fun goalChildRepositoryIdentity(
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): String? =
    when (val lookup = unitOfWork.workList.repositoryIdentityForGoalChildren(workflowId)) {
      is IssueRepositoryIdentityLookup.Unique -> lookup.repositoryIdentity
      else -> null
    }

  private fun verifyIssueRepositoryIdentity(
    item: WorkItem,
    unitOfWork: UnitOfWork,
  ): String? {
    val issueKey = item.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) ?: return null
    return when (val lookup = unitOfWork.workList.repositoryIdentityForIssue(issueKey, item.workflowId)) {
      IssueRepositoryIdentityLookup.Missing -> null
      is IssueRepositoryIdentityLookup.Unique -> lookup.repositoryIdentity
      is IssueRepositoryIdentityLookup.Ambiguous -> null
    }
  }

  private fun canonicalDetailMatches(matches: List<WorkItem>): List<WorkItem> {
    if (matches.size != 2) return matches
    val goal = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_GOAL } ?: return matches
    val runtime = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME } ?: return matches
    val goalIssue = goal.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    val runtimeIssue = runtime.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    return if (goalIssue != null && goalIssue == runtimeIssue) listOf(goal) else matches
  }

  private fun repositoryIdentityDiagnostic(
    identity: String?,
    workflowId: String? = null,
    invocationId: String? = null,
  ): WorkBoardDiagnostic =
    WorkBoardDiagnostic(
      reason =
        if (identity.isNullOrBlank()) {
          WorkBoardDiagnosticReason.MISSING_REPOSITORY_IDENTITY
        } else {
          WorkBoardDiagnosticReason.INVALID_REPOSITORY_IDENTITY
        },
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
    return if (item.workflowKind == WorkItemKind.FEATURE_GOAL) {
      val completedAt = unitOfWork.goalRunnerControls.controlState(item.workflowId).goalCompletedAt
      if (completedAt != null) {
        val instant =
          parsePersistedInstantOrNull(completedAt)
            ?: throw invalidWorkflowStateSchemaError(
              "Goal runner control state has an invalid goal_completed_at timestamp.",
            )
        instant to WorkBoardCompletionSource.GOAL_COMPLETION
      } else {
        item.stateEnteredAt to
          if (item.stateEnteredAtEstimated) {
            WorkBoardCompletionSource.ESTIMATED_GOAL_STATE_ENTRY
          } else {
            WorkBoardCompletionSource.GOAL_STATE_ENTRY
          }
      }
    } else {
      val family =
        if (item.workflowKind == WorkItemKind.FEATURE_VERIFY) WorkflowFamily.VERIFY else WorkflowFamily.TASK_RUNTIME
      unitOfWork.workflowStates.get(family, item.workflowId)?.finishedAt
        ?.let { it to WorkBoardCompletionSource.WORKFLOW_COMPLETION } ?: (null to null)
    }
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
          isVerifyRegisteredOnBranch(candidate, branch) ||
          candidate.issueKey?.let { IdeStatusBranchScope.branchReferencesIssueKey(branch, it) } == true ||
          isPlanningOnBaseBranch(candidate, branch, repoRoot)
      )

  private fun isVerifyRegisteredOnBranch(
    candidate: IdeStatusCandidate,
    branch: String,
  ): Boolean =
    candidate.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY && candidate.branchCorrelation == branch

  private fun isPlanningOnBaseBranch(
    candidate: IdeStatusCandidate,
    branch: String,
    repoRoot: Path,
  ): Boolean {
    val issueKey = candidate.issueKey
    if (issueKey == null ||
      candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_GOAL ||
      candidate.lifecycleState in setOf(IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.CANCELED)
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
    repository: IdeStatusRepositoryResolution.Ok,
    branch: String?,
    observedAt: Instant,
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
            repository,
            branch,
            resolved?.work?.firstOrNull { it.item.workflowId == item.workflowId },
          )
        }
      }
    val standaloneRecords =
      resolved?.standalone ?: branch?.let { branchName ->
        unitOfWork.standalonePhaseStatuses.readEligible(repository.identity, branchName, observedAt)
      }.orEmpty()
    val standaloneCandidates =
      latestPhaseExecutions(standaloneRecords).map { stored ->
        val record = projectStandaloneLease(stored, observedAt)
        val lifecycle = IdeStatusPolicy.standaloneLifecycle(record.lifecycleState)
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
    val standaloneWorkflowIds = standaloneRecords.mapNotNull { it.workflowId }.toSet()
    return workflowCandidates.filterNot { it.workflowId in standaloneWorkflowIds } + standaloneCandidates
  }

  private fun projectStandaloneLease(
    record: StandalonePhaseStatusRecord,
    observedAt: Instant,
  ): StandalonePhaseStatusRecord {
    if (IdeStatusPolicy.standaloneLifecycle(record.lifecycleState) != IdeStatusLifecycleState.ACTIVE) {
      return record
    }
    val ownerGone =
      record.workerOwnership()?.let { owner ->
        when (workerSupervisor.inspect(owner)) {
          FeatureTaskRuntimeProcessInspection.NotRunning,
          is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
          -> true
          FeatureTaskRuntimeProcessInspection.ExactLive,
          is FeatureTaskRuntimeProcessInspection.Unsupported,
          -> false
        }
      } == true
    if (record.leaseExpiresAt.isAfter(observedAt) && !ownerGone) return record
    return record.copy(
      lifecycleState = "paused",
      currentStep = "runner_interrupted",
      currentActivity = null,
      activeDurationAsOf = null,
    )
  }

  private fun toCandidate(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    repository: IdeStatusRepositoryResolution.Ok,
    branch: String?,
    resolved: ResolvedWorkItem? = null,
  ): IdeStatusCandidate? {
    val repositoryIdentity = repository.identity
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork, repositoryIdentity)
    val family = item.workflowKind.toIdeFamily()
    val lifecycle =
      family?.let { candidateFamily ->
        if (
          if (resolved != null) {
            resolved.repositoryIdentity != repositoryIdentity
          } else {
            repositoryCorrelation.matches(item, candidateFamily) != true
          }
        ) {
          null
        } else {
          IdeStatusSelectionPolicy.lifecycleFromDurableStateWire(candidateFamily, item.currentState)
        }
      }
    if (family == null || lifecycle == null) return null
    val workflowExecution =
      if (resolved != null) {
        resolved.workflowExecution
      } else {
        unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
      }
    val branchCorrelation =
      goalBranchCorrelation(item, family, workflowExecution?.branchCorrelation, branch, repository.repoRoot)
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

private const val BOARD_CANDIDATE_BATCH_BUDGET = 4

private val GOAL_FINISH_PIPELINE =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
  )
