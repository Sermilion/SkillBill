package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.work.changes.WorkChangeBaselineEnvironment
import skillbill.engine.work.changes.workChangeBaseline
import skillbill.engine.work.eligibility.workControlEligible
import skillbill.engine.work.eligibility.workDisplayGroup
import skillbill.engine.work.eligibility.workDisplayPriority
import skillbill.engine.work.eligibility.workLiveSection
import skillbill.engine.work.eligibility.workPauseEligible
import skillbill.engine.work.eligibility.workRequiresAttention
import skillbill.engine.work.eligibility.workRunning
import skillbill.engine.work.failure.IdeStatusProblemSnapshots
import skillbill.engine.work.failure.exitCode
import skillbill.engine.work.incident.WorkIncidentProjectionBuilder
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
import skillbill.engine.work.pagination.WorkDetailAnchor
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.engine.work.pagination.detailNextAnchors
import skillbill.engine.work.pagination.emit
import skillbill.engine.work.pagination.goalSubtaskPageLimit
import skillbill.engine.work.pagination.includesDetailList
import skillbill.engine.work.pagination.invalidWorkPaginationRequest
import skillbill.engine.work.pagination.skipIncompatibleBoardItem
import skillbill.engine.work.pagination.workBoardPagePosition
import skillbill.engine.work.pagination.workDetailPagePosition
import skillbill.engine.work.pagination.workDetailPagedResult
import skillbill.engine.work.validation.withValidationBranchRuns
import skillbill.engine.work.verify.VerifyRunDetailsProjection
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePlanProgress
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
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.qa.QaRunPreview
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant

