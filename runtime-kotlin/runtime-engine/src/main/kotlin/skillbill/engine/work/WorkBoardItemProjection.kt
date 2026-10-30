package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.work.eligibility.workControlEligible
import skillbill.engine.work.eligibility.workDisplayGroup
import skillbill.engine.work.eligibility.workDisplayPriority
import skillbill.engine.work.eligibility.workLiveSection
import skillbill.engine.work.eligibility.workPauseEligible
import skillbill.engine.work.eligibility.workPolicy
import skillbill.engine.work.eligibility.workRequiresAttention
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusQueryWork
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.engine.work.observation.workerOwnership
import skillbill.engine.work.pagination.AggregateSkipDiagnostics
import skillbill.engine.work.pagination.CANDIDATE_ROW_LOOKUPS
import skillbill.engine.work.pagination.IDE_STATUS_STATUS_SEAM
import skillbill.engine.work.pagination.emit
import skillbill.engine.work.pagination.readStatusStandaloneRecords
import skillbill.engine.work.pagination.readStatusWorkItems
import skillbill.engine.work.pagination.skipIncompatibleBoardItem
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentityFamily
import skillbill.idestatus.model.IdeStatusRepositoryIdentityPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentitySource
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.WorkBoardReadModelProjector
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.idestatus.model.WorkDetailProblem
import skillbill.ports.idestatus.model.WorkDetailProblemCode
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

