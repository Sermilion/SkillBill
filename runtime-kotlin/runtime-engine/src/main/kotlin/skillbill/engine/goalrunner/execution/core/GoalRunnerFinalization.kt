package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneCompletedSubtaskCheckpointRefs
import skillbill.engine.featuretask.lifecycle.subtask.parseGitPorcelainPaths
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.execution.support.MAX_REPORTED_FINALIZE_DIRTY_PATHS
import skillbill.engine.goalrunner.execution.support.isFeatureSpecPath
import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.findings.resolveUnaddressedFindingsLedger
import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.monitoring.GoalRunnerCiMonitor
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.qa.GoalQaGateResult
import skillbill.engine.goalrunner.qa.GoalQaSkipReason
import skillbill.engine.goalrunner.qa.GoalRunnerQaGate
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySignal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.goalrunner.model.GoalPullRequestStatus
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class GoalRunnerFinalization(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val specScratchStore: SpecScratchStore,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  private val reconciliation: GoalRunnerFinalReconciliation,
  private val noChangePauses: FeatureTaskRuntimePhaseRecorder,
  private val ciMonitor: GoalRunnerCiMonitor,
  private val qaGate: GoalRunnerQaGate,
  private val pullRequestIdentityLookup: PullRequestIdentityLookup,
) {
  fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport {
    val finalState = reconciliation.reconcile(state, request, ledger)
    return finalizePublication(finalState, request, attempted)
  }

  private fun finalizePublication(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport {
    val settled =
      noChangeFinalization(finalState.manifest, request, attempted)
        ?: publishedGoalResult(finalState, request, attempted)
    settled?.let { return it }
    val qaResult = qaGate.run(finalState, request)
    if (qaResult is GoalQaGateResult.Blocked) {
      return stopped(
        StoppedReportArgs(
          issueKey = finalState.manifest.issueKey,
          attempted = attempted,
          subtaskId = finalState.manifest.subtasks.lastOrNull()?.id ?: 0,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = qaResult.reason,
          workflowId = finalState.parentWorkflowId,
          lastResumableStep = qaResult.stepId,
        ),
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
    val result = openPullRequest(finalState, request, qaResult)
    return when (result) {
      is GoalPullRequestResult.Opened ->
        completeMonitoredGoal(finalState, request, attempted, result.url, GoalPullRequestStatus.OPENED)
      is GoalPullRequestResult.Existing ->
        completeMonitoredGoal(
          finalState,
          request,
          attempted,
          result.url,
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
            blockedReason = result.reason,
            workflowId = null,
            lastResumableStep = "pr_description",
          ),
        )
    }
  }

  private fun openPullRequest(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    qaResult: GoalQaGateResult,
  ): GoalPullRequestResult =
    pullRequestPort.open(
      state.manifest.toPullRequestRequest(
        request.repoRoot,
        goalQaSummary = goalQaSummary(state, qaResult),
        qaSummary = { subtask ->
          state.manifest.workflowIdFor(subtask.id)?.let(outcomeStore::recordedQaOutcome)
            ?: GoalSubtaskQaSummary.NotRecorded
        },
      ),
    )

  private fun goalQaSummary(
    state: GoalRunnerManifestState,
    result: GoalQaGateResult,
  ): GoalQaSummary =
    if (result is GoalQaGateResult.Skipped && result.reason == GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA) {
      GoalQaSummary.RanPerSubtask
    } else {
      outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)?.let { GoalQaSummary.Recorded(it) }
        ?: GoalQaSummary.NotRecorded
    }

  private fun publishedGoalResult(
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
          resolveFindingsLedger(state.manifest.issueKey),
        ).copy(monitoringSummary = acceptedMonitoringSummary(result.completedOutputs))
      }
      is PhaseRunResult.Blocked ->
        stopped(
          StoppedReportArgs(
            issueKey = state.manifest.issueKey,
            attempted = attempted,
            subtaskId = 0,
            reason = GoalRunnerStopReason.BLOCKED,
            blockedReason =
              listOf(result.reason, acceptedMonitoringSummary(result.completedOutputs))
                .filterNotNull()
                .filter(String::isNotBlank)
                .joinToString("\n\n"),
            workflowId = state.parentWorkflowId,
            lastResumableStep = result.stepId,
          ),
        )
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
        output.output.value.takeIf(String::isNotBlank)?.let { "$label: $it" }
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

  fun deleteCompletedSubtaskSpecScratch(
    manifest: DecompositionManifest,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val specPath =
      manifest.subtasks.firstOrNull { it.id == subtaskId }?.specPath?.takeIf(String::isNotBlank)
        ?: return
    val resolved = resolvedParentSpecPath(request.repoRoot, Path.of(specPath))
    runCatching { specScratchStore.deleteFileIfExists(resolved) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Goal linear-mode subtask spec scratch deletion at '$resolved' failed; the completed " +
            "subtask is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }

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
    unfinishedGitOperationReason(request.repoRoot)?.let { return it }
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

  private fun unfinishedGitOperationReason(repoRoot: Path): String? {
    val inProgress = gitOperations.inProgressOperation(repoRoot)
    if (inProgress !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization could not check '$repoRoot' for an unfinished git operation: ${inProgress.error}"
    }
    val operation = inProgress.value.trim().ifBlank { return null }
    return "Goal finalization found an unfinished git $operation in '$repoRoot' and will not commit it. " +
      "An interrupted monitor repair can leave one behind. Check and finish it, or discard it with " +
      "`git $operation --abort`, then resume the goal."
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
    val commitError = branchError ?: stageCommitAndPushAll(manifest, request, featureBranch, implementationPaths)
    return commitError ?: verifyWorktreeCleanAfterCommitAll(request)
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
    val pushed = gitOperations.pushBranch(request.repoRoot, featureBranch)
    return if (pushed is WorkflowGitOperationResult.Ok) {
      null
    } else {
      "Goal finalization commit-all committed remaining changes but could not push " +
        "branch '$featureBranch': ${pushed.error}"
    }
  }

  private fun verifyWorktreeCleanAfterCommitAll(request: GoalRunnerRunRequest): String? {
    val after = gitOperations.worktreeStatus(request.repoRoot)
    if (after !is WorkflowGitOperationResult.Ok) {
      return "Goal finalization could not re-verify worktree cleanliness after commit-all: ${after.error}"
    }
    val remaining = parseGitPorcelainPaths(after.value.orEmpty()).filterNot(::isFeatureSpecPath)
    return if (remaining.isEmpty()) {
      null
    } else {
      "Goal finalization commit-all left dirty paths after commit/push: " +
        remaining.take(MAX_REPORTED_FINALIZE_DIRTY_PATHS).joinToString(", ") +
        if (remaining.size > MAX_REPORTED_FINALIZE_DIRTY_PATHS) {
          " (+${remaining.size - MAX_REPORTED_FINALIZE_DIRTY_PATHS} more)"
        } else {
          ""
        }
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
      ?: gitOperations.pushBranch(repoRoot, featureBranch)
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
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val parentSpec = resolvedParentSpecPath(request.repoRoot, Path.of(manifest.parentSpecPath))
    val specDir = parentSpec.parent ?: return
    runCatching { specScratchStore.deleteDirectoryIfExists(specDir) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Goal linear-mode spec scratch deletion at '$specDir' failed; the completed goal is " +
            "unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }

  private fun resolveFindingsLedger(issueKey: String): UnaddressedFindingsLedger? =
    resolveUnaddressedFindingsLedger(unaddressedFindingsLedgerService, issueKey)
}
