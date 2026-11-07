package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneCompletedSubtaskCheckpointRefs
import skillbill.engine.featuretask.lifecycle.subtask.parseGitPorcelainPaths
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.assessment.GoalAssessmentStageResult
import skillbill.engine.goalrunner.assessment.GoalRunnerAssessmentGate
import skillbill.engine.goalrunner.execution.support.GoalPullRequestFactSection
import skillbill.engine.goalrunner.execution.support.GoalSpecScratchCleanup
import skillbill.engine.goalrunner.execution.support.goalRunnerRepositoryContentIdentity
import skillbill.engine.goalrunner.execution.support.isFeatureSpecPath
import skillbill.engine.goalrunner.execution.support.pullRequestDescriptionInstructions
import skillbill.engine.goalrunner.execution.support.pullRequestFacts
import skillbill.engine.goalrunner.execution.support.pullRequestRequestOf
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.findings.resolveUnaddressedFindingsLedger
import skillbill.engine.goalrunner.history.goalHistoryFingerprint
import skillbill.engine.goalrunner.model.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.monitoring.GoalRunnerCiMonitor
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityExecutionRead
import skillbill.engine.goalrunner.qa.GoalQaGateResult
import skillbill.engine.goalrunner.qa.GoalQaSkipReason
import skillbill.engine.goalrunner.qa.GoalRunnerQaGate
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySignal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.model.GoalPullRequestStatus
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path