@Inject
class WorkBoardItemProjection(
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun projectBoardCandidate(
    unitOfWork: UnitOfWork,
    id: String,
    window: BoardProjectionWindow,
    diagnostics: MutableList<WorkBoardDiagnostic>,
    skipDiagnostics: AggregateSkipDiagnostics,
  ): WorkBoardItem? {
    val (observedAt, finishedFrom) = window
    return try {
      val found = detailMatch(id, unitOfWork) as? WorkDetailMatch.Found ?: return null
      val identity = found.standalone?.repositoryIdentity ?: found.work?.repositoryIdentity
      val root = identity?.let(::decodeRepositoryRoot)
      if (identity == null || root == null) {
        diagnostics += repositoryIdentityDiagnostic(identity, workflowId = id)
        return null
      }
      val work = listOfNotNull(found.work)
      val candidates =
        collectCandidates(
          unitOfWork,
          IdeStatusRepositoryResolution.Ok(identity, root),
          null,
          observedAt,
          CandidateRead(
            ResolvedWorkCandidates(work, listOfNotNull(found.standalone)),
            skipDiagnostics = skipDiagnostics,
          ),
        )
      candidates.singleOrNull()?.let { candidate ->
        projectRetainedBoardItem(
          candidate,
          IdeStatusProjectionContext(unitOfWork, identity, null, observedAt, root),
          work,
          finishedFrom,
          diagnostics,
        )
      }
    } catch (error: SkillBillRuntimeException) {
      skipIncompatibleBoardItem(error, diagnostics, skipDiagnostics, this.diagnostics, id)
    }
  }

  internal fun projectRetainedBoardItem(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    work: List<ResolvedWorkItem>,
    finishedFrom: Instant,
    diagnostics: MutableList<WorkBoardDiagnostic>,
  ): WorkBoardItem? {
    val item = projectBoardItem(candidate, context, work)
    if (item.finishedAt == null && !isFinished(item.snapshot)) return item
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

  internal fun resolvedWorkItems(
    items: List<WorkItem>,
    unitOfWork: UnitOfWork,
  ): List<ResolvedWorkItem> =
    items.map { item ->
      val execution = unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
      ResolvedWorkItem(item, repositoryIdentityFor(item, unitOfWork, execution), execution)
    }

  internal fun projectBoardItem(
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
    val superseded = supersededPhase(candidate.standaloneStatus, context.unitOfWork, context.observedAt)
    val finishedAt = completion.first ?: candidate.standaloneStatus?.updatedAt?.takeIf { superseded }
    val windowed =
      workPolicy(
        snapshot,
        finishedAt,
        context.observedAt,
        successfulGoalCompletion = completion.second == WorkBoardCompletionSource.GOAL_COMPLETION,
      )
    return WorkBoardItem(
      snapshot,
      context.repoRoot.toString(),
      finishedAt,
      completion.second,
      context.unitOfWork.goalRunnerControls.controlState(candidate.workflowId).executionLease,
      context.unitOfWork.workList.observation(),
      snapshot.updatedAt.plus(IdeStatusFreshnessClassifier.FRESH_WINDOW).plusNanos(1),
      workLiveSection(snapshot, finishedAt).takeUnless { superseded },
      workControlEligible(snapshot) && !superseded,
      workRequiresAttention(snapshot) && !superseded,
      workDisplayGroup(snapshot, completion.first),
      workDisplayPriority(snapshot, completion.first),
      projection.exactPauseReason,
      context.unitOfWork.databaseFileObservation,
      workPauseEligible(snapshot) && !superseded,
      running = windowed.running && !superseded,
      recentlyFinishedPhase = windowed.recentlyFinishedPhase,
      doneWithinDay = windowed.doneWithinDay,
      membershipDeadline = windowed.membershipDeadline,
      executingAgentId = projection.executingAgentId,
    )
  }

  internal fun detailMatch(
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

  internal fun selectedMatch(
    id: String,
    unitOfWork: UnitOfWork,
  ): WorkDetailMatch.Found =
    detailMatch(id, unitOfWork) as? WorkDetailMatch.Found
      ?: throw invalidWorkListRowError("Selected work candidate is unavailable or ambiguous.")

  internal fun isFinished(snapshot: IdeStatusSnapshot): Boolean =
    snapshot.lifecycleState in setOf(IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.CANCELED) ||
      snapshot.lifecycleState == IdeStatusLifecycleState.FAILED

  internal fun repositoryIdentityFor(
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

  internal fun goalChildRepositoryIdentity(
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): String? =
    when (val lookup = unitOfWork.workList.repositoryIdentityForGoalChildren(workflowId)) {
      is IssueRepositoryIdentityLookup.Unique -> lookup.repositoryIdentity
      else -> null
    }

  internal fun verifyIssueRepositoryIdentity(
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

  internal fun canonicalDetailMatches(matches: List<WorkItem>): List<WorkItem> {
    if (matches.size != 2) return matches
    val goal = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_GOAL } ?: return matches
    val runtime = matches.singleOrNull { it.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME } ?: return matches
    val goalIssue = goal.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    val runtimeIssue = runtime.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    return if (goalIssue != null && goalIssue == runtimeIssue) listOf(goal) else matches
  }

  internal fun repositoryIdentityDiagnostic(
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

  internal fun completionEvidence(
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

  internal fun collectCandidates(
    unitOfWork: UnitOfWork,
    repository: IdeStatusRepositoryResolution.Ok,
    branch: String?,
    observedAt: Instant,
    read: CandidateRead = CandidateRead(),
  ): List<IdeStatusCandidate> {
    val resolved = read.resolved
    val queryWork = read.queryWork
    val work = resolved?.work?.map { it.item } ?: readStatusWorkItems(unitOfWork, queryWork)
    val issueKeysWithGoals =
      work
        .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        .mapNotNull { it.issueKey?.uppercase() }
        .toSet()
    val workflowCandidates =
      work.mapNotNull { item ->
        queryWork?.statement()
        if (isExcludedGoalChild(routeScopeFor(item, unitOfWork), item.issueKey, issueKeysWithGoals)) {
          null
        } else {
          if (resolved == null) repeat(CANDIDATE_ROW_LOOKUPS) { queryWork?.statement() }
          toCandidateOrSkip(item, unitOfWork, repository, branch, read)
        }
      }
    val standaloneRecords =
      resolved?.standalone
        ?: readStatusStandaloneRecords(unitOfWork, repository, branch, observedAt, queryWork)
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
              launchScope = record.launchScope,
            ),
          branchCorrelation = record.branchCorrelation,
          standaloneStatus = record,
        )
      }.orEmpty()
    val standaloneWorkflowIds = standaloneRecords.mapNotNull { it.workflowId }.toSet()
    return workflowCandidates.filterNot { it.workflowId in standaloneWorkflowIds } + standaloneCandidates
  }

  internal fun toCandidateOrSkip(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    repository: IdeStatusRepositoryResolution.Ok,
    branch: String?,
    read: CandidateRead,
  ): IdeStatusCandidate? =
    try {
      toCandidate(
        item,
        unitOfWork,
        repository,
        branch,
        read.resolved?.work?.firstOrNull { it.item.workflowId == item.workflowId },
      )
    } catch (error: SkillBillRuntimeException) {
      if (error.code != InstallFailureCode.INVALID_IDE_STATUS_SCHEMA) throw error
      read.skipDiagnostics.emit(diagnostics, IDE_STATUS_STATUS_SEAM, item.workflowId, null, error)
      null
    }

  internal fun projectStandaloneLease(
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
          FeatureTaskRuntimeProcessInspection.ForeignHost,
          FeatureTaskRuntimeProcessInspection.PidReused,
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

  internal fun toCandidate(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    repository: IdeStatusRepositoryResolution.Ok,
    branch: String?,
    resolved: ResolvedWorkItem? = null,
  ): IdeStatusCandidate? {
    val repositoryIdentity = repository.identity
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork)
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
          livenessAnchors.lifecycle(item, candidateFamily)
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
      projector.goalBranchCorrelation(item, family, workflowExecution?.branchCorrelation, branch, repository.repoRoot)
    val execution =
      workflowExecution
        ?.takeIf {
          family != IdeStatusWorkflowFamily.FEATURE_VERIFY ||
            branch == null ||
            branchCorrelation == branch ||
            branchCorrelation == "HEAD"
        }
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

  internal fun routeScopeFor(
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

  internal fun isExcludedGoalChild(
    routeScope: FeatureTaskRouteScope?,
    issueKey: String?,
    issueKeysWithGoals: Set<String>,
  ): Boolean = routeScope == FeatureTaskRouteScope.GOAL_CHILD && issueKey?.uppercase() in issueKeysWithGoals

  internal fun boardId(item: WorkBoardItem): String? = item.snapshot.execution?.invocationId ?: item.snapshot.workflowId

  internal fun searchText(item: WorkBoardItem): String =
    listOfNotNull(
      boardId(item),
      item.snapshot.issueKey,
      item.snapshot.title,
      item.snapshot.summary,
      Path.of(item.repositoryRoot).fileName?.toString(),
    ).joinToString("\n").lowercase(Locale.ROOT)

  internal fun matchesFilter(
    item: WorkBoardItem,
    normalizedFilter: String?,
  ): Boolean = normalizedFilter == null || searchText(item).contains(normalizedFilter)

  internal fun decodeRepositoryRoot(identity: String): Path? {
    return try {
      val prefix = FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX
      if (!identity.startsWith(prefix)) return null
      Path.of(identity.removePrefix(prefix)).takeIf { it.isAbsolute }
    } catch (_: InvalidPathException) {
      null
    }
  }
}

internal data class BoardProjectionWindow(val observedAt: Instant, val finishedFrom: Instant)

internal data class ResolvedWorkItem(
  val item: WorkItem,
  val repositoryIdentity: String?,
  val workflowExecution: IdeStatusWorkflowExecution?,
)

internal data class CandidateRead(
  val resolved: ResolvedWorkCandidates? = null,
  val queryWork: IdeStatusQueryWork? = null,
  val skipDiagnostics: AggregateSkipDiagnostics = AggregateSkipDiagnostics(),
)

internal data class ResolvedWorkCandidates(
  val work: List<ResolvedWorkItem>,
  val standalone: List<StandalonePhaseStatusRecord>,
)

internal sealed interface WorkDetailMatch {
  data class Found(val work: ResolvedWorkItem?, val standalone: StandalonePhaseStatusRecord?) : WorkDetailMatch

  data class Problem(val problem: WorkDetailProblem) : WorkDetailMatch
}

@Inject
class WorkBoardReadModelProjection(
  private val projection: WorkBoardItemProjection,
  private val diagnostics: RuntimeDiagnostics,
) : WorkBoardReadModelProjector {
  override fun refresh(
    unitOfWork: UnitOfWork,
    workIds: Set<String>,
    now: Instant,
  ) {
    val finishedFrom = now.minus(WorkBoardWindow.entries.maxOf { it.duration })
    val affected = unitOfWork.workList.boardCandidatesAffectedBy(workIds, finishedFrom, now)
    if (affected.requiresRebuild) {
      diagnostics.warning("Work board read model refresh exceeded the affected-id bound; rebuilding the projection.")
      rebuild(unitOfWork, now)
      return
    }
    affected.nonCandidates.forEach(unitOfWork.workBoardReadModel::delete)
    affected.candidates.forEach { id -> projectAndPersist(unitOfWork, id, now, finishedFrom) }
  }

  override fun rebuild(
    unitOfWork: UnitOfWork,
    now: Instant,
  ) {
    val finishedFrom = now.minus(WorkBoardWindow.entries.maxOf { it.duration })
    unitOfWork.workBoardReadModel.deleteAll()
    var afterKey: String? = null
    while (true) {
      val rows =
        unitOfWork.workList.boardCandidates(
          WorkBoardCandidateQuery(finishedFrom, now, afterKey, WORK_BOARD_CANDIDATE_BATCH_SIZE),
        )
      rows.forEach { candidate -> projectAndPersist(unitOfWork, candidate.id, now, finishedFrom) }
      if (rows.size < WORK_BOARD_CANDIDATE_BATCH_SIZE) break
      afterKey = rows.last().key
    }
    unitOfWork.workBoardReadModel.setComplete(true)
  }

  private fun projectAndPersist(
    unitOfWork: UnitOfWork,
    workId: String,
    now: Instant,
    finishedFrom: Instant,
  ) {
    val persisted =
      try {
        val item =
          projection.projectBoardCandidate(
            unitOfWork,
            workId,
            BoardProjectionWindow(now, finishedFrom),
            mutableListOf(),
            AggregateSkipDiagnostics(),
          )
        if (item == null) {
          false
        } else {
          unitOfWork.workBoardReadModel.upsert(row(workId, item, now))
          true
        }
      } catch (error: SkillBillRuntimeException) {
        error.rethrowIfDatabaseFailure()
        val code = error.failureCodeLabel()?.take(MAX_FAILURE_CODE_CHARS) ?: "unclassified"
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=work_board_read_model_projection work_id=$workId " +
            "value_expected=projected_row value_used=deleted failure_code=$code",
        )
        false
      }
    if (!persisted) unitOfWork.workBoardReadModel.delete(workId)
  }

  private fun row(
    workId: String,
    item: WorkBoardItem,
    now: Instant,
  ): WorkBoardReadModelRow {
    val reprojectAt =
      if (item.snapshot.lifecycleState == IdeStatusLifecycleState.ACTIVE) now else item.freshnessDeadline
    return WorkBoardReadModelRow(
      workId,
      item,
      item.snapshot.updatedAt,
      projection.searchText(item),
      reprojectAt,
      now,
      item.membershipDeadline,
    )
  }

  private companion object {
    const val MAX_FAILURE_CODE_CHARS = 120
  }
}
