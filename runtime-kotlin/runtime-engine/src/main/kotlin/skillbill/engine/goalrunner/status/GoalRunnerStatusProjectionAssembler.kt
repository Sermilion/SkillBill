package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.lifecycle.asWorkerOwnership
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.lifecycle.continuation.runningPhaseAgentId
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.review.core.isAuditGapRound
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.reconcileGoalManifest
import skillbill.engine.goalrunner.manifest.toAcceptedSubtasks
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.ExecutionProgressStall
import skillbill.goalrunner.model.GoalNoChangeState
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerNoChangeStatus
import skillbill.goalrunner.model.GoalRunnerPhaseConclusion
import skillbill.goalrunner.model.GoalRunnerQaResult
import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.goalrunner.model.GoalRunnerReviewPassResult
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.goalrunner.model.GoalRunnerSubtaskValidationEvidence
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.idestatus.model.summary
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.artifact.decodeNoChangePauseFromArtifact
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException
import java.time.Clock
import java.time.Instant

@Inject
class GoalRunnerStatusProjectionAssembler(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val attemptLedgerStore: GoalRunnerAttemptLedgerStore,
  private val database: DatabaseSessionFactory,
  private val gitOperations: WorkflowGitOperations,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val runtimeStatusService: FeatureTaskRuntimeStatusService?,
  private val clock: Clock,
) {
  fun project(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection {
    val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId)
    val manifest =
      reconcileGoalManifest(
        manifest = loadedState.manifest,
        authoritativeOutcomes = outcomeStore.authoritativeOutcomes(loadedState.manifest.issueKey),
        acceptances = acceptances,
        outcomeStore = outcomeStore,
      )
    val currentSubtask =
      manifest.subtasks.firstOrNull { subtask ->
        subtask.id == manifest.currentSubtaskIntent.subtaskId
      }
    return GoalRunnerStatusProjector.project(
      manifest = manifest,
      activeAgent = resolveActiveAgent(currentSubtask, loadedState.parentWorkflowId),
      extras =
        statusProjectionRuntimeInputs(
          loadedState = loadedState,
          request = request,
          manifest = manifest,
          currentSubtask = currentSubtask,
          acceptances = acceptances,
        ),
    )
  }

  fun projectWorkflow(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection? =
    database.read { unit ->
      require(!request.pruneCheckpointRefs)
      require(!request.includeCompletedSubtaskValidation || request.completedSubtaskValidationIds != null)
      require(request.completedSubtaskValidationIds.orEmpty().size <= WorkPaginationLimits.MAX_PAGE_SIZE)
      val subject =
        GoalRunnerStatusSubtaskReader(manifestStore, outcomeStore, unit).read(parentWorkflowId, request)
          ?: return@read null
      val durableRead = GoalRunnerStatusDurableReadTracker(diagnostics)
      val childWorkflowId = subject.current?.workflowId?.takeIf(String::isNotBlank)
      val progress = resolveStatusWorkflowProgress(parentWorkflowId, childWorkflowId, durableRead)
      val noChange =
        subject.completedNoChange?.let {
          GoalRunnerNoChangeStatus(
            GoalNoChangeState.COMPLETED_NO_CHANGE,
            acceptedNoChangeReason(it.workflowId, durableRead) ?: "unknown",
          )
        }
      val inputs =
        buildStatusProjectionRuntimeInputs(
          GoalStatusProjectionRuntimeAssembly(
            loadedState = subject.state,
            request = request,
            planning = subject.planning,
            validation = subject.validationSubtasks.map(::completedSubtaskValidationFor),
            phaseResults = subject.validationSubtasks.map { completedSubtaskPhaseResultsFor(it, durableRead) },
            goalQaResult = goalQaResult(parentWorkflowId, durableRead),
            currentSubtask = subject.current,
            acceptances = subject.acceptances,
            durableRead = durableRead,
            progress = progress,
            ledgerSummary =
              readLedgerSummary(
                request.issueKey,
                durableRead,
                listOfNotNull(subject.current?.id).toSet(),
              ),
            childWorkflowId = childWorkflowId,
            latestWorktreeEdit = latestWorktreeEditSummary(childWorkflowId, durableRead),
            auditAcRetryCount = measuredAuditAcRetryCount(childWorkflowId, durableRead),
            noChangeStatus = noChange,
          ),
        )
      GoalRunnerStatusProjector.project(
        request.issueKey,
        subject.current,
        subject.counts,
        resolveActiveAgent(subject.current, parentWorkflowId),
        inputs,
      )
    }

  fun reconcileSubtaskPage(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
    subtasks: List<DecompositionSubtask>,
  ): List<DecompositionSubtask> =
    database.read { unit ->
      require(subtasks.size <= WorkPaginationLimits.MAX_PAGE_SIZE)
      GoalRunnerStatusSubtaskReader(manifestStore, outcomeStore, unit).reconcile(parentWorkflowId, request, subtasks)
    }

  internal fun resolveExecutionLiveness(
    parentWorkflowId: String,
    currentSubtask: DecompositionSubtask?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness {
    val workflowId =
      currentSubtask?.workflowId?.takeIf(String::isNotBlank)
        ?: return resolveParentExecutionLiveness(parentWorkflowId, durableRead)
    val childLiveness = resolveChildExecutionLiveness(workflowId, durableRead)
    if (childLiveness != ExecutionLiveness.IDLE) {
      return childLiveness
    }
    return resolveParentExecutionLiveness(parentWorkflowId, durableRead)
  }

  internal fun resolvePurgeBlockingLiveness(
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
  ): ExecutionLiveness? {
    val durableRead = GoalRunnerStatusDurableReadTracker(diagnostics)
    val parentLiveness = resolveParentExecutionLiveness(parentWorkflowId, durableRead)
    if (executionLivenessBlocksIdleSafeRefresh(parentLiveness)) {
      return parentLiveness
    }
    return firstBlockingWorkerLiveness(childWorkflowIds, durableRead)
  }

  internal fun resolveWorkerBlockingLiveness(workflowIds: Collection<String>): ExecutionLiveness? =
    firstBlockingWorkerLiveness(workflowIds.sorted(), GoalRunnerStatusDurableReadTracker(diagnostics))

  private fun firstBlockingWorkerLiveness(
    workflowIds: Collection<String>,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness? =
    workflowIds.firstNotNullOfOrNull { workflowId ->
      resolveChildExecutionLiveness(workflowId, durableRead)
        .takeIf { executionLivenessBlocksIdleSafeRefresh(it) }
    }

  private fun resolveStatusWorkflowProgress(
    parentWorkflowId: String,
    childWorkflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): GoalRunnerWorkflowProgress? {
    val childProgress = childWorkflowId?.let(outcomeStore::progress)
    if (childProgress != null) {
      val idleOrCompleted =
        childProgress.workflowStatus == WorkflowStatus.COMPLETED ||
          resolveChildExecutionLiveness(childProgress.workflowId, durableRead) == ExecutionLiveness.IDLE
      if (!idleOrCompleted) {
        return childProgress
      }
    }
    val liveParent =
      liveGoalFinalizationProgress(parentWorkflowId)
    if (childProgress != null && liveParent == null) {
      return childProgress
    }
    return liveParent
  }

  private fun goalFinalizationAgent(parentWorkflowId: String): String? =
    resolveParentExecutionLiveness(parentWorkflowId, GoalRunnerStatusDurableReadTracker(diagnostics))
      .takeIf { it == ExecutionLiveness.LIVE }
      ?.let { liveGoalFinalizationProgress(parentWorkflowId) }
      ?.latestDeclaredProgressEvent
      ?.agentId
      ?.takeIf(String::isNotBlank)

  private fun liveGoalFinalizationProgress(parentWorkflowId: String): GoalRunnerWorkflowProgress? =
    outcomeStore.progress(parentWorkflowId)
      ?.takeIf { it.latestDeclaredProgressEvent?.operationKind == GOAL_FINALIZATION_OPERATION_KIND }
      ?.let { parent ->
        val liveStepId = parent.latestDeclaredProgressEvent?.stepId.orEmpty()
        parent.copy(
          currentStepId = liveStepId,
          latestLivenessSignal = parent.resolvedLivenessSignal(liveStepId),
        )
      }

  private fun statusProjectionRuntimeInputs(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    manifest: DecompositionManifest,
    currentSubtask: DecompositionSubtask?,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): GoalRunnerStatusProjectionRuntimeInputs {
    val durableRead = GoalRunnerStatusDurableReadTracker(diagnostics)
    val childWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
    val progress = resolveStatusWorkflowProgress(loadedState.parentWorkflowId, childWorkflowId, durableRead)
    val ledgerSummary = readLedgerSummary(loadedState.manifest.issueKey, durableRead)
    return buildStatusProjectionRuntimeInputs(
      GoalStatusProjectionRuntimeAssembly(
        loadedState = loadedState,
        request = request,
        planning = durablePlanningStatus(loadedState, manifest, currentSubtask),
        validation =
          if (request.includeCompletedSubtaskValidation) {
            completedSubtaskValidation(manifest, request.completedSubtaskValidationIds)
          } else {
            emptyList()
          },
        phaseResults =
          if (request.includeCompletedSubtaskValidation) {
            completedSubtaskPhaseResults(manifest, request.completedSubtaskValidationIds, durableRead)
          } else {
            emptyList()
          },
        goalQaResult = goalQaResult(loadedState.parentWorkflowId, durableRead),
        currentSubtask = currentSubtask,
        acceptances = acceptances,
        durableRead = durableRead,
        progress = progress,
        ledgerSummary = ledgerSummary,
        childWorkflowId = childWorkflowId,
        latestWorktreeEdit = latestWorktreeEditSummary(childWorkflowId, durableRead),
        auditAcRetryCount = measuredAuditAcRetryCount(childWorkflowId, durableRead),
        noChangeStatus = completedNoChangeStatus(manifest, durableRead),
      ),
    )
  }

  private data class GoalStatusProjectionRuntimeAssembly(
    val loadedState: GoalRunnerManifestState,
    val request: GoalRunnerStatusRequest,
    val planning: GoalPlanningStatusSnapshot?,
    val validation: List<GoalRunnerSubtaskValidationEvidence>,
    val phaseResults: List<GoalRunnerSubtaskPhaseResults>,
    val goalQaResult: GoalRunnerQaResult?,
    val currentSubtask: DecompositionSubtask?,
    val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
    val durableRead: GoalRunnerStatusDurableReadTracker,
    val progress: GoalRunnerWorkflowProgress?,
    val ledgerSummary: GoalRunnerAttemptLedgerSummary?,
    val childWorkflowId: String?,
    val latestWorktreeEdit: WorktreeEditSummary?,
    val auditAcRetryCount: Int?,
    val noChangeStatus: GoalRunnerNoChangeStatus?,
  )

  private fun buildStatusProjectionRuntimeInputs(
    assembly: GoalStatusProjectionRuntimeAssembly,
  ): GoalRunnerStatusProjectionRuntimeInputs {
    val goalCompletedAt = assembly.loadedState.controlState.goalCompletedAt
    val currentStepOverride =
      assembly.progress?.currentStepId ?: derivedChildCurrentStep(assembly.childWorkflowId)
    val executionLiveness =
      resolveExecutionLiveness(
        parentWorkflowId = assembly.loadedState.parentWorkflowId,
        currentSubtask = assembly.currentSubtask,
        durableRead = assembly.durableRead,
      )
    return GoalRunnerStatusProjectionRuntimeInputs(
      executionLiveness = executionLiveness,
      progressStall = progressStall(executionLiveness, assembly),
      planning = assembly.planning,
      currentStepOverride = currentStepOverride,
      currentWorkflowStatus = assembly.progress?.workflowStatus,
      latestLivenessSignal = assembly.progress?.latestLivenessSignal,
      latestObservabilityEvent = assembly.progress?.latestGoalObservabilityEvent?.toObservabilityEvent(),
      requestedDiffStat = requestedDiffStat(assembly.request),
      selectedDiffHunks = requestedSelectedDiffHunks(assembly.request),
      blockedAttemptCount = assembly.ledgerSummary?.blockedAttemptCount ?: 0,
      supervisorKillCount = assembly.ledgerSummary?.supervisorKillCount ?: 0,
      phaseAttemptCounts = assembly.ledgerSummary?.phaseAttemptCounts ?: emptyMap(),
      cumulativeFixIterations = assembly.ledgerSummary?.cumulativeFixIterations ?: emptyMap(),
      reAttemptCauseCounts = assembly.ledgerSummary?.reAttemptCauseCounts ?: emptyMap(),
      findingsInScope = assembly.ledgerSummary?.findingsInScope,
      outOfBandAcceptances = assembly.acceptances.toAcceptedSubtasks(),
      completedSubtaskValidation = assembly.validation,
      completedSubtaskPhaseResults = assembly.phaseResults,
      goalQaResult = assembly.goalQaResult,
      paused = assembly.loadedState.controlState.paused,
      pauseRequested = assembly.loadedState.controlState.pauseRequested,
      pauseReason = assembly.loadedState.controlState.pauseReason,
      pausedAt = assembly.loadedState.controlState.pausedAt,
      stopAfterSubtaskId = assembly.loadedState.controlState.stopAfterSubtaskId,
      activeDurationMs = assembly.loadedState.controlState.activeDurationMs,
      activeDurationAsOf = assembly.loadedState.controlState.activeDurationAsOf,
      subtaskActiveDurationMs = assembly.loadedState.controlState.subtaskActiveDurationMs,
      subtaskActiveDurationAsOf = assembly.loadedState.controlState.subtaskActiveDurationAsOf,
      degradedDurableRead = assembly.durableRead.degraded,
      latestWorktreeEdit = assembly.latestWorktreeEdit,
      auditAcRetryCount = assembly.auditAcRetryCount,
      noChangeStatus = assembly.noChangeStatus,
      goalCompletedAt = goalCompletedAt,
    )
  }

  private fun progressStall(
    liveness: ExecutionLiveness,
    assembly: GoalStatusProjectionRuntimeAssembly,
  ): ExecutionProgressStall? {
    if (liveness != ExecutionLiveness.LIVE) return null
    val signals =
      GoalProgressStallSignals(
        progress = assembly.progress,
        agentActivityStamps =
          agentActivityStamps(
            listOfNotNull(assembly.loadedState.parentWorkflowId, assembly.childWorkflowId),
            assembly.durableRead,
          ),
        latestWorktreeEdit = assembly.latestWorktreeEdit,
      )
    return ExecutionProgressStall.detect(liveness, signals.latestProgressAt(), clock.instant())
  }

  private fun agentActivityStamps(
    workflowIds: List<String>,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): List<Instant> =
    runCatching {
      database.read { unit ->
        workflowIds.distinct().mapNotNull { workflowId -> unit.agentActivityStamps.read(workflowId)?.recordedAt }
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.agent_activity_stamp",
        expected = "agent_activity_stamp",
        used = "absent",
        error = error,
      )
      emptyList()
    }

  private fun readLedgerSummary(
    issueKey: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
    subtaskIds: Set<Int>? = null,
  ): GoalRunnerAttemptLedgerSummary? =
    runCatching {
      if (subtaskIds == null) {
        attemptLedgerStore.readAttemptLedgerSummary(issueKey)
      } else {
        attemptLedgerStore.readAttemptLedgerSummaryForSubtasks(issueKey, subtaskIds)
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.attempt_ledger",
        expected = "ledger_summary",
        used = "degraded",
        error = error,
      )
      null
    }

  private fun completedNoChangeStatus(
    manifest: DecompositionManifest,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): GoalRunnerNoChangeStatus? {
    val noChangeSubtasks =
      manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETED_NO_CHANGE }
    val finished =
      noChangeSubtasks.isNotEmpty() &&
        manifest.subtasks.all {
          it.status.decompositionStatus() in
            setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED, DecompositionStatus.COMPLETED_NO_CHANGE)
        } &&
        manifest.subtasks.none { !it.commitSha.isNullOrBlank() }
    if (!finished) return null
    val reason = acceptedNoChangeReason(noChangeSubtasks.first().workflowId, durableRead) ?: "unknown"
    return GoalRunnerNoChangeStatus(state = GoalNoChangeState.COMPLETED_NO_CHANGE, reason = reason)
  }

  private fun acceptedNoChangeReason(
    workflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): String? {
    val id = workflowId?.takeIf(String::isNotBlank) ?: return null
    val pause: FeatureTaskRuntimeNoChangePause? =
      runCatching {
        database.readIfPresent { unitOfWork ->
          unitOfWork.workflowStates.readFeatureTaskArtifacts(
            id,
            setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE),
          )?.let { artifacts ->
            decodeNoChangePauseFromArtifact(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.value(artifacts),
            )
          }
        }
      }.getOrElse { error ->
        durableRead.recordDegradedRead(
          seam = "goal-status.no_change_pause",
          expected = "no_change_reason",
          used = "unknown",
          error = error,
        )
        null
      }
    return pause?.reason?.wireValue
  }

  private fun latestWorktreeEditSummary(
    childWorkflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ) = childWorkflowId?.let { workflowId ->
    runCatching {
      database.readIfPresent { unitOfWork ->
        unitOfWork.worktreeEditJournal.latestTick(workflowId)
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.worktree_edit_journal",
        expected = "latest_tick",
        used = "omitted",
        error = error,
      )
      null
    }?.summary(WorktreeEditJournalPayloadKeys.PATH_SAMPLE_LIMIT)
  }

  private fun measuredAuditAcRetryCount(
    childWorkflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ) = childWorkflowId?.let { workflowId ->
    runCatching {
      var count = 0
      if (phaseQuery.visitPhaseLedger(workflowId) { if (isAuditGapRound(it)) count++ }) count else null
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.audit_ac_retry_count",
        expected = "ledger_count",
        used = "omitted",
        error = error,
      )
      null
    }?.takeIf { count -> count > 0 }
  }

  private fun completedSubtaskValidation(
    manifest: DecompositionManifest,
    selectedIds: Set<Int>?,
  ): List<GoalRunnerSubtaskValidationEvidence> =
    manifest.subtasks
      .filter { selectedIds == null || it.id in selectedIds }
      .filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETE }
      .map { subtask -> completedSubtaskValidationFor(subtask) }

  private fun completedSubtaskValidationFor(subtask: DecompositionSubtask): GoalRunnerSubtaskValidationEvidence {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val record =
      workflowId?.let { phaseQuery.loadPhaseRecords(it) }
        ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    val envelope =
      record?.outputArtifact
        ?.let(JsonCodec::parseObjectOrNull)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val passed = true.takeIf { envelope?.get(SharedPayloadKeys.STATUS) == WorkflowStepStatus.COMPLETED.wireValue }
    return GoalRunnerSubtaskValidationEvidence(
      subtaskId = subtask.id,
      validationPassed = passed,
      integrityProblem =
        when {
          envelope == null -> "Completed subtask has no boolean validation result."
          passed == null || record.status != WorkflowStepStatus.COMPLETED -> "Validation phase is not completed."
          else -> null
        },
    )
  }

  private fun completedSubtaskPhaseResults(
    manifest: DecompositionManifest,
    selectedIds: Set<Int>?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): List<GoalRunnerSubtaskPhaseResults> =
    manifest.subtasks
      .filter { selectedIds == null || it.id in selectedIds }
      .filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETE }
      .map { subtask -> completedSubtaskPhaseResultsFor(subtask, durableRead) }

  private fun completedSubtaskPhaseResultsFor(
    subtask: DecompositionSubtask,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): GoalRunnerSubtaskPhaseResults {
    val workflowId =
      subtask.workflowId?.takeIf(String::isNotBlank)
        ?: return GoalRunnerSubtaskPhaseResults(subtask.id)
    return runCatching {
      val artifacts =
        phaseQuery.loadPhaseResultArtifacts(workflowId) ?: return GoalRunnerSubtaskPhaseResults(subtask.id)
      GoalRunnerSubtaskPhaseResults(
        subtaskId = subtask.id,
        reviewPasses =
          GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts)?.passResults.orEmpty().map { pass ->
            GoalRunnerReviewPassResult(
              passNumber = pass.passNumber,
              verdict = pass.verdict.wireValue,
              unresolvedFindingCount = pass.unresolvedFindingCount,
              findings = pass.findings.map { GoalRunnerReviewFinding(it.severity, it.label, it.text) },
            )
          },
        audit = artifacts.phaseRecords()[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT]?.toConclusion(),
      )
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.subtask_phase_results",
        expected = "review_and_audit_results",
        used = "omitted",
        error = error,
      )
      GoalRunnerSubtaskPhaseResults(subtask.id)
    }
  }

  private fun FeatureTaskRuntimePhaseRecord.toConclusion(): GoalRunnerPhaseConclusion {
    val envelope =
      outputArtifact
        ?.let(JsonCodec::parseObjectOrNull)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val produced = envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)?.let(JsonCodec::anyToStringAnyMap)
    return GoalRunnerPhaseConclusion(
      verdict = (envelope?.get(SharedPayloadKeys.VERDICT) as? String)?.takeIf(String::isNotBlank),
      summary = (envelope?.get(SharedPayloadKeys.SUMMARY) as? String)?.trim()?.takeIf(String::isNotBlank),
      detail = (produced?.get(SharedPayloadKeys.VALUE) as? String)?.trim()?.takeIf(String::isNotBlank),
      attemptCount = attemptCount,
    )
  }

  private fun goalQaResult(
    parentWorkflowId: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): GoalRunnerQaResult? =
    runCatching {
      outcomeStore.recordedGoalQaOutcome(parentWorkflowId)?.let {
        GoalRunnerQaResult(it.outcome.wireValue, it.reason, it.qaFixAttempts, Instant.parse(it.recordedAt))
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.goal_qa_result",
        expected = "goal_qa_outcome",
        used = "omitted",
        error = error,
      )
      null
    }

  private fun durablePlanningStatus(
    loadedState: GoalRunnerManifestState,
    manifest: DecompositionManifest,
    currentSubtask: DecompositionSubtask?,
  ) = currentSubtask?.takeIf { subtask ->
    subtask.status.decompositionStatus() == DecompositionStatus.BLOCKED &&
      subtask.lastResumableStep in setOf("preplan", "plan")
  }.let { planningBlock ->
    manifestStore.planningStatus(
      loadedState.parentWorkflowId,
      manifest.subtasks.filter { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }.map { it.id },
      planningBlock?.id,
      planningBlock?.blockedReason,
    )
  }

  private fun derivedChildCurrentStep(childWorkflowId: String?): String? {
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return null
    val statusService = runtimeStatusService ?: return null
    return try {
      statusService.status(
        FeatureTaskRuntimeStatusRequest(
          workflowId = workflowId,
        ),
      )?.currentPhaseId?.takeIf(String::isNotBlank)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Goal status omitted derived child phase for workflow '$workflowId': " +
          "the child's durable status could not be read.",
        error,
      )
      null
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Goal status omitted derived child phase for workflow '$workflowId': " +
          "the child's durable status could not be read.",
        error,
      )
      null
    }
  }

  private fun resolveChildExecutionLiveness(
    workflowId: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness =
    runCatching {
      if (phaseQuery.existingWorkflowMode(workflowId) != FeatureTaskWorkflowMode.RUNTIME) {
        ExecutionLiveness.UNKNOWN
      } else {
        val ownership = phaseQuery.workerOwnership(workflowId)
        if (ownership == null) {
          ExecutionLiveness.IDLE
        } else {
          livenessOfLeaseOwner(ownership, durableRead)
        }
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.child_execution_liveness",
        expected = "live_or_idle",
        used = ExecutionLiveness.UNKNOWN.wireValue,
        error = error,
      )
      ExecutionLiveness.UNKNOWN
    }

  private fun resolveParentExecutionLiveness(
    parentWorkflowId: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness =
    runCatching {
      val lease =
        manifestStore.executionLease(parentWorkflowId)
          ?: return@runCatching ExecutionLiveness.IDLE
      livenessOfLeaseOwner(lease.asWorkerOwnership(parentWorkflowId), durableRead)
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.parent_execution_liveness",
        expected = "live_or_idle",
        used = ExecutionLiveness.UNKNOWN.wireValue,
        error = error,
      )
      ExecutionLiveness.UNKNOWN
    }

  private fun livenessOfLeaseOwner(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness =
    when (val inspection = workerSupervisor.inspect(ownership)) {
      is FeatureTaskRuntimeProcessInspection.Unsupported -> {
        durableRead.recordDegradedRead(
          seam = "goal-status.execution_liveness",
          expected = "verified_process_owner",
          used = ExecutionLiveness.UNKNOWN.wireValue,
        )
        ExecutionLiveness.UNKNOWN
      }
      else ->
        executionLivenessFromWorkerInspection(inspection)
    }

  private fun resolveActiveAgent(
    currentSubtask: DecompositionSubtask?,
    parentWorkflowId: String,
  ): String? {
    if (currentSubtask == null) return goalFinalizationAgent(parentWorkflowId)
    val workflowId = currentSubtask.workflowId?.takeIf(String::isNotBlank)
    if (workflowId != null &&
      phaseQuery.existingWorkflowMode(workflowId) == FeatureTaskWorkflowMode.RUNTIME
    ) {
      (
        runningPhaseAgentId(phaseQuery, workflowId)
          ?: agentAttributionFromPhaseState(phaseQuery, workflowId, includeParticipatingAgents = false)
            .finalizingAgentId
      )?.takeIf(String::isNotBlank)
        ?.let { return it }
    }
    return currentSubtask.finalizingAgentId?.takeIf(String::isNotBlank)
      ?: currentSubtask.participatingAgentIds.firstOrNull()?.takeIf(String::isNotBlank)
  }

  private fun requestedDiffStat(request: GoalRunnerStatusRequest) =
    if (request.includeDiffStat) {
      request.repoRoot
        ?.let(gitOperations::worktreeActivity)
        ?.takeIf { result -> result.status == WorkflowGitOperationStatus.OK }
        ?.diffStat
    } else {
      null
    }

  private fun requestedSelectedDiffHunks(request: GoalRunnerStatusRequest) =
    if (request.selectedDiffHunkPaths.isNotEmpty()) {
      request.repoRoot
        ?.let { root ->
          gitOperations.selectedDiffHunks(
            root,
            WorkflowSelectedDiffHunksRequest(
              paths = request.selectedDiffHunkPaths,
              maxHunks = request.selectedDiffMaxHunks,
              maxLines = request.selectedDiffMaxLines,
              maxBytes = request.selectedDiffMaxBytes,
            ),
          )
        }
        ?.takeIf { result -> result.status == WorkflowGitOperationStatus.OK }
        ?.selectedDiffHunks
    } else {
      null
    }
}