@Inject
public class GoalRunnerFinalization(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val specScratchCleanup: GoalSpecScratchCleanup,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
  private val noChangePauses: FeatureTaskRuntimePhaseRecorder,
  private val ciMonitor: GoalRunnerCiMonitor,
  private val qaGate: GoalRunnerQaGate,
  private val assessmentGate: GoalRunnerAssessmentGate,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val finalHistoryWriter: GoalRunnerFinalHistoryWriter,
  private val publication: GoalRunnerPullRequests,
) {
  internal fun requireCompletedChildrenHaveDrafts(state: GoalRunnerManifestState) {
    state.manifest.subtasks
      .filter {
        it.status.decompositionStatus() in
          setOf(
            DecompositionStatus.COMPLETE,
            DecompositionStatus.COMPLETED_NO_CHANGE,
          )
      }
      .forEach { subtask ->
        val childWorkflowId =
          subtask.workflowId?.takeIf(String::isNotBlank)
            ?: outcomeStore.restoredGoalChildWorkflowId(state.parentWorkflowId, subtask.id)
            ?: throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
              "Completed subtask ${subtask.id} has no child identity to verify before goal finalization.",
            )
        if (!outcomeStore.hasRequiredGoalChildHistoryDraft(state.parentWorkflowId, subtask.id, childWorkflowId)) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
            "Goal child $childWorkflowId cannot reach finalization without its accepted history draft or skip.",
          )
        }
      }
  }

  internal fun finalizePublication(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport {
    requireCompletedChildrenHaveDrafts(finalState)
    val settled =
      noChangeFinalization(finalState.manifest, request, attempted)?.also { assessmentGate.skipNoChanges(finalState) }
        ?: publication.publishedGoalResult(finalState, request, attempted)
    settled?.let { return it }
    when (val assessment = assessmentGate.run(finalState, request)) {
      is GoalAssessmentStageResult.ProviderPaused -> {
        pauseBoundary.pauseNow(finalState.parentWorkflowId, assessment.reason)
        return stopped(
          StoppedReportArgs(
            issueKey = finalState.manifest.issueKey,
            attempted = attempted,
            subtaskId = 0,
            reason = GoalRunnerStopReason.PAUSED,
            blockedReason = assessment.reason,
            workflowId = finalState.parentWorkflowId,
            lastResumableStep = assessment.lastResumableStep,
          ),
        )
      }
      is GoalAssessmentStageResult.Completed -> {
        GoalAssessmentLoopRules.publicationBlockReason(assessment.record)?.let { reason ->
          return stopped(
            StoppedReportArgs(
              issueKey = finalState.manifest.issueKey,
              attempted = attempted,
              subtaskId = 0,
              reason = GoalRunnerStopReason.BLOCKED,
              blockedReason =
                reason +
                  " Resume explicitly with skill-bill goal resume ${request.issueKey} before running the goal again.",
              workflowId = finalState.parentWorkflowId,
              lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT,
            ),
          )
        }
      }
    }
    return finalizeValidatedPublication(finalState, request, attempted)
  }

  private fun finalizeValidatedPublication(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport {
    val prepared =
      try {
        readyValidatedPublication(finalState, request, attempted)
      } catch (failure: SkillBillRuntimeException) {
        return writeHistoryStopped(
          finalState,
          attempted,
          failure.message ?: "Final goal state could not be saved.",
          failure.failureCodeLabel(),
        )
      }
    return when (prepared) {
      is ValidatedPublicationPrep.Ready ->
        commitAndPublish(finalState, request, attempted, prepared.invocationId, prepared.qaResult)
      is ValidatedPublicationPrep.Stopped -> prepared.report
    }
  }

  private fun readyValidatedPublication(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): ValidatedPublicationPrep {
    val qaResult = qaGate.run(finalState, request)
    if (qaResult is GoalQaGateResult.Paused) {
      return ValidatedPublicationPrep.Stopped(
        pauseForProviderLimit(finalState, attempted, qaResult.reason, qaResult.stepId),
      )
    }
    if (qaResult is GoalQaGateResult.Warned) warnQa(request, qaResult.reason)
    val invocationId =
      currentQualityExecution(outcomeStore, finalState.parentWorkflowId)?.invocationId
        ?: return ValidatedPublicationPrep.Stopped(
          writeHistoryStopped(
            finalState,
            attempted,
            "Final quality invocation is missing before boundary history writing.",
          ),
        )
    val historyStop = writeGoalHistory(finalState, request, attempted, invocationId)
    return if (historyStop != null) {
      ValidatedPublicationPrep.Stopped(historyStop)
    } else {
      ValidatedPublicationPrep.Ready(invocationId, qaResult)
    }
  }

  private fun commitAndPublish(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    qualityInvocationId: String,
    qaResult: GoalQaGateResult,
  ): GoalRunnerRunReport {
    if (!qualityInvocationIsCurrent(finalState.parentWorkflowId, qualityInvocationId)) {
      return writeHistoryStopped(
        finalState,
        attempted,
        "Final quality invocation changed before commit and PR publication.",
      )
    }
    commitAllRemainingWorktree(finalState.manifest, request)?.let { reason ->
      return stopped(
        StoppedReportArgs(
          issueKey = finalState.manifest.issueKey,
          attempted = attempted,
          subtaskId = finalState.manifest.subtasks.lastOrNull()?.id ?: 0,
          reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
          blockedReason = reason,
          workflowId = null,
          lastResumableStep = "commit_push",
        ),
      )
    }
    if (!qualityInvocationIsCurrent(finalState.parentWorkflowId, qualityInvocationId)) {
      return writeHistoryStopped(finalState, attempted, "Final quality invocation changed before PR creation.")
    }
    val result = publication.openPullRequest(finalState, request, qaResult)
    return when (result) {
      is GoalPullRequestOpenOutcome.ProviderPaused ->
        pauseForProviderLimit(
          finalState,
          attempted,
          result.reason,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        )
      is GoalPullRequestOpenOutcome.Ready ->
        finishPublishedPullRequest(finalState, request, attempted, result.result)
    }
  }

  private fun finishPublishedPullRequest(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    pullRequest: GoalPullRequestResult,
  ): GoalRunnerRunReport =
    when (pullRequest) {
      is GoalPullRequestResult.Opened ->
        completeMonitoredGoal(finalState, request, attempted, pullRequest.url, GoalPullRequestStatus.OPENED)
      is GoalPullRequestResult.Existing ->
        completeMonitoredGoal(
          finalState,
          request,
          attempted,
          pullRequest.url,
          GoalPullRequestStatus.EXISTING,
        )
      is GoalPullRequestResult.Failed ->
        stopped(
          StoppedReportArgs(
            issueKey = finalState.manifest.issueKey,
            attempted = attempted,
            subtaskId =
              finalState.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 }
                ?: finalState.manifest.subtasks.last().id,
            reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
            blockedReason = pullRequest.reason,
            workflowId = null,
            lastResumableStep = "pr_description",
          ),
        )
    }

  private fun pauseForProviderLimit(
    state: GoalRunnerManifestState,
    attempted: List<Int>,
    reason: String,
    lastResumableStep: String,
    reportReason: String = reason,
  ): GoalRunnerRunReport {
    pauseBoundary.pauseNow(state.parentWorkflowId, reason)
    return stopped(
      StoppedReportArgs(
        issueKey = state.manifest.issueKey,
        attempted = attempted,
        subtaskId = 0,
        reason = GoalRunnerStopReason.PAUSED,
        blockedReason = reportReason,
        workflowId = state.parentWorkflowId,
        lastResumableStep = lastResumableStep,
      ),
    )
  }

  private fun writeGoalHistory(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    expectedInvocationId: String,
  ): GoalRunnerRunReport? {
    val quality =
      currentQualityExecution(outcomeStore, state.parentWorkflowId)
        ?.takeIf { it.invocationId == expectedInvocationId }
        ?: return writeHistoryStopped(
          state,
          attempted,
          "Final quality invocation changed before boundary history writing.",
        )
    if (quality.status !in
      setOf(
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    ) {
      return writeHistoryStopped(
        state,
        attempted,
        "Final quality invocation is no longer settled for boundary history " +
          "writing.",
      )
    }
    return writeEligibleGoalHistory(state, request, attempted, quality.invocationId)
  }

  private fun writeEligibleGoalHistory(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    invocationId: String,
  ): GoalRunnerRunReport? {
    val writerInput = requiredGoalHistoryInput(state)
    val currentTree = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
    if (currentTree !is WorkflowGitOperationResult.Ok || currentTree.value.isBlank()) {
      return writeHistoryStopped(
        state,
        attempted,
        "Could not read repository contents before boundary history writing.",
      )
    }
    val preWriterContentFingerprint = currentTree.value.trim()
    val accepted = outcomeStore.goalHistoryCompletion(state.parentWorkflowId)
    if (accepted != null &&
      accepted.inputFingerprint == goalHistoryFingerprint(writerInput, accepted.preWriterContentFingerprint) &&
      preWriterContentFingerprint == accepted.postWriterContentFingerprint
    ) {
      return null
    }

    return runHistoryWriter(
      state,
      request,
      attempted,
      GoalHistoryWriteInput(writerInput, invocationId, preWriterContentFingerprint),
    )
  }

  private fun requiredGoalHistoryInput(state: GoalRunnerManifestState): GoalHistoryWriterInput {
    val input =
      goalHistoryWriterInput(state) ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Required child history records are missing or conflict with the parent manifest.",
      )
    val repairs =
      outcomeStore.goalFinalRepairContext(state.parentWorkflowId) ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Final QA repair context was not durably recorded.",
      )
    return input.copy(repairContext = repairs)
  }

  private fun runHistoryWriter(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    write: GoalHistoryWriteInput,
  ): GoalRunnerRunReport? {
    val (writerInput, invocationId, preWriterContentFingerprint) = write
    val branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() }
    val result =
      finalHistoryWriter.run(
        PhaseRunRequest(
          definitionId = SkeletonDefinition.GOAL_HISTORY.id,
          repoRoot = request.repoRoot,
          invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
          intake = request.issueKey,
          timeout = request.timeout,
          agentAddonSelection = request.agentAddonSelection,
          modelAssignment = request.modelAssignment,
          goalHistoryWriterInput = writerInput,
        ),
        state.parentWorkflowId,
        branch,
      )
    if (result is PhaseRunResult.Blocked &&
      FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)
    ) {
      return pauseForProviderLimit(state, attempted, result.reason, result.stepId)
    }
    val output = (result as? PhaseRunResult.Completed)?.acceptedOutput?.takeIf(String::isNotBlank)
    val afterTree = output?.let { goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot) }
    val postWriterContentFingerprint =
      (afterTree as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotBlank)
    if (output != null &&
      postWriterContentFingerprint != null &&
      outcomeStore.saveGoalHistoryCompletion(
        state.parentWorkflowId,
        invocationId,
        GoalHistoryCompletion(
          inputFingerprint = goalHistoryFingerprint(writerInput, preWriterContentFingerprint),
          preWriterContentFingerprint = preWriterContentFingerprint,
          postWriterContentFingerprint = postWriterContentFingerprint,
          acceptedOutput = output,
        ),
      )
    ) {
      return null
    }
    return writeHistoryStopped(
      state,
      attempted,
      when {
        output == null -> "The boundary history writer did not complete with accepted output."
        postWriterContentFingerprint == null ->
          "Could not read repository contents after boundary history writing."
        else -> "Could not durably record boundary history writer completion."
      },
    )
  }

  private fun qualityInvocationIsCurrent(
    parentWorkflowId: String,
    expectedInvocationId: String,
  ): Boolean =
    currentQualityExecution(outcomeStore, parentWorkflowId)?.let { execution ->
      execution.invocationId == expectedInvocationId && execution.status in
        setOf(
          GoalQualityExecutionStatus.SETTLED_PASSED,
          GoalQualityExecutionStatus.SETTLED_SKIPPED,
          GoalQualityExecutionStatus.SETTLED_WARNED,
        )
    } == true

  private fun goalHistoryWriterInput(state: GoalRunnerManifestState): GoalHistoryWriterInput? {
    val parentId = state.parentWorkflowId
    val expectedSelection = state.manifest.subtasks.map { it.id to it.workflowId }
    val ordered = outcomeStore.goalHistoryWriterEntries(parentId, expectedSelection)
    val repairContext = outcomeStore.goalFinalRepairContext(parentId) ?: return null
    return GoalHistoryWriterInput(parentId, ordered, repairContext)
  }

  private fun writeHistoryStopped(
    state: GoalRunnerManifestState,
    attempted: List<Int>,
    reason: String,
    failureCode: String? = null,
  ): GoalRunnerRunReport =
    stopped(
      StoppedReportArgs(
        issueKey = state.manifest.issueKey,
        attempted = attempted,
        subtaskId = state.manifest.subtasks.lastOrNull()?.id ?: 0,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = state.parentWorkflowId,
        lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        failureCode = failureCode,
      ),
    )

  private fun warnQa(
    request: GoalRunnerRunRequest,
    reason: String,
  ) {
    val warning = "Warning: goal QA finished with a warning; continuing to commit_push. $reason"
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, warning)
    request.outputSink.write(AgentRunOutputStream.STDERR, "$warning\n")
  }

  private fun completeMonitoredGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    pullRequestUrl: String,
    pullRequestStatus: GoalPullRequestStatus,
  ): GoalRunnerRunReport =
    when (val result = ciMonitor.monitor(state, request)) {
      is PhaseRunResult.Completed -> {
        deleteGoalSpecScratchOnSuccess(state.manifest, request)
        completed(
          state.manifest,
          attempted,
          pullRequestUrl,
          pullRequestStatus,
          publication.resolveFindingsLedger(state.manifest.issueKey),
        ).copy(monitoringSummary = acceptedMonitoringSummary(result.completedOutputs))
      }
      is PhaseRunResult.Blocked -> {
        val summary = acceptedMonitoringSummary(result.completedOutputs)
        val blockedReason =
          listOfNotNull(result.reason.takeIf(String::isNotBlank), summary?.takeIf(String::isNotBlank))
            .joinToString("\n\n")
        if (FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)) {
          pauseForProviderLimit(state, attempted, result.reason, result.stepId, blockedReason)
        } else {
          stopped(
            StoppedReportArgs(
              issueKey = state.manifest.issueKey,
              attempted = attempted,
              subtaskId = 0,
              reason = GoalRunnerStopReason.BLOCKED,
              blockedReason = blockedReason,
              workflowId = state.parentWorkflowId,
              lastResumableStep = result.stepId,
            ),
          )
        }
      }
    }

  private fun acceptedMonitoringSummary(completedOutputs: List<String>): String? =
    completedOutputs
      .map { artifact ->
        NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(artifact, "goal monitor completed output")
      }
      .mapNotNull { output ->
        val label =
          when (output.phaseId) {
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH -> "Published change"
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR -> "CI result"
            else -> return@mapNotNull null
          }
        output.output.value.takeIf(String::isNotBlank)?.let {
          val resultLabel = if (it.startsWith("No checks were reported")) "Monitoring result" else label
          "$resultLabel: $it"
        }
      }
      .takeIf { it.isNotEmpty() }
      ?.joinToString("\n")

  private fun noChangeFinalization(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport? {
    val noChangeSubtasks =
      manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETED_NO_CHANGE }
    if (noChangeSubtasks.isEmpty() || manifest.subtasks.any { !it.commitSha.isNullOrBlank() }) return null
    deleteGoalSpecScratchOnSuccess(manifest, request)
    val firstSubtask = noChangeSubtasks.first()
    val pause =
      firstSubtask.workflowId
        ?.takeIf(String::isNotBlank)
        ?.let(noChangePauses::loadNoChangePause)
    if (pause == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Goal ${manifest.issueKey} finished no-change, but the pause for subtask ${firstSubtask.id} could not be " +
          "read; no_change_reason is omitted from telemetry.",
      )
    }
    return GoalRunnerRunReport.CompletedNoChange(
      issueKey = manifest.issueKey,
      attemptedSubtasks = attempted,
      noChangeReason = pause?.reason?.wireValue,
      subtaskIds = noChangeSubtasks.map(DecompositionSubtask::id),
    )
  }

  internal fun deleteCompletedSubtaskSpecScratch(
    manifest: DecompositionManifest,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ) = specScratchCleanup.deleteCompletedSubtaskSpec(manifest, subtaskId, request.repoRoot)

  internal fun pruneCompletedCheckpointRefs(
    completed: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Complete,
    request: GoalRunnerRunRequest,
    observability: GoalRunnerObservabilityEmitter,
  ) {
    pruneCompletedSubtaskCheckpointRefs(
      gitOperations = gitOperations,
      repoRoot = request.repoRoot,
      request =
        FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = completed.manifest.issueKey,
          subtaskId = subtaskId.toString(),
          manifestCommitSha = reconciled.commitSha,
          featureBranch = completed.manifest.featureBranch,
        ),
      record = { message ->
        observability.record(
          GoalRunnerObservabilitySubject(reconciled.workflowId, completed.manifest.issueKey, subtaskId),
          GoalRunnerObservabilitySignal(
            workflowPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
            livenessClass = GoalRunnerObservabilityLivenessClass.DEGRADATION,
            activitySummary = message,
          ),
        )
      },
    )
  }

  private fun commitAllRemainingWorktree(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
  ): String? {
    if (manifest.specSource == SpecSource.LINEAR) {
      deleteGoalSpecScratchOnSuccess(manifest, request)
    }
    warnUnfinishedGitOperation(request.repoRoot)
    val before = gitOperations.worktreeStatus(request.repoRoot)
    if (before !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization could not verify worktree cleanliness: ${before.error}"
    }
    val dirtyPaths = parseGitPorcelainPaths(before.value.orEmpty())
    val implementationPaths = dirtyPaths.filterNot(::isFeatureSpecPath)
    val featureBranch = manifest.featureBranch.orEmpty().trim()
    if (implementationPaths.isEmpty()) {
      return pushUnpushedFeatureBranchIfNeeded(featureBranch, request.repoRoot)
    }
    return commitAndPushDirtyWorktree(manifest, request, featureBranch, implementationPaths)
  }

  private fun warnUnfinishedGitOperation(repoRoot: Path) {
    val inProgress = gitOperations.inProgressOperation(repoRoot)
    val operation = (inProgress as? WorkflowGitOperationResult.Ok)?.value?.trim().orEmpty()
    if (operation.isBlank()) return
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Goal finalization found an unfinished git $operation in '$repoRoot' and commits it with the remaining " +
        "worktree changes.",
    )
  }

  private fun commitAndPushDirtyWorktree(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
    featureBranch: String,
    implementationPaths: List<String>,
  ): String? {
    if (featureBranch.isBlank()) {
      return "Goal finalization commit-all requires a feature branch."
    }
    val branchError = requireFeatureBranchForFinalize(featureBranch, request.repoRoot)
    return branchError ?: stageCommitAndPushAll(manifest, request, featureBranch, implementationPaths)
  }

  private fun stageCommitAndPushAll(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
    featureBranch: String,
    implementationPaths: List<String>,
  ): String? {
    val staged = gitOperations.stagePaths(request.repoRoot, implementationPaths)
    if (staged !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization commit-all could not stage remaining worktree changes: ${staged.error}"
    }
    val message = "chore(${manifest.issueKey}): goal finalization commit-all on '$featureBranch'"
    return when (val commit = gitOperations.createCommit(request.repoRoot, message)) {
      is WorkflowGitCommitResult.Failed ->
        "Goal finalization commit-all could not commit remaining worktree changes: ${commit.error}"
      WorkflowGitCommitResult.NothingToCommit ->
        pushUnpushedFeatureBranchIfNeeded(featureBranch, request.repoRoot)
      is WorkflowGitCommitResult.Committed ->
        if (commit.commitSha.isBlank()) {
          pushUnpushedFeatureBranchIfNeeded(featureBranch, request.repoRoot)
        } else {
          pushCommittedFeatureBranch(request, featureBranch)
        }
    }
  }

  private fun pushCommittedFeatureBranch(
    request: GoalRunnerRunRequest,
    featureBranch: String,
  ): String? {
    val pushed = gitOperations.pushOwnedBranch(request.repoRoot, featureBranch)
    return if (pushed is WorkflowGitOperationResult.Ok) {
      null
    } else {
      "Goal finalization commit-all committed remaining changes but could not push " +
        "branch '$featureBranch': ${pushed.error}"
    }
  }

  private fun pushUnpushedFeatureBranchIfNeeded(
    featureBranch: String,
    repoRoot: Path,
  ): String? {
    if (featureBranch.isBlank()) return null
    val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, featureBranch)
    if (unpushed !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization could not determine whether '$featureBranch' has unpushed commits: " +
        unpushed.error
    }
    if (unpushed.value.trim() != "true") return null
    return requireFeatureBranchForFinalize(featureBranch, repoRoot)
      ?: gitOperations.pushOwnedBranch(repoRoot, featureBranch)
        .takeIf { it !is WorkflowGitOperationResult.Ok }
        ?.let { "Goal finalization found unpushed commits on '$featureBranch' but could not push: ${it.error}" }
  }

  private fun requireFeatureBranchForFinalize(
    featureBranch: String,
    repoRoot: Path,
  ): String? {
    protectedBranchName(featureBranch)?.let { protected ->
      return "Goal finalization commit-all refuses protected branch '$protected'."
    }
    val current = gitOperations.currentBranch(repoRoot)
    if (current !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization could not read the current branch: ${current.error}"
    }
    val currentBranch = current.value.trim()
    if (currentBranch != featureBranch) {
      return "Goal finalization commit-all requires checkout of feature branch '$featureBranch' " +
        "(current branch is '${currentBranch.ifBlank { "<detached/empty>" }}')."
    }
    return null
  }

  private fun deleteGoalSpecScratchOnSuccess(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
  ) = specScratchCleanup.deleteGoalSpecBundle(manifest, request.repoRoot)
}

