package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.control.workControlEligible
import skillbill.engine.work.control.workDisplayGroup
import skillbill.engine.work.control.workDisplayPriority
import skillbill.engine.work.control.workLiveSection
import skillbill.engine.work.control.workPauseEligible
import skillbill.engine.work.control.workRequiresAttention
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusQueryWork
import skillbill.engine.work.model.IdeStatusQueryWorkReport
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.engine.work.pagination.AggregateSkipDiagnostics
import skillbill.engine.work.pagination.GoalPageRead
import skillbill.engine.work.pagination.ProjectedDetailItem
import skillbill.engine.work.pagination.RuntimePhasePageRead
import skillbill.engine.work.pagination.STATUS_READ_ATTEMPTS
import skillbill.engine.work.pagination.WorkBoardPagePosition
import skillbill.engine.work.pagination.WorkDetailAnchor
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.engine.work.pagination.detailNextAnchors
import skillbill.engine.work.pagination.emit
import skillbill.engine.work.pagination.goalSubtaskPageLimit
import skillbill.engine.work.pagination.includesDetailList
import skillbill.engine.work.pagination.invalidWorkPaginationRequest
import skillbill.engine.work.pagination.skipIncompatibleBoardItem
import skillbill.engine.work.pagination.workBoardNextCursor
import skillbill.engine.work.pagination.workBoardPagePosition
import skillbill.engine.work.pagination.workDetailPagePosition
import skillbill.engine.work.pagination.workDetailPagedResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelKey
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
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
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Inject
class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val workBoardItemProjection: WorkBoardItemProjection,
  private val ideStatusValidator: IdeStatusValidator,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val diagnostics: RuntimeDiagnostics,
  private val incidentProjectionBuilder: WorkIncidentProjectionBuilder,
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
    val repository = identityResult as IdeStatusRepositoryResolution.Ok
    val repositoryIdentity = repository.identity

    if (!database.databaseExists()) {
      return emit(IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt))
    }

    val currentBranch = branchSource.checkedOutBranch(repository.repoRoot)
    val queryWork = IdeStatusQueryWork()
    return try {
      fencedStatus(repository, currentBranch, observedAt, queryWork)
    } catch (error: SkillBillRuntimeException) {
      when {
        error.code == WorkflowFailureCode.INVALID_WORK_LIST_ROW ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible work-list record.",
            ),
            queryWork,
          )
        error.isInvalidWorkflowStateFailure() ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible workflow record.",
            ),
            queryWork,
          )
        else -> throw error
      }
    }
  }

  private fun fencedStatus(
    repository: IdeStatusRepositoryResolution.Ok,
    currentBranch: String?,
    observedAt: Instant,
    queryWork: IdeStatusQueryWork,
  ): IdeStatusResult {
    repeat(STATUS_READ_ATTEMPTS) { attempt ->
      if (attempt > 0) queryWork.retry()
      database.read { unitOfWork ->
        statusAttempt(unitOfWork, repository, currentBranch, observedAt, queryWork)
      }?.let { return it }
    }
    return emit(
      IdeStatusProblemSnapshots.incompatibleRecord(
        repositoryIdentity = repository.identity,
        observedAt = observedAt,
        message = "Work changed during the status read; retry.",
      ),
      queryWork,
    )
  }

  private fun statusAttempt(
    unitOfWork: UnitOfWork,
    repository: IdeStatusRepositoryResolution.Ok,
    currentBranch: String?,
    observedAt: Instant,
    queryWork: IdeStatusQueryWork,
  ): IdeStatusResult? {
    val before = unitOfWork.workList.observation().also { queryWork.statement(1) }
    val candidates =
      scopeToBranch(
        workBoardItemProjection.collectCandidates(
          unitOfWork,
          repository,
          currentBranch,
          observedAt,
          CandidateRead(queryWork = queryWork),
        ),
        currentBranch,
      )
    if (unitOfWork.workList.observation().also { queryWork.statement(1) } != before) return null
    val context =
      IdeStatusProjectionContext(
        unitOfWork = unitOfWork,
        repositoryIdentity = repository.identity,
        branchCorrelation = currentBranch,
        observedAt = observedAt,
        repoRoot = repository.repoRoot,
      )
    val selectable = candidates.filter { projector.readableForSelection(it, context) }
    val selected =
      IdeStatusSelectionPolicy.select(selectable, observedAt)
        ?: IdeStatusSelectionPolicy.select(candidates, observedAt)
        ?: return emit(
          IdeStatusProblemSnapshots.noMatchingWork(repository.identity, observedAt, currentBranch),
          queryWork,
        )
    val monitorRecord = selected.standaloneStatus?.takeIf { it.monitorTarget != null }
    val selectedContext =
      monitorRecord?.let { context.copy(repositoryIdentity = it.repositoryIdentity) } ?: context
    return emit(projector.project(candidate = selected, context = selectedContext).snapshot, queryWork)
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
      WorkBoardReadModelRead.read(workBoardItemProjection, unitOfWork, request, position)
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
      val skipDiagnostics = AggregateSkipDiagnostics()
      val items =
        ids.distinct().mapNotNull { id ->
          val found = workBoardItemProjection.selectedMatch(id, unitOfWork)
          val work = found.work
          val standalone = found.standalone
          val identity = standalone?.repositoryIdentity ?: work?.repositoryIdentity
          val root = identity?.let(workBoardItemProjection::decodeRepositoryRoot)
          if (identity == null || root == null) {
            diagnostics += workBoardItemProjection.repositoryIdentityDiagnostic(identity, workflowId = id)
            return@mapNotNull null
          }
          val candidates =
            try {
              workBoardItemProjection.collectCandidates(
                unitOfWork,
                IdeStatusRepositoryResolution.Ok(identity, root),
                null,
                observedAt,
                CandidateRead(
                  ResolvedWorkCandidates(listOfNotNull(work), listOfNotNull(standalone)),
                  skipDiagnostics = skipDiagnostics,
                ),
              )
            } catch (error: SkillBillRuntimeException) {
              return@mapNotNull skipIncompatibleBoardItem(
                error,
                diagnostics,
                skipDiagnostics,
                this.diagnostics,
                id,
              )
            }
          val candidate =
            candidates.singleOrNull()
              ?: throw invalidWorkListRowError("Selected work candidate cannot be projected.")
          val context = IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
          try {
            workBoardItemProjection.projectBoardItem(candidate, context, listOfNotNull(work))
          } catch (error: SkillBillRuntimeException) {
            skipIncompatibleBoardItem(error, diagnostics, skipDiagnostics, this.diagnostics, id)
          }
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

  fun detail(request: WorkDetailRequest): WorkDetailResult {
    val position = workDetailPagePosition(request, clock.instant())
    val observedAt = position.observedAt
    if (!database.databaseExists()) {
      return detailProblem(observedAt, WorkDetailProblemCode.ABSENT_DATABASE, "Skill Bill database is not present.")
        .copy(pageSize = request.pageSize)
    }
    var nextAnchors: Map<WorkDetailList, WorkDetailAnchor> = emptyMap()
    val result =
      database.read { unitOfWork ->
        if (request.cursor != null && position.anchor == null &&
          position.observation != unitOfWork.workList.observation()
        ) {
          invalidWorkPaginationRequest("Work changed. Refresh the detail.")
        }
        val match = workBoardItemProjection.detailMatch(request.id, unitOfWork)
        if (match is WorkDetailMatch.Problem) {
          return@read WorkDetailResult(observedAt, problem = match.problem)
        }
        val found = match as WorkDetailMatch.Found
        val resolved = found.work
        val standalone = found.standalone
        val identity = standalone?.repositoryIdentity ?: resolved?.repositoryIdentity
        val root = identity?.let(workBoardItemProjection::decodeRepositoryRoot)
        if (identity == null || root == null) {
          return@read detailProblem(
            observedAt,
            WorkDetailProblemCode.INVALID_REPOSITORY_IDENTITY,
            "Work item '${request.id}' has no usable repository identity.",
          )
        }
        val candidates =
          workBoardItemProjection.collectCandidates(
            unitOfWork,
            IdeStatusRepositoryResolution.Ok(identity, root),
            null,
            observedAt,
            CandidateRead(ResolvedWorkCandidates(listOfNotNull(resolved), listOfNotNull(standalone))),
          )
        val candidate =
          candidates.singleOrNull()
            ?: return@read detailProblem(
              observedAt,
              WorkDetailProblemCode.UNKNOWN_WORK_ITEM,
              "No projectable work item has id '${request.id}'.",
            )
        val context = IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
        val projected = projectDetailItem(candidate, context, resolved?.item, request, position)
        nextAnchors = projected.nextAnchors
        WorkDetailResult(
          observedAt,
          item = projected.item,
          observation = unitOfWork.workList.observation(),
        )
      }
    return workDetailPagedResult(result, request, position, nextAnchors)
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
  ): ProjectedDetailItem {
    val pageSize = request.pageSize
    val projection = projector.project(candidate, context)
    val snapshot = projection.snapshot
    val incidentSet = detailIncidents(candidate, context, snapshot)
    ideStatusValidator.validate(snapshot, "work-detail:${candidate.workflowId}")
    val standalone = candidate.standaloneStatus
    val completion = detailCompletion(snapshot, standalone, item, context.unitOfWork)
    val goal = item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_GOAL }
    val goalRead = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = false)
    val validationRead = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = true)
    val validationPage = validationRead?.page
    val goalStatus = goal?.let { work -> detailGoalStatus(work, validationPage, context) }
    val dependencies = detailDependencies(goal, context.unitOfWork.workList, request, position)
    val runtimeWorkflowId =
      goalStatus?.currentChildWorkflowId
        ?: item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME }?.workflowId
    val runtimeRead =
      runtimeWorkflowId?.takeIf { standalone == null }?.let { detailRuntimeStatus(it, request, position) }
    val runtimeStatus = runtimeRead?.status
    val history = detailExecutionHistory(candidate, context, request, position)
    val moreLists =
      detailContinuationLists(
        history.size > pageSize,
        (goalRead?.page?.remainingCount ?: 0) > goalSubtaskPageLimit(pageSize, completedOnly = false),
        (validationPage?.remainingCount ?: 0) > pageSize,
        runtimeRead?.hasMore == true && request.includesDetailList(WorkDetailList.PHASES),
      )
    val allMoreLists = if (dependencies?.nextOffset == null) moreLists else moreLists + WorkDetailList.DEPENDENCIES
    val shownHistory = history.take(pageSize)
    val detailItem =
      WorkDetailItem(
        snapshot = snapshot,
        recoveryWorkId = standalone?.invocationId ?: candidate.workflowId,
        executionHistory = shownHistory,
        repositoryRoot = context.repoRoot.toString(),
        stepPipeline = standalone?.let { standaloneStepPipeline(it.phaseId) } ?: runtimeStatus?.phaseIds.orEmpty(),
        finishedAt = completion.first,
        finishedAtSource = completion.second,
        goalManifest = goalRead?.page?.manifest,
        goalSpecBundle = goalRead?.page?.manifest?.let { projector.goalSpecBundle(it, context.repoRoot) },
        goalFinishPipeline = goalRead?.page?.let { detailGoalFinishPipeline(context, goalStatus) }.orEmpty(),
        runtimeStatus = runtimeStatus,
        goalStatus = goalStatus,
        pageOffset = position.offset,
        pageSize = pageSize,
        hasMore = allMoreLists.isNotEmpty(),
        moreLists = allMoreLists,
        dependencies = dependencies?.entries.orEmpty(),
        freshnessDeadline = snapshot.updatedAt.plus(IdeStatusFreshnessClassifier.FRESH_WINDOW).plusNanos(1),
        observation = context.unitOfWork.workList.observation(),
        liveSection = workLiveSection(snapshot, completion.first),
        controlEligible = workControlEligible(snapshot),
        pauseEligible = workPauseEligible(snapshot),
        requiresAttention = workRequiresAttention(snapshot),
        displayGroup = workDisplayGroup(snapshot, completion.first),
        displayPriority = workDisplayPriority(snapshot, completion.first),
        exactPauseReason = projection.exactPauseReason,
        databaseFileObservation = context.unitOfWork.databaseFileObservation,
        controlLease = context.unitOfWork.goalRunnerControls.controlState(candidate.workflowId).executionLease,
        incident = incidentSet.incident,
        incidentHistory = incidentSet.incidentHistory,
      )
    return ProjectedDetailItem(
      detailItem,
      detailNextAnchors(shownHistory, goalRead, validationRead, dependencies, runtimeRead),
    )
  }

  private fun detailCompletion(
    snapshot: IdeStatusSnapshot,
    standalone: StandalonePhaseStatusRecord?,
    item: WorkItem?,
    unitOfWork: UnitOfWork,
  ): Pair<Instant?, WorkBoardCompletionSource?> =
    if (workBoardItemProjection.isFinished(snapshot)) {
      workBoardItemProjection.completionEvidence(standalone, item, unitOfWork)
    } else {
      null to null
    }

  private fun detailExecutionHistory(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ) = if (request.includesDetailList(WorkDetailList.EXECUTIONS)) {
    workExecutionHistory(
      context.unitOfWork,
      candidate.standaloneStatus,
      candidate.workflowId,
      position,
      request.pageSize + 1,
    )
  } else {
    emptyList()
  }

  private fun detailIncidents(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    snapshot: IdeStatusSnapshot,
  ) = incidentProjectionBuilder.build(
    context.unitOfWork,
    candidate.workflowId.takeIf { candidate.standaloneStatus == null },
    candidate.standaloneStatus?.invocationId,
    snapshot.lifecycleState == IdeStatusLifecycleState.BLOCKED,
  )

  private fun detailDependencies(
    goal: WorkItem?,
    workList: WorkListRepository,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): WorkGoalDependencyPage? {
    val anchor = (position.anchor as? WorkDetailAnchor.Dependency)?.anchor
    return goal?.takeIf { request.includesDetailList(WorkDetailList.DEPENDENCIES) }
      ?.let {
        workList.goalDependencyPage(
          it.workflowId,
          if (anchor == null) position.offset else 0,
          request.pageSize,
          after = anchor,
        )
      }
  }

  private fun detailGoalPage(
    goal: WorkItem?,
    workList: WorkListRepository,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
    completedOnly: Boolean,
  ): GoalPageRead? {
    val list = if (completedOnly) WorkDetailList.VALIDATION else WorkDetailList.SUBTASKS
    val after = (position.anchor as? WorkDetailAnchor.Subtask)?.afterSubtaskId
    return goal?.takeIf { request.includesDetailList(list) }
      ?.let { work ->
        val offset = if (after == null) position.offset else 0
        val limit = goalSubtaskPageLimit(request.pageSize, completedOnly)
        workList.goalSubtaskPage(work.workflowId, offset, limit, completedOnly, after)?.let { page ->
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
          GoalPageRead(
            page.copy(manifest = page.manifest.copy(subtasks = visible)),
            list,
            page.manifest.subtasks.lastOrNull()?.id,
          )
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

  private fun detailGoalFinishPipeline(
    context: IdeStatusProjectionContext,
    goalStatus: GoalRunnerStatusProjection?,
  ): List<String> = projector.goalFinishPipeline(context.repoRoot, goalStatus?.goalAssessmentResult)

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

  private fun detailRuntimeStatus(
    workflowId: String,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): RuntimePhasePageRead? {
    val after = (position.anchor as? WorkDetailAnchor.Phase)?.afterPhaseId
    val limit = if (request.includesDetailList(WorkDetailList.PHASES)) request.pageSize else 0
    return featureTaskRuntimeStatusService.phasePage(workflowId, after, limit, position.offset)?.let { page ->
      if (after != null && after !in page.phaseIds) invalidWorkPaginationRequest("Unknown phase anchor.")
      val status = page.status
      RuntimePhasePageRead(
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
          phaseIds = page.phaseIds,
          totalPhaseCount = page.phaseIds.size,
        ),
        page.hasMore,
      )
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
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
        )
      else -> listOf(step)
    }

  fun toWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> = ideStatusValidator.toWirePayload(snapshot).toPayload()

  private fun scopeToBranch(
    candidates: List<IdeStatusCandidate>,
    branch: String?,
  ): List<IdeStatusCandidate> {
    if (branch == null) return candidates
    if (protectedBranchName(branch) != null) return candidates
    return candidates.filter { candidate -> IdeStatusBranchScope.matchesCandidate(candidate, branch) }
  }

  private fun emit(
    snapshot: IdeStatusSnapshot,
    queryWork: IdeStatusQueryWork? = null,
  ): IdeStatusResult {
    ideStatusValidator.validate(snapshot, sourceLabel = "ide-status")
    return IdeStatusResult(
      snapshot = snapshot,
      exitCode = snapshot.exitCode(),
      queryWork = queryWork?.report() ?: IdeStatusQueryWorkReport.NONE,
    )
  }
}

internal val GOAL_FINISH_PIPELINE =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
  )