@Inject
public class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val workBoardItemProjection: WorkBoardItemProjection,
  private val ideStatusValidator: IdeStatusValidator,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val retainedProjection: RetainedWorkflowBoardProjection,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val goalRunnerStatusService: GoalRunnerStatusPort,
  private val diagnostics: RuntimeDiagnostics,
  private val incidentProjectionBuilder: WorkIncidentProjectionBuilder,
) {
  public fun initializeReadiness(): RuntimeDatabaseReadinessResult =
    featureTaskRuntimeStatusService.initializeReadiness()

  public fun status(request: IdeStatusRequest): IdeStatusResult {
    val observedAt = request.observedAt ?: clock.instant()
    val identityResult = workBoardItemProjection.resolveRepositoryIdentity(request.repoRoot)
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

    val refusal = readinessStatus(repositoryIdentity, observedAt)
    return if (refusal != null) {
      emit(refusal)
    } else {
      fencedStatus(
        repository,
        branchSource.checkedOutBranch(repository.repoRoot),
        observedAt,
        IdeStatusQueryWork(),
      )
    }
  }

  private fun readinessStatus(
    repositoryIdentity: String,
    observedAt: Instant,
  ): IdeStatusSnapshot? {
    val readiness = featureTaskRuntimeStatusService.initializeReadiness()
    if (readiness is RuntimeDatabaseReadinessResult.Ready && !readiness.existing) {
      return IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt)
    }
    if (readiness is RuntimeDatabaseReadinessResult.Refused) {
      val item = retainedProjection.repositoryItem(repositoryIdentity, observedAt, readiness.failure)
      return item?.snapshot ?: IdeStatusProblemSnapshots.incompatibleRecord(
        repositoryIdentity,
        observedAt,
        readiness.failure.message.orEmpty(),
      )
    }
    return null
  }

  private fun fencedStatus(
    repository: IdeStatusRepositoryResolution.Ok,
    currentBranch: String?,
    observedAt: Instant,
    queryWork: IdeStatusQueryWork,
  ): IdeStatusResult {
    return try {
      repeat(STATUS_READ_ATTEMPTS) { attempt ->
        if (attempt > 0) queryWork.retry()
        database.read { unitOfWork ->
          statusAttempt(unitOfWork, repository, currentBranch, observedAt, queryWork)
        }?.let { return it }
      }
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(
          repositoryIdentity = repository.identity,
          observedAt = observedAt,
          message = "Work changed during the status read; retry.",
        ),
        queryWork,
      )
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfDatabaseFailure()
      if (error.code != WorkflowFailureCode.INVALID_WORK_LIST_ROW && !error.isInvalidWorkflowStateFailure()) throw error
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Work status cannot restore a saved record; reporting the retained schema failure.",
        error,
      )
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(repository.identity, observedAt, error.message.orEmpty()),
        queryWork,
      )
    }
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
    val snapshot =
      try {
        projector.project(candidate = selected, context = selectedContext).snapshot
      } catch (error: SkillBillRuntimeException) {
        error.rethrowIfDatabaseFailure()
        if (error.code != WorkflowFailureCode.INVALID_WORK_LIST_ROW && !error.isInvalidWorkflowStateFailure()) {
          throw error
        }
        val retained = retainedProjection.item(selected.workflowId, observedAt, error) ?: throw error
        retained.snapshot.copy(
          workflowFamily = selected.workflowFamily,
          branchCorrelation = selected.branchCorrelation,
          execution = selected.execution,
        )
      }
    return emit(snapshot, queryWork)
  }

  public fun board(request: WorkBoardRequest): WorkBoardResult {
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
    val readiness = featureTaskRuntimeStatusService.initializeReadiness()
    if (readiness is RuntimeDatabaseReadinessResult.Ready && !readiness.existing) {
      return WorkBoardResult(
        observedAt,
        request.finishedWithin,
        emptyList(),
        absentDatabase = true,
        pageSize = request.pageSize,
      )
    }
    if (readiness is RuntimeDatabaseReadinessResult.Refused) {
      return retainedProjection.board(
        request,
        observedAt,
        readiness.failure,
      )
    }
    WorkBoardReadModelRefresh.refresh(database, workBoardItemProjection, clock, diagnostics)
    val result =
      database.read { unitOfWork ->
        WorkBoardReadModelRead.read(workBoardItemProjection, unitOfWork, request, position)
      }
    return result.copy(
      items =
        retainedProjection.attach(
          workBoardItemProjection.withBuilds(result.items, clock.instant()),
          observedAt,
        ),
    )
  }

  public fun projectBoardSelection(
    ids: List<String>,
    request: WorkBoardRequest,
  ): WorkBoardResult {
    val readiness = featureTaskRuntimeStatusService.initializeReadiness()
    if (readiness is RuntimeDatabaseReadinessResult.Ready && !readiness.existing) {
      val observedAt = request.observedAt ?: clock.instant()
      return WorkBoardResult(observedAt, request.finishedWithin, emptyList(), pageSize = request.pageSize)
    }
    if (readiness is RuntimeDatabaseReadinessResult.Refused) {
      require(ids.size <= request.pageSize)
      val observedAt = request.observedAt ?: clock.instant()
      return WorkBoardResult(
        observedAt,
        request.finishedWithin,
        ids.distinct().mapNotNull { retainedProjection.item(it, observedAt, readiness.failure) },
        pageSize = request.pageSize,
      )
    }
    require(ids.size <= request.pageSize)
    val observedAt = request.observedAt ?: clock.instant()
    val result =
      database.read { unitOfWork ->
        val diagnostics = mutableListOf<WorkBoardDiagnostic>()
        val skipDiagnostics = AggregateSkipDiagnostics()
        val items =
          ids.distinct().mapNotNull { id ->
            try {
              selectedBoardItem(unitOfWork, id, observedAt, skipDiagnostics)
            } catch (error: SkillBillRuntimeException) {
              error.rethrowIfDatabaseFailure()
              retainedProjection.item(id, observedAt, error)?.also {
                diagnostics +=
                  WorkBoardDiagnostic(
                    WorkBoardDiagnosticReason.INCOMPATIBLE_RECORD,
                    it.snapshot.repositoryIdentity,
                    id,
                  )
              } ?: skipIncompatibleBoardItem(error, diagnostics, skipDiagnostics, this.diagnostics, id)
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
    return result.copy(
      items =
        retainedProjection.attach(
          workBoardItemProjection.withBuilds(result.items, clock.instant()),
          observedAt,
        ),
    )
  }

  private fun selectedBoardItem(
    unitOfWork: UnitOfWork,
    id: String,
    observedAt: Instant,
    skipDiagnostics: AggregateSkipDiagnostics,
  ): WorkBoardItem {
    val found = workBoardItemProjection.selectedMatch(id, unitOfWork)
    val work = found.work
    val standalone = found.standalone
    val identity = standalone?.repositoryIdentity ?: work?.repositoryIdentity
    val root = identity?.let(workBoardItemProjection::decodeRepositoryRoot)
    if (identity == null || root == null) {
      throw invalidWorkListRowError("Selected work '$id' has no usable repository identity.")
    }
    val candidates =
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
    val candidate =
      candidates.singleOrNull()
        ?: throw invalidWorkListRowError("Selected work candidate cannot be projected.")
    val context =
      IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
    return workBoardItemProjection.projectBoardItem(candidate, context, listOfNotNull(work))
  }

  public fun detail(request: WorkDetailRequest): WorkDetailResult {
    val position = workDetailPagePosition(request, clock.instant())
    val observedAt = position.observedAt
    if (!database.databaseExists()) {
      return detailProblem(observedAt, WorkDetailProblemCode.ABSENT_DATABASE, "Skill Bill database is not present.")
        .copy(pageSize = request.pageSize)
    }
    val readiness = featureTaskRuntimeStatusService.initializeReadiness()
    if (readiness is RuntimeDatabaseReadinessResult.Ready && !readiness.existing) {
      return detailProblem(
        observedAt,
        WorkDetailProblemCode.ABSENT_DATABASE,
        "Skill Bill database is not present.",
      ).copy(pageSize = request.pageSize)
    }
    if (readiness is RuntimeDatabaseReadinessResult.Refused) {
      val item = retainedProjection.item(request.id, observedAt, readiness.failure)
      return if (item == null) {
        detailProblem(observedAt, WorkDetailProblemCode.INCOMPATIBLE_RECORD, readiness.failure.message.orEmpty())
      } else {
        WorkDetailResult(observedAt, item = retainedDetailItem(item, request.id), pageSize = request.pageSize)
      }
    }
    return preparedDetail(request, position)
  }

  private fun preparedDetail(
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): WorkDetailResult {
    val observedAt = position.observedAt
    var nextAnchors: Map<WorkDetailList, WorkDetailAnchor> = emptyMap()
    val result =
      try {
        database.read { unitOfWork ->
          readDetail(unitOfWork, request, position).also { nextAnchors = it.second }.first
        }
      } catch (error: SkillBillRuntimeException) {
        error.rethrowIfDatabaseFailure()
        if (error.code != WorkflowFailureCode.INVALID_WORK_LIST_ROW && !error.isInvalidWorkflowStateFailure()) {
          throw error
        }
        val retained = retainedProjection.item(request.id, observedAt, error) ?: throw error
        WorkDetailResult(observedAt, item = retainedDetailItem(retained, request.id), pageSize = request.pageSize)
      }
    if (result.item == null && result.problem?.code == WorkDetailProblemCode.UNKNOWN_WORK_ITEM) {
      val retained =
        retainedProjection.item(
          request.id,
          observedAt,
          invalidWorkListRowError("Retained work could not be projected from its current source."),
        )
      if (retained != null) {
        return WorkDetailResult(
          observedAt,
          item = retainedDetailItem(retained, request.id),
          pageSize = request.pageSize,
        )
      }
    }
    val overlaid =
      result.item?.let { item ->
        if (item.snapshot.problem?.code == IdeStatusProblemCode.INCOMPATIBLE_RECORD) {
          item
        } else {
          workBoardItemProjection.withBuild(item, clock.instant())
        }
      }
    return workDetailPagedResult(result.copy(item = overlaid), request, position, nextAnchors)
  }

  private fun readDetail(
    unitOfWork: UnitOfWork,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): Pair<WorkDetailResult, Map<WorkDetailList, WorkDetailAnchor>> {
    val observedAt = position.observedAt
    if (request.cursor != null && position.anchor == null &&
      position.observation != unitOfWork.workList.observation()
    ) {
      invalidWorkPaginationRequest("Work changed. Refresh the detail.")
    }
    val match = workBoardItemProjection.detailMatch(request.id, unitOfWork)
    if (match is WorkDetailMatch.Problem) {
      return WorkDetailResult(observedAt, problem = match.problem) to emptyMap()
    }
    val found = match as WorkDetailMatch.Found
    val resolved = found.work
    val standalone = found.standalone
    val identity = standalone?.repositoryIdentity ?: resolved?.repositoryIdentity
    val root = identity?.let(workBoardItemProjection::decodeRepositoryRoot)
    if (identity == null || root == null) {
      return detailProblem(
        observedAt,
        WorkDetailProblemCode.INVALID_REPOSITORY_IDENTITY,
        "Work item '${request.id}' has no usable repository identity.",
      ) to emptyMap()
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
        ?: return detailProblem(
          observedAt,
          WorkDetailProblemCode.UNKNOWN_WORK_ITEM,
          "No projectable work item has id '${request.id}'.",
        ) to emptyMap()
    val context =
      IdeStatusProjectionContext(unitOfWork, identity, candidate.branchCorrelation, observedAt, root)
    val projected = projectDetailItem(candidate, context, resolved?.item, request, position)
    return WorkDetailResult(
      observedAt,
      item = projected.item,
      observation = unitOfWork.workList.observation(),
    ) to projected.nextAnchors
  }

  private fun projectDetailItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    item: WorkItem?,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): ProjectedDetailItem {
    val projection = projector.project(candidate, context)
    val snapshot = projection.snapshot
    projection.migrationDiagnostic?.let { diagnostic ->
      val retained =
        retainedProjection.item(diagnostic, context.observedAt).copy(
          snapshot = snapshot,
          repositoryRoot = context.repoRoot.toString(),
        )
      return ProjectedDetailItem(retainedDetailItem(retained, request.id), emptyMap())
    }
    return candidate.projectReadableDetailItem(context, item, request, position, projection)
  }

  private fun IdeStatusCandidate.projectReadableDetailItem(
    context: IdeStatusProjectionContext,
    item: WorkItem?,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
    projection: IdeStatusProjection,
  ): ProjectedDetailItem {
    val snapshot = projection.snapshot
    val incidentSet = detailIncidents(this, context, snapshot)
    val standalone = standaloneStatus
    val completion = detailCompletion(snapshot, standalone, item, context.unitOfWork)
    val goalReads = detailGoalReads(context, item, standalone, request, position)
    val goalRead = goalReads.goalRead
    val validationRead = goalReads.validationRead
    val goalStatus = goalReads.goalStatus
    val goalWorktree = goalReads.goalWorktree
    val dependencies = goalReads.dependencies
    val runtimeRead = goalReads.runtimeRead
    val history =
      detailExecutionHistory(
        this,
        context,
        request,
        position,
        WorkflowExecutionCurrentState(snapshot.execution?.executionId, snapshot.lifecycleState, completion.first),
      )
    val moreLists = detailMoreLists(history.size > request.pageSize, goalRead, validationRead, runtimeRead, request)
    val planOutput = standalonePlanOutput(standalone, context.unitOfWork)
    val detailItem =
      WorkDetailItem(
        snapshot = snapshot,
        recoveryWorkId = standalone?.invocationId ?: workflowId,
        executionHistory = history.take(request.pageSize),
        selectedExecution = workSelectedExecution(standalone, context.unitOfWork),
        repositoryRoot = context.repoRoot.toString(),
        stepPipeline = detailStepPipeline(standalone, runtimeRead?.status?.phaseIds.orEmpty()),
        finishedAt = completion.first,
        finishedAtSource = completion.second,
        goalManifest = goalRead?.page?.manifest,
        goalSpecBundle = goalRead?.page?.manifest?.let { projector.goalSpecBundle(it, context.repoRoot) },
        goalFinishPipeline = goalRead?.page?.let { detailGoalFinishPipeline(context, goalStatus) }.orEmpty(),
        runtimeStatus = runtimeRead?.status,
        goalStatus = goalStatus,
        qaPreview = detailQaPreview(goalStatus, context.unitOfWork.featureTaskQaOutcomes),
        qaPreviewSubtaskId = goalStatus?.currentSubtaskId.takeIf { goalStatus?.currentChildWorkflowId != null },
        pageOffset = position.offset,
        pageSize = request.pageSize,
        hasMore = moreLists.isNotEmpty() || dependencies?.nextOffset != null,
        moreLists = if (dependencies?.nextOffset == null) moreLists else moreLists + WorkDetailList.DEPENDENCIES,
        dependencies = dependencies?.entries.orEmpty(),
        freshnessDeadline = snapshot.updatedAt.plus(IdeStatusFreshnessClassifier.FRESH_WINDOW).plusNanos(1),
        observation = context.unitOfWork.workList.observation(),
        exactPauseReason = projection.exactPauseReason,
        databaseFileObservation = context.unitOfWork.databaseFileObservation,
        controlLease = context.unitOfWork.goalRunnerControls.controlState(workflowId).executionLease,
        incident = incidentSet.incident,
        incidentHistory = incidentSet.incidentHistory,
        changeBaseline = detailChangeBaseline(standalone, goalRead?.page?.manifest, item, goalWorktree, context),
        goalWorktree = goalWorktree,
        standalonePlanProgress = detailPlanProgress(standalone, context, position, planOutput),
        verifyRun = VerifyRunDetailsProjection.project(this, context.unitOfWork),
      ).withDerivedStatus().withPlanOutput(planOutput).withValidationBranchRuns(standalone, context.unitOfWork)
    val nextAnchors =
      detailPageNextAnchors(history.take(request.pageSize), goalRead, validationRead, dependencies, runtimeRead)
    return withPlanOutputPage(
      ProjectedDetailItem(detailItem, nextAnchors),
      standalone,
      context.unitOfWork,
      request,
      position,
    )
  }

  private fun detailQaPreview(
    goalStatus: GoalRunnerStatusProjection?,
    outcomes: FeatureTaskQaOutcomeRepository,
  ): QaRunPreview? {
    val childWorkflowId = goalStatus?.currentChildWorkflowId
    return if (childWorkflowId != null) {
      outcomes.find(childWorkflowId)?.details?.preview
    } else {
      goalStatus?.goalQaResult?.details?.preview
    }
  }

  private fun detailGoalReads(
    context: IdeStatusProjectionContext,
    item: WorkItem?,
    standalone: StandalonePhaseStatusRecord?,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): DetailGoalReads {
    val goal = item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_GOAL }
    val goalRead = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = false)
    val validationRead = detailGoalPage(goal, context.unitOfWork.workList, request, position, completedOnly = true)
    val goalStatus = goal?.let { work -> detailGoalStatus(work, validationRead?.page, goalRead?.page, context) }
    val goalWorktree = goal?.let { workBoardItemProjection.goalWorktreeView(it.workflowId, context.unitOfWork) }
    val dependencies = detailDependencies(goal, context.unitOfWork.workList, request, position)
    val runtimeRead =
      (goalStatus?.currentChildWorkflowId ?: item?.taskRuntimeWorkflowId())
        ?.takeIf { standalone == null }?.let { detailRuntimeStatus(it, request, position) }
    return DetailGoalReads(goal, goalRead, validationRead, goalStatus, goalWorktree, dependencies, runtimeRead)
  }

  private fun detailPageNextAnchors(
    history: List<WorkExecutionInstance>,
    goalRead: GoalPageRead?,
    validationRead: GoalPageRead?,
    dependencies: WorkGoalDependencyPage?,
    runtimeRead: RuntimePhasePageRead?,
  ) = detailNextAnchors(history, goalRead, validationRead, dependencies, runtimeRead)

  private fun withPlanOutputPage(
    projection: ProjectedDetailItem,
    standalone: StandalonePhaseStatusRecord?,
    unitOfWork: UnitOfWork,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): ProjectedDetailItem {
    val item = projection.item
    val lines = detailOutputLines(standalone, unitOfWork, request, position)
    val more = if (lines.size > request.pageSize) setOf(WorkDetailList.PLAN_OUTPUT) else emptySet()
    val page = lines.take(request.pageSize)
    val anchor =
      page.lastOrNull()?.let {
        mapOf(WorkDetailList.PLAN_OUTPUT to WorkDetailAnchor.Output(it.sequence))
      }.orEmpty()
    return ProjectedDetailItem(
      item.copy(planOutputLines = page, moreLists = item.moreLists + more, hasMore = item.hasMore || more.isNotEmpty()),
      projection.nextAnchors + anchor,
    )
  }

  private fun WorkDetailItem.withPlanOutput(output: StandalonePhaseOutputSnapshot): WorkDetailItem =
    copy(
      planActivity = output.activities,
      planOutputTail = output.tail,
      planOutputUpdatedAt = output.latestLineAt,
      planSubtaskLatestLines = output.latestAttributedLines,
    )

  private fun WorkDetailItem.withDerivedStatus(): WorkDetailItem =
    copy(
      liveSection = workLiveSection(snapshot, finishedAt),
      controlEligible = workControlEligible(snapshot),
      pauseEligible = workPauseEligible(snapshot),
      running = workRunning(snapshot, finishedAt),
      requiresAttention = workRequiresAttention(snapshot),
      displayGroup = workDisplayGroup(snapshot, finishedAt),
      displayPriority = workDisplayPriority(snapshot, finishedAt),
    )

  private fun detailOutputLines(
    standalone: StandalonePhaseStatusRecord?,
    unitOfWork: UnitOfWork,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): List<StandalonePhaseOutputLine> {
    if (standalone?.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN ||
      !request.includesDetailList(WorkDetailList.PLAN_OUTPUT)
    ) {
      return emptyList()
    }
    return unitOfWork.standalonePhaseStatuses.readOutputLines(
      standalone.executionId,
      (position.anchor as? WorkDetailAnchor.Output)?.beforeSequence,
      request.pageSize + 1,
    )
  }

  private fun detailPlanProgress(
    standalone: StandalonePhaseStatusRecord?,
    context: IdeStatusProjectionContext,
    position: WorkDetailPagePosition,
    planOutput: StandalonePhaseOutputSnapshot,
  ): StandalonePlanProgress? {
    return standalone
      ?.takeIf { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
      ?.let {
        projector.standalonePlanProgressProjector.project(
          it,
          context.repoRoot,
          context.unitOfWork,
          position.observedAt,
          planOutput,
        )
      }
  }

  private fun detailStepPipeline(
    standalone: StandalonePhaseStatusRecord?,
    runtimePhases: List<String>,
  ): List<String> = standalone?.let { standaloneStepPipeline(it.phaseId) } ?: runtimePhases

  private fun WorkItem.taskRuntimeWorkflowId(): String? =
    workflowId.takeIf { workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME }

  private fun detailChangeBaseline(
    standalone: StandalonePhaseStatusRecord?,
    manifest: DecompositionManifest?,
    item: WorkItem?,
    goalWorktree: GoalWorktreeView?,
    context: IdeStatusProjectionContext,
  ) = workChangeBaseline(
    standalone,
    manifest,
    item,
    WorkChangeBaselineEnvironment(
      context.repoRoot,
      context.unitOfWork.workflowStates,
      diagnostics,
      goalWorktree,
    ),
  )

  private fun standalonePlanOutput(
    standalone: StandalonePhaseStatusRecord?,
    unitOfWork: UnitOfWork,
  ) = standalone?.takeIf { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
    ?.let { record ->
      catchingNonCancellation { unitOfWork.standalonePhaseStatuses.readOutput(record.executionId) }
        .onFailure { error ->
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Standalone plan output read failed",
            error,
          )
        }.getOrNull()
    } ?: StandalonePhaseOutputSnapshot()

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
    current: WorkflowExecutionCurrentState,
  ) = if (request.includesDetailList(WorkDetailList.EXECUTIONS)) {
    context.unitOfWork.workExecutionHistory(
      candidate.standaloneStatus,
      candidate.workflowId,
      position,
      request.pageSize + 1,
      current,
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
  ).also { ideStatusValidator.validate(snapshot, "work-detail:${candidate.workflowId}") }

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
    subtaskPage: WorkGoalSubtaskPage?,
    context: IdeStatusProjectionContext,
  ) = goalRunnerStatusService.statusForWorkflow(
    work.workflowId,
    GoalRunnerStatusRequest(
      issueKey = requireNotNull(work.issueKey),
      repoRoot = context.repoRoot,
      pruneCheckpointRefs = false,
      completedSubtaskValidationIds =
        validationPage?.manifest?.subtasks.orEmpty().mapTo(mutableSetOf()) { it.id },
      phaseResultSubtaskIds = subtaskPage?.manifest?.subtasks?.mapTo(mutableSetOf()) { it.id },
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

  private fun detailMoreLists(
    moreExecutions: Boolean,
    goalRead: GoalPageRead?,
    validationRead: GoalPageRead?,
    runtimeRead: RuntimePhasePageRead?,
    request: WorkDetailRequest,
  ): Set<WorkDetailList> =
    detailContinuationLists(
      moreExecutions,
      (goalRead?.page?.remainingCount ?: 0) > goalSubtaskPageLimit(request.pageSize, completedOnly = false),
      (validationRead?.page?.remainingCount ?: 0) > request.pageSize,
      runtimeRead?.hasMore == true && request.includesDetailList(WorkDetailList.PHASES),
    )

  private fun detailRuntimeStatus(
    workflowId: String,
    request: WorkDetailRequest,
    position: WorkDetailPagePosition,
  ): RuntimePhasePageRead? {
    val after = (position.anchor as? WorkDetailAnchor.Phase)?.afterPhaseId
    val limit = if (request.includesDetailList(WorkDetailList.PHASES)) request.pageSize else 0
    return featureTaskRuntimeStatusService.phasePagePrepared(workflowId, after, limit, position.offset)?.let { page ->
      if (after != null && after !in page.phaseIds) invalidWorkPaginationRequest("Unknown phase anchor.")
      val status = page.status
      val briefings =
        if (status.phases.isEmpty()) emptyMap() else featureTaskRuntimeStatusService.phaseBriefingTexts(workflowId)
      RuntimePhasePageRead(
        WorkDetailRuntimeStatus(
          phases =
            status.phases.map { phase ->
              WorkDetailPhase(
                phase.phaseId, phase.status, phase.attemptCount, phase.resolvedAgentId, phase.finished,
                phase.executionOrigin, phase.continuationKind, phase.launchedModel, phase.launchedEffort,
                briefings[phase.phaseId],
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

  public fun toWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
    ideStatusValidator.toWirePayload(snapshot).toPayload()

  private fun scopeToBranch(
    candidates: List<IdeStatusCandidate>,
    branch: String?,
  ): List<IdeStatusCandidate> {
    if (branch == null) return candidates
    if (protectedBranchName(branch) != null) return candidates
    return candidates.filter { candidate ->
      candidate.migrationDiagnostic != null && candidate.branchCorrelation == null ||
        IdeStatusBranchScope.matchesCandidate(candidate, branch)
    }
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

private data class DetailGoalReads(
  val goal: WorkItem?,
  val goalRead: GoalPageRead?,
  val validationRead: GoalPageRead?,
  val goalStatus: GoalRunnerStatusProjection?,
  val goalWorktree: GoalWorktreeView?,
  val dependencies: WorkGoalDependencyPage?,
  val runtimeRead: RuntimePhasePageRead?,
)

internal val GOAL_FINISH_PIPELINE =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
  )

private fun retainedDetailItem(
  item: WorkBoardItem,
  recoveryWorkId: String = requireNotNull(item.snapshot.workflowId),
): WorkDetailItem =
  WorkDetailItem(
    snapshot = item.snapshot,
    recoveryWorkId = recoveryWorkId,
    repositoryRoot = item.repositoryRoot,
    stepPipeline = emptyList(),
    liveSection = item.liveSection,
    requiresAttention = true,
    exactPauseReason = item.exactPauseReason,
  )

private fun detailProblem(
  observedAt: Instant,
  code: WorkDetailProblemCode,
  message: String,
): WorkDetailResult = WorkDetailResult(observedAt, problem = WorkDetailProblem(code, message))
