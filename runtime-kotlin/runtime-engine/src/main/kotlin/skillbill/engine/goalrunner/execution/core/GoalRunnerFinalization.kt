package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneCompletedSubtaskCheckpointRefs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.goalrunner.execution.support.MAX_REPORTED_FINALIZE_DIRTY_PATHS
import skillbill.engine.goalrunner.execution.support.isFeatureSpecPath
import skillbill.engine.goalrunner.execution.support.parseGitPorcelainPaths
import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.findings.resolveUnaddressedFindingsLedger
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
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
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class GoalRunnerFinalization(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val specScratchStore: SpecScratchStore,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  private val progressReader: GoalRunnerProgressReader,
) {
  fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport {
    reconcileBeforeFinalization(state, request, ledger)
    val finalState = manifestStore.save(state)
    return finalizePublication(finalState, request, attempted)
  }

  private fun finalizePublication(
    finalState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport {
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
    val findingsLedger = resolveFindingsLedger(finalState.manifest.issueKey)
    val result = pullRequestPort.open(finalState.manifest.toPullRequestRequest(request.repoRoot))
    return when (result) {
      is GoalPullRequestResult.Opened -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(
          finalState.manifest,
          attempted,
          pullRequestUrl = result.url,
          pullRequestStatus = GoalPullRequestStatus.OPENED,
          findingsLedger,
        )
      }
      is GoalPullRequestResult.Existing -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(
          finalState.manifest,
          attempted,
          pullRequestUrl = result.url,
          pullRequestStatus = GoalPullRequestStatus.EXISTING,
          findingsLedger,
        )
      }
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

  private fun reconcileBeforeFinalization(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    ledger: GoalRunnerLedgerRecorder,
  ) {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      repoRoot = request.repoRoot,
    )
    state.manifest.subtasks
      .lastOrNull { subtask -> !subtask.workflowId.isNullOrBlank() }
      ?.let { subtask ->
        ledger.recordLedgerEntry(
          GoalRunnerLedgerContext.FinalReconciledOutcome(
            workflowId = subtask.workflowId,
            issueKey = state.manifest.issueKey,
            subtaskId = subtask.id,
            progress = subtask.workflowId?.let { progressReader.safeProgress(it) },
            blockedReason = null,
            finalReconciledResult = "goal_finalize status=${state.manifest.status}",
            stopReason = null,
            diagnosticClass = null,
            recoverableJsonPresent = null,
            nextSafeAction = null,
            attemptDurationMillis = null,
            causingLoopEntry = null,
            reAttemptCause = null,
            findingsInScope = null,
          ),
        )
      }
  }

  private fun commitAllRemainingWorktree(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
  ): String? {
    if (manifest.specSource == SpecSource.LINEAR) {
      deleteGoalSpecScratchOnSuccess(manifest, request)
    }
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

  private fun commitAndPushDirtyWorktree(
    manifest: DecompositionManifest,
    request: GoalRunnerRunRequest,
    featureBranch: String,
    implementationPaths: List<String>,
  ): String? {
    if (manifest.executionModel == DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK) {
      val sample = implementationPaths.take(MAX_REPORTED_FINALIZE_DIRTY_PATHS).joinToString(", ")
      val suffix =
        if (implementationPaths.size > MAX_REPORTED_FINALIZE_DIRTY_PATHS) {
          " (+${implementationPaths.size - MAX_REPORTED_FINALIZE_DIRTY_PATHS} more)"
        } else {
          ""
        }
      return "Goal finalization in same-branch mode refuses to commit leftover implementation paths " +
        "($sample$suffix); route each through subtask commit_push finalization."
    }
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