internal object WorkBoardReadModelRead {
  fun read(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    request: WorkBoardRequest,
    position: WorkBoardPagePosition,
  ): WorkBoardResult {
    val observedAt = position.observedAt
    val observation = unitOfWork.workList.observation()
    if (request.cursor != null && position.observation != observation) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    val after = position.afterKey?.let(::decodePosition)
    val finishedFrom = observedAt.minus(request.finishedWithin.duration)
    val filter = normalizeFilter(request.filter)
    val diagnostics = mutableListOf<WorkBoardDiagnostic>()
    val scanRequest = ScanRequest(observedAt, finishedFrom, filter, diagnostics)
    val result =
      if (unitOfWork.workBoardReadModel.isComplete()) {
        readIndexed(projection, unitOfWork, request.pageSize, after, scanRequest)
      } else {
        val scanned = scanCandidates(projection, unitOfWork, scanRequest)
        val ordered = scanned.items.sortedWith { left, right -> PROJECTED_ORDER.compare(left.position, right.position) }
        val eligible = ordered.filter { after == null || PROJECTED_ORDER.compare(it.position, after) > 0 }
        BoardPage(eligible.take(request.pageSize), eligible.size > request.pageSize, scanned.examined)
      }
    val page = result.items
    val hasNext = result.hasNext
    val examined = result.examined
    val nextCursor =
      page.lastOrNull()?.takeIf { hasNext }?.let {
        workBoardNextCursor(request, observedAt, encodePosition(it.position), observation)
      }
    return WorkBoardResult(
      observedAt = observedAt,
      finishedWithin = request.finishedWithin,
      items =
        page.map {
          it.item.copy(observation = observation, databaseFileObservation = unitOfWork.databaseFileObservation)
        },
      diagnostics = diagnostics,
      pageSize = request.pageSize,
      nextCursor = nextCursor,
      observation = observation,
      candidatesExamined = examined,
    )
  }