private data class GoalHistoryWriteInput(
  val input: GoalHistoryWriterInput,
  val invocationId: String,
  val preWriterContentFingerprint: String,
)

internal sealed interface GoalPullRequestOpenOutcome {
  data class Ready(val result: GoalPullRequestResult) : GoalPullRequestOpenOutcome

  data class ProviderPaused(val reason: String) : GoalPullRequestOpenOutcome
}

@Inject
public class GoalRunnerPullRequests(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val pullRequestIdentityLookup: PullRequestIdentityLookup,
  private val specScratchCleanup: GoalSpecScratchCleanup,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  private val diagnostics: RuntimeDiagnostics,
  private val pullRequestWriter: GoalRunnerPullRequestWriter,
) {
  internal fun openPullRequest(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    qaResult: GoalQaGateResult,
  ): GoalPullRequestOpenOutcome {
    val facts =
      state.manifest.pullRequestFacts(
        goalQaSummary = goalQaSummary(state, qaResult),
        goalAssessment = goalAssessment(state),
        qaSummary = { subtask ->
          state.manifest.workflowIdFor(subtask.id)?.let(outcomeStore::recordedQaOutcome)
            ?: GoalSubtaskQaSummary.NotRecorded
        },
        unknownScenarios = { subtask -> unknownScenariosFor(state, subtask) },
      )
    val fallback = state.manifest.pullRequestRequestOf(request.repoRoot, facts)
    writtenPullRequest(state, request, fallback, facts)?.let { return it }
    return GoalPullRequestOpenOutcome.Ready(pullRequestPort.open(fallback))
  }

  private fun writtenPullRequest(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    fallback: GoalPullRequestRequest,
    facts: List<GoalPullRequestFactSection>,
  ): GoalPullRequestOpenOutcome? {
    val head = fallback.headBranch.takeIf(String::isNotBlank) ?: return null
    if (pullRequestIdentityLookup.lookup(request.repoRoot, head) != PullRequestIdentity.Absent) return null
    val instructions = state.manifest.pullRequestDescriptionInstructions(fallback, facts)
    val writerResult =
      catchingNonCancellation {
        pullRequestWriter.run(goalPullRequestPhaseRequest(request, instructions), state.parentWorkflowId, head)
      }.getOrElse { failure ->
        val contained = containableFailure(failure)
        warnPullRequestWriter("failed: ${contained.javaClass.simpleName}: ${contained.message}")
        null
      }
    if (writerResult is PhaseRunResult.Blocked) {
      if (FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(writerResult.reason)) {
        return GoalPullRequestOpenOutcome.ProviderPaused(writerResult.reason)
      }
      warnPullRequestWriter("stopped at '${writerResult.stepId}': ${writerResult.reason}")
    }
    return when (val written = pullRequestIdentityLookup.lookup(request.repoRoot, head)) {
      is PullRequestIdentity.Found -> GoalPullRequestOpenOutcome.Ready(GoalPullRequestResult.Opened(written.url))
      is PullRequestIdentity.Merged -> GoalPullRequestOpenOutcome.Ready(GoalPullRequestResult.Existing(written.url))
      else -> {
        warnPullRequestWriter("left no pull request on '$head'; opening it with the runtime-composed description.")
        null
      }
    }
  }

  private fun goalPullRequestPhaseRequest(
    request: GoalRunnerRunRequest,
    instructions: String,
  ): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.GOAL_PR.id,
      repoRoot = request.repoRoot,
      invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
      intake = request.issueKey,
      instructions = PhaseInstructions(instructions, setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR)),
      timeout = request.timeout,
      agentAddonSelection = request.agentAddonSelection,
      modelAssignment = request.modelAssignment,
    )

  private fun warnPullRequestWriter(message: String) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=goal_pull_request_description ${message.take(MAX_WARNING_REASON)}",
    )
  }

  private fun goalAssessment(state: GoalRunnerManifestState): GoalAssessmentRecordRead =
    try {
      outcomeStore.readGoalAssessmentRecord(state.parentWorkflowId)
    } catch (failure: SkillBillRuntimeException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=goal_assessment_pr_body record_kind=lookup_failed value_expected=assessment_record " +
          "value_used=unreadable reason=${failure.message.orEmpty().take(MAX_WARNING_REASON)}",
      )
      GoalAssessmentRecordRead.Corrupt(failure.message.orEmpty())
    }

  private fun unknownScenariosFor(
    state: GoalRunnerManifestState,
    subtask: DecompositionSubtask,
  ): GoalSubtaskUnknownScenarios? {
    val workflowId = state.manifest.workflowIdFor(subtask.id) ?: return null
    val recorded =
      try {
        outcomeStore.recordedUnknownScenarios(workflowId)
      } catch (failure: SkillBillRuntimeException) {
        warnUnknownScenarios(subtask, failure.message.orEmpty())
        return null
      }
    if (recorded is GoalSubtaskUnknownScenarios.Malformed) warnUnknownScenarios(subtask, recorded.reason)
    if (recorded is GoalSubtaskUnknownScenarios.Recorded) {
      recorded.scenarios.filter { it.reason.isNullOrBlank() }.forEach {
        warnUnknownScenarios(subtask, "scenario=${it.scenarioRef}", "reason_missing", "reason_unavailable")
      }
    }
    return recorded
  }

  private fun warnUnknownScenarios(
    subtask: DecompositionSubtask,
    reason: String,
    recordKind: String = "lookup_failed",
    used: String = "omitted",
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=interaction_trace_pr_body subtask=${subtask.id} record_kind=$recordKind " +
        "value_expected=unknown_scenarios value_used=$used reason=${reason.take(MAX_WARNING_REASON)}",
    )
  }

  private fun goalQaSummary(
    state: GoalRunnerManifestState,
    result: GoalQaGateResult,
  ): GoalQaSummary {
    if (result is GoalQaGateResult.Skipped && result.reason == GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA) {
      return GoalQaSummary.RanPerSubtask
    }
    val recorded = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)
    return when {
      result is GoalQaGateResult.Paused -> GoalQaSummary.NotRecorded
      result is GoalQaGateResult.Warned -> GoalQaSummary.Warned(result.reason, recorded)
      recorded != null -> GoalQaSummary.Recorded(recorded)
      else -> GoalQaSummary.NotRecorded
    }
  }

  internal fun publishedGoalResult(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport? {
    val published =
      pullRequestIdentityLookup.lookup(request.repoRoot, finalState.manifest.featureBranch.orEmpty())
    if (published is PullRequestIdentity.Merged) {
      deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
      return completed(
        finalState.manifest,
        attempted,
        published.url,
        GoalPullRequestStatus.EXISTING,
        resolveFindingsLedger(finalState.manifest.issueKey),
      )
    }
    if (published is PullRequestIdentity.Unavailable) {
      return stopped(
        StoppedReportArgs(
          issueKey = finalState.manifest.issueKey,
          attempted = attempted,
          subtaskId = 0,
          reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
          blockedReason = published.reason,
          workflowId = finalState.parentWorkflowId,
          lastResumableStep = "pr_description",
        ),
      )
    }
    return null
  }

  private fun deleteGoalSpecScratchOnSuccess(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
  ) = specScratchCleanup.deleteGoalSpecBundle(manifest, request.repoRoot)

  internal fun resolveFindingsLedger(issueKey: String): UnaddressedFindingsLedger? =
    resolveUnaddressedFindingsLedger(unaddressedFindingsLedgerService, issueKey)
}

