package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.goalRepositoryRootFromIdentity
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
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentityFamily
import skillbill.idestatus.model.IdeStatusRepositoryIdentityPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentitySource
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRun
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardInvalidation
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.idestatus.model.WorkBuildPrevious
import skillbill.ports.idestatus.model.WorkBuildState
import skillbill.ports.idestatus.model.WorkBuildView
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailProblem
import skillbill.ports.idestatus.model.WorkDetailProblemCode
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Inject
public class WorkBoardItemProjection(
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val goalRuntimeStateFiles: GoalRuntimeStateFileStore,
  private val buildRunRepository: AgentBuildRunRepository,
) {
  internal fun withBuilds(
    items: List<WorkBoardItem>,
    now: Instant,
  ): List<WorkBoardItem> =
    catchingNonCancellation {
      items.map { item ->
        item.copy(
          build = buildView(item.snapshot.execution?.invocationId ?: item.snapshot.workflowId, item.running, now),
        )
      }
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Work build overlay failed; builds omitted from this board page.",
        error,
      )
      items.map { it.copy(build = null) }
    }

  internal fun withBuild(
    item: WorkDetailItem,
    now: Instant,
  ): WorkDetailItem =
    catchingNonCancellation {
      item.copy(
        build = buildView(item.snapshot.execution?.invocationId ?: item.snapshot.workflowId, item.running, now),
      )
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Work build overlay failed; build omitted from this detail.",
        error,
      )
      item.copy(build = null)
    }

  private fun buildView(
    runId: String?,
    running: Boolean,
    now: Instant,
  ): WorkBuildView? {
    if (runId == null) return null
    val row = buildRunRepository.latestForRun(setOf(runId)) ?: return null
    val history = buildRunRepository.history(row.repositoryIdentity, row.command, row.startedAt)
    return workBuildView(row, history, running, now)
  }

  internal fun projectBoardCandidate(
    unitOfWork: UnitOfWork,
    id: String,
    window: BoardProjectionWindow,
    diagnostics: MutableList<WorkBoardDiagnostic>,
    skipDiagnostics: AggregateSkipDiagnostics,
  ): WorkBoardItem? {
    val (observedAt, finishedFrom) = window
    var found: WorkDetailMatch.Found? = null
    return try {
      found = detailMatch(id, unitOfWork) as? WorkDetailMatch.Found ?: return null
      if (isPlanPhaseOfListedGoal(found, unitOfWork)) return null
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
      if (!error.isInvalidWorkflowStateFailure() || found == null) {
        skipIncompatibleBoardItem(error, diagnostics, skipDiagnostics, this.diagnostics, id)
      } else {
        val resolved = found.work
        val identity = resolved?.repositoryIdentity
        val root = identity?.let(::decodeRepositoryRoot)
        if (resolved == null || identity == null || root == null) throw error
        this.diagnostics.warning(
          "seam=$IDE_STATUS_STATUS_SEAM value_expected=readable_row value_used=blocked_record workflow_id=$id " +
            "failure_code=${error.failureCodeLabel()} error=${error.message.orEmpty()}",
        )
        val snapshot =
          IdeStatusProblemSnapshots.blockedWorkRecord(
            identity,
            observedAt,
            resolved.item,
            error.message.orEmpty(),
          )
        ideStatusValidator.validate(snapshot, "work-board:$id")
        WorkBoardItem(
          snapshot = snapshot,
          repositoryRoot = root.toString(),
          liveSection = workLiveSection(snapshot, null),
          requiresAttention = workRequiresAttention(snapshot),
          displayGroup = workDisplayGroup(snapshot, null),
          displayPriority = workDisplayPriority(snapshot, null),
          observation = unitOfWork.workList.observation(),
          databaseFileObservation = unitOfWork.databaseFileObservation,
        )
      }
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
    val snapshot =
      if (projection.snapshot.problem?.code == IdeStatusProblemCode.INCOMPATIBLE_RECORD) {
        projection.snapshot.copy(
          lifecycleState = IdeStatusLifecycleState.BLOCKED,
          issueKey = candidate.issueKey,
          workflowFamily = candidate.workflowFamily,
          execution = candidate.execution,
          summary =
            "Saved data needs migration. Install a compatible runtime and retry; existing progress is retained.",
        )
      } else {
        projection.snapshot
      }
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
      goalWorktree =
        work.firstOrNull()?.item?.takeIf { it.workflowKind == WorkItemKind.FEATURE_GOAL }
          ?.let { goalWorktreeView(candidate.workflowId, context.unitOfWork) },
    )
  }

  internal fun goalWorktreeView(
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): GoalWorktreeView? {
    val control = unitOfWork.goalRunnerControls.controlState(workflowId)
    val path = control.executionWorktreePath ?: return null
    val state =
      when {
        control.executionWorktreeRemovedAt != null || !goalRuntimeStateFiles.directoryExists(Path.of(path)) ->
          GoalWorktreeState.REMOVED
        control.executionWorktreeRetentionReason != null -> GoalWorktreeState.RETAINED
        else -> GoalWorktreeState.ACTIVE
      }
    val outcome =
      control.executionMainCheckoutOutcome?.let { wire ->
        GoalMainCheckoutOutcome.entries.firstOrNull { it.wireValue == wire }
      }
    return GoalWorktreeView(
      path,
      state,
      control.executionWorktreeRetentionReason,
      control.executionWorktreeRetentionDetail,
      outcome,
      control.executionMainCheckoutDetail.takeIf { outcome != null },
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
    val repositoryCorrelation =
      IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity, repository.monitorIdentity)
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

  private fun isPlanPhaseOfListedGoal(
    found: WorkDetailMatch.Found,
    unitOfWork: UnitOfWork,
  ): Boolean {
    val standalone =
      found.standalone?.takeIf { it.launchScope == IdeStatusLaunchScope.GOAL }
        ?.takeIf { found.work?.item?.workflowKind != WorkItemKind.FEATURE_GOAL }
    val issueKey = standalone?.issueKey?.takeIf(String::isNotBlank) ?: return false
    val exact = standalone.repositoryIdentity
    val primary = decodeRepositoryRoot(exact)?.let(repositoryEnclosingRootPort::primaryRepositoryIdentity)
    if (setOfNotNull(exact, primary).any { identity ->
        unitOfWork.workList
          .goalParentCandidatePage(issueKey, identity, 0, WorkPaginationLimits.MAX_PAGE_SIZE)
          .any { parent -> isListedGoalParent(parent.workflowId, unitOfWork) }
      }
    ) {
      return true
    }
    val root = decodeRepositoryRoot(exact) ?: return false
    return hasRecordedPlanningWorktreeParent(issueKey, root, unitOfWork)
  }

  private fun hasRecordedPlanningWorktreeParent(
    issueKey: String,
    root: Path,
    unitOfWork: UnitOfWork,
  ): Boolean {
    var offset = 0
    do {
      val parents =
        unitOfWork.workList.goalParentCandidatePage(
          issueKey,
          null,
          offset,
          WorkPaginationLimits.MAX_PAGE_SIZE,
        )
      if (parents.any { parent ->
          val ownedPath = unitOfWork.goalRunnerControls.controlState(parent.workflowId).executionWorktreePath
          ownedPath == root.toString() && isListedGoalParent(parent.workflowId, unitOfWork)
        }
      ) {
        return true
      }
      offset += parents.size
    } while (parents.size == WorkPaginationLimits.MAX_PAGE_SIZE)
    return false
  }

  private fun isListedGoalParent(
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): Boolean =
    resolvedWorkItems(canonicalDetailMatches(unitOfWork.workList.findByWorkflowId(workflowId)), unitOfWork)
      .any { it.repositoryIdentity != null }

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

  internal fun decodeRepositoryRoot(identity: String): Path? = goalRepositoryRootFromIdentity(identity)
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

internal object WorkBoardReadModelRefresh {
  fun refresh(
    database: DatabaseSessionFactory,
    projection: WorkBoardItemProjection,
    clock: Clock,
    diagnostics: RuntimeDiagnostics,
  ) {
    try {
      repeat(WORK_BOARD_CANDIDATE_BATCH_SIZE) {
        val prepared =
          database.read { unit ->
            val store = unit.workBoardReadModel
            val invalidation = store.pendingInvalidation() ?: return@read null
            val observation = unit.databaseFileObservation
            if (observation == null) {
              diagnostics.info("Work board projection has no database identity; retaining invalidations.")
              return@read null
            }
            PreparedBoardProjection(
              invalidation,
              store.invalidationRevision(),
              observation,
              prepare(unit, projection, diagnostics, invalidation.workId, clock.instant()),
            )
          }
        if (prepared == null) {
          return
        }
        val publication =
          database.transactionFenced(prepared.databaseFileObservation) { unit ->
            val store = unit.workBoardReadModel
            if (unit.databaseFileObservation != prepared.databaseFileObservation ||
              store.invalidationRevision() != prepared.revision || store.pendingInvalidation() != prepared.invalidation
            ) {
              return@transactionFenced false
            }
            if (prepared.changes.replaceAll) store.deleteAll()
            prepared.changes.deleted.forEach(store::delete)
            prepared.changes.rows.forEach(store::upsert)
            store.acknowledge(prepared.invalidation)
            store.setComplete(store.pendingInvalidation() == null)
            true
          }
        if (publication !is FencedTransactionResult.Committed || !publication.value) {
          diagnostics.info(
            "Work board projection changed during preparation; retaining invalidations for the next read.",
          )
          return
        }
      }
      diagnostics.info("Work board projection batch exhausted; remaining invalidations will resume on the next read.")
    } catch (error: SkillBillRuntimeException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Work board projection refresh failed; using authoritative board reads and retaining invalidations.",
        error,
      )
    }
  }

  private fun prepare(
    unit: UnitOfWork,
    projection: WorkBoardItemProjection,
    diagnostics: RuntimeDiagnostics,
    workId: String,
    now: Instant,
  ): BoardProjectionChanges {
    val finishedFrom = now.minus(WorkBoardWindow.entries.maxOf { it.duration })
    val affected = unit.workList.boardCandidatesAffectedBy(setOf(workId), finishedFrom, now)
    if (affected.requiresRebuild) {
      diagnostics.info("Work board projection exceeded the affected-id bound; preparing a full read snapshot.")
    }
    val ids = if (affected.requiresRebuild) allCandidates(unit, now, finishedFrom) else affected.candidates
    val deleted = affected.nonCandidates.toMutableSet()
    val rows =
      ids.mapNotNull { id ->
        val item =
          try {
            projection.projectBoardCandidate(
              unit,
              id,
              BoardProjectionWindow(now, finishedFrom),
              mutableListOf(),
              AggregateSkipDiagnostics(),
            )
          } catch (error: SkillBillRuntimeException) {
            error.rethrowIfDatabaseFailure()
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=work_board_read_model_projection work_id=$id value_used=deleted " +
                "failure_code=${error.failureCodeLabel()}",
              error,
            )
            null
          }
        if (item == null) {
          deleted.add(id)
          null
        } else {
          row(projection, id, item, now)
        }
      }
    return BoardProjectionChanges(rows, deleted, affected.requiresRebuild)
  }

  private fun allCandidates(
    unit: UnitOfWork,
    now: Instant,
    finishedFrom: Instant,
  ): Set<String> =
    buildSet {
      var afterKey: String? = null
      while (true) {
        val page =
          unit.workList.boardCandidates(
            WorkBoardCandidateQuery(finishedFrom, now, afterKey, WORK_BOARD_CANDIDATE_BATCH_SIZE),
          )
        addAll(page.map { it.id })
        if (page.size < WORK_BOARD_CANDIDATE_BATCH_SIZE) break
        afterKey = page.last().key
      }
    }

  private fun row(
    projection: WorkBoardItemProjection,
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
}