  private fun readIndexed(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    pageSize: Int,
    after: ProjectedBoardPosition?,
    request: ScanRequest,
  ): BoardPage {
    val indexed = mutableListOf<ProjectedBoardItem>()
    var indexedAfter: WorkBoardReadModelKey? = null
    var examined = 0
    var moreRows = true
    val limit = maxOf(pageSize + 1, WORK_BOARD_CANDIDATE_BATCH_SIZE)
    while (moreRows) {
      val entries =
        unitOfWork.workBoardReadModel.page(
          WorkBoardReadModelPageQuery(request.finishedFrom, request.filter, indexedAfter, limit),
        ).entries
      examined += entries.size
      moreRows = entries.size == limit
      entries.mapNotNullTo(indexed) { entry -> indexedItem(projection, unitOfWork, entry, request) }
      indexedAfter = entries.lastOrNull()?.key ?: break
    }
    val ordered = indexed.sortedWith { left, right -> PROJECTED_ORDER.compare(left.position, right.position) }
    val eligible = ordered.filter { after == null || PROJECTED_ORDER.compare(it.position, after) > 0 }
    return BoardPage(eligible.take(pageSize), eligible.size > pageSize, examined)
  }

  private fun indexedItem(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    entry: WorkBoardReadModelPage.Entry,
    request: ScanRequest,
  ): ProjectedBoardItem? {
    val row = (entry as? WorkBoardReadModelPage.Entry.Decoded)?.row
    val needsProjection =
      row == null || row.reprojectAt?.let { !it.isAfter(request.observedAt) } == true ||
        row.membershipDeadline?.let { !it.isAfter(request.observedAt) } == true
    val item =
      if (needsProjection) {
        projection.projectBoardCandidate(
          unitOfWork,
          entry.workId,
          BoardProjectionWindow(request.observedAt, request.finishedFrom),
          request.diagnostics,
          AggregateSkipDiagnostics(),
        )
      } else {
        row.item
      } ?: return null
    val projectedId = projection.boardId(item)
    if (projectedId == null || projectedId != entry.workId || '\n' in projectedId) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    if (item.finishedAt?.isAfter(
        request.observedAt,
      ) == true || !projection.matchesFilter(item, request.filter)
    ) {
      return null
    }
    return ProjectedBoardItem(
      item,
      projectedId,
      ProjectedBoardPosition(item.displayPriority, item.snapshot.updatedAt, entry.workId),
    )
  }