public fun interface GoalRunnerFinalHistoryWriter {
  public fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): PhaseRunResult
}

@Inject
public class PhaseRunGoalFinalHistoryWriter(
  private val phaseRunEntry: PhaseRunEntry,
) : GoalRunnerFinalHistoryWriter {
  override fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): PhaseRunResult = phaseRunEntry.runForGoal(request, parentWorkflowId, branch)
}

public fun interface GoalRunnerPullRequestWriter {
  public fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): PhaseRunResult
}

@Inject
public class PhaseRunGoalPullRequestWriter(
  private val phaseRunEntry: PhaseRunEntry,
) : GoalRunnerPullRequestWriter {
  override fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): PhaseRunResult = phaseRunEntry.runForGoal(request, parentWorkflowId, branch)
}

private sealed interface ValidatedPublicationPrep {
  data class Ready(
    val invocationId: String,
    val qaResult: GoalQaGateResult,
  ) : ValidatedPublicationPrep

  data class Stopped(
    val report: GoalRunnerRunReport,
  ) : ValidatedPublicationPrep
}

private const val MAX_WARNING_REASON = 200

private fun currentQualityExecution(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  parentWorkflowId: String,
) = when (val retained = outcomeStore.retainedGoalQualityExecution(parentWorkflowId)) {
  is GoalQualityExecutionRead.Current -> retained.record.execution
  is GoalQualityExecutionRead.Historical, is GoalQualityExecutionRead.Ambiguous, null -> null
}