private data class PreparedBoardProjection(
  val invalidation: WorkBoardInvalidation,
  val revision: Long,
  val databaseFileObservation: DatabaseFileObservation,
  val changes: BoardProjectionChanges,
)

private data class BoardProjectionChanges(
  val rows: List<WorkBoardReadModelRow>,
  val deleted: Set<String>,
  val replaceAll: Boolean,
)

private fun workBuildView(
  row: AgentBuildRun,
  history: AgentBuildHistory,
  running: Boolean,
  now: Instant,
): WorkBuildView {
  val endedAt = row.endedAt
  val state =
    when {
      row.outcome == AgentBuildOutcome.UNKNOWN -> WorkBuildState.FINISHED
      endedAt == null -> if (running) WorkBuildState.RUNNING else WorkBuildState.FINISHED
      else -> finishedBuildState(row.outcome)
    }
  val elapsedAsOf = if (endedAt == null && state == WorkBuildState.RUNNING) now else endedAt
  val elapsedMs =
    when {
      endedAt != null -> Duration.between(row.startedAt, endedAt).toMillis()
      state == WorkBuildState.RUNNING -> Duration.between(row.startedAt, now).toMillis().coerceAtLeast(0)
      else -> null
    }
  return WorkBuildView(
    state = state,
    command = row.command,
    shortCommand = row.shortCommand,
    tool = row.tool,
    projectKind = row.projectKind,
    pid = row.pid,
    startedAt = row.startedAt,
    elapsedMs = elapsedMs,
    elapsedAsOf = elapsedAsOf,
    usualMs = history.usualMs,
    exitCode = row.exitCode,
    finishedAt = endedAt,
    previous =
      history.previous?.let { previous ->
        WorkBuildPrevious(
          state = finishedBuildState(previous.outcome),
          durationMs = previous.durationMs,
          finishedAt = previous.endedAt,
        )
      },
  )
}

private fun finishedBuildState(outcome: AgentBuildOutcome?): WorkBuildState =
  when (outcome) {
    AgentBuildOutcome.PASSED -> WorkBuildState.PASSED
    AgentBuildOutcome.FAILED -> WorkBuildState.FAILED
    AgentBuildOutcome.UNKNOWN, null -> WorkBuildState.FINISHED
  }