  private data class BoardPage(val items: List<ProjectedBoardItem>, val hasNext: Boolean, val examined: Int)

  private fun scanCandidates(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    request: ScanRequest,
  ): BoardScanResult {
    val observedAt = request.observedAt
    val finishedFrom = request.finishedFrom
    val filter = request.filter
    val diagnostics = request.diagnostics
    val skipDiagnostics = AggregateSkipDiagnostics()
    val projected = mutableListOf<ProjectedBoardItem>()
    val seenIds = mutableSetOf<String>()
    var candidateCursor: String? = null
    var examined = 0

    while (true) {
      val rows =
        unitOfWork.workList.boardCandidates(
          WorkBoardCandidateQuery(
            finishedFrom = finishedFrom,
            observedAt = observedAt,
            afterKey = candidateCursor,
            limit = WORK_BOARD_CANDIDATE_BATCH_SIZE,
          ),
        )
      if (rows.isEmpty()) break
      candidateCursor = rows.last().key
      for (row in rows) {
        if (seenIds.add(row.id)) {
          examined++
          val item =
            projection.projectBoardCandidate(
              unitOfWork,
              row.id,
              BoardProjectionWindow(observedAt, finishedFrom),
              diagnostics,
              skipDiagnostics,
            )
          if (item != null) {
            val projectedId = projection.boardId(item)
            if (projectedId == null || projectedId != row.id || '\n' in projectedId) {
              invalidWorkPaginationRequest("Work changed. Refresh the board.")
            }
            if (item.finishedAt?.isAfter(observedAt) != true && projection.matchesFilter(item, filter)) {
              projected += ProjectedBoardItem(item, projectedId)
            }
          }
        }
      }
    }

    return BoardScanResult(projected, examined)
  }

  private data class BoardScanResult(val items: List<ProjectedBoardItem>, val examined: Int)

  private data class ScanRequest(
    val observedAt: Instant,
    val finishedFrom: Instant,
    val filter: String?,
    val diagnostics: MutableList<WorkBoardDiagnostic>,
  )

  private fun normalizeFilter(value: String?): String? =
    value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

  private fun encodePosition(position: ProjectedBoardPosition): String =
    listOf(
      PROJECTED_CURSOR_PREFIX.trimEnd('\n'),
      position.displayPriority.toString(),
      formatReadModelInstant(position.updatedAt),
      position.workId,
    )
      .joinToString("\n")

  private fun decodePosition(value: String): ProjectedBoardPosition {
    val parts = value.split('\n', limit = POSITION_FIELD_COUNT)
    if (!validPositionFields(parts)) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    val priority = parts[1].toIntOrNull() ?: invalidWorkPaginationRequest("Work changed. Refresh the board.")
    if (priority.toString() != parts[1]) invalidWorkPaginationRequest("Work changed. Refresh the board.")
    val updatedAt =
      try {
        Instant.parse(parts[2]).takeIf { it.toString() == parts[2] || formatReadModelInstant(it) == parts[2] }
          ?: invalidWorkPaginationRequest("Work changed. Refresh the board.")
      } catch (_: DateTimeParseException) {
        invalidWorkPaginationRequest("Work changed. Refresh the board.")
      }
    return ProjectedBoardPosition(priority, updatedAt, parts[POSITION_WORK_ID_INDEX])
  }

  private fun validPositionFields(parts: List<String>): Boolean =
    parts.size == POSITION_FIELD_COUNT && parts[0] == PROJECTED_CURSOR_PREFIX.trimEnd('\n') &&
      validPositionWorkId(parts[POSITION_WORK_ID_INDEX])

  private fun validPositionWorkId(id: String): Boolean = id.isNotBlank() && '\n' !in id

  private fun formatReadModelInstant(value: Instant): String = READ_MODEL_INSTANT_FORMATTER.format(value)

  private data class ProjectedBoardItem(
    val item: WorkBoardItem,
    val workId: String,
    private val indexedPosition: ProjectedBoardPosition? = null,
  ) {
    val position: ProjectedBoardPosition
      get() = indexedPosition ?: ProjectedBoardPosition(item.displayPriority, item.snapshot.updatedAt, workId)
  }

  private data class ProjectedBoardPosition(val displayPriority: Int, val updatedAt: Instant, val workId: String)

  private val PROJECTED_ORDER =
    compareBy<ProjectedBoardPosition> { it.displayPriority }
      .thenByDescending { it.updatedAt }
      .thenBy { it.workId }

  private const val PROJECTED_CURSOR_PREFIX = "p\n"
  private const val POSITION_FIELD_COUNT = 4
  private const val POSITION_WORK_ID_INDEX = 3
  private val READ_MODEL_INSTANT_FORMATTER =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)
}
