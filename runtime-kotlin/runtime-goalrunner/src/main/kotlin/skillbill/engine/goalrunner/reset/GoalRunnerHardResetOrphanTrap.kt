package skillbill.engine.goalrunner.reset

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtaskAction
import java.nio.file.Path

internal data class GoalRunnerHardResetOrphanTrap(
  val featureBranch: String,
  val headSha: String,
  val activeSubtaskId: Int,
)

internal sealed interface GoalRunnerHardResetBranchCoordination {
  data object NotApplicable : GoalRunnerHardResetBranchCoordination

  data class Documented(val branchActionTaken: String) : GoalRunnerHardResetBranchCoordination

  data class Refused(val reason: String, val remedyCommand: String) : GoalRunnerHardResetBranchCoordination
}

internal sealed interface GoalRunnerHardResetOrphanTrapInspection {
  data object NotApplicable : GoalRunnerHardResetOrphanTrapInspection

  data class Detected(val trap: GoalRunnerHardResetOrphanTrap) : GoalRunnerHardResetOrphanTrapInspection

  data class Unavailable(val reason: String, val remedyCommand: String) : GoalRunnerHardResetOrphanTrapInspection
}

private data class HardResetHead(
  val activeSubtaskId: Int,
  val featureBranch: String,
  val sha: String,
)

internal fun inspectHardResetOrphanTrap(
  manifest: DecompositionManifest,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetOrphanTrapInspection {
  val activeSubtaskId = activeHardResetSubtaskId(manifest)
  val featureBranch = activeSubtaskId?.let { hardResetFeatureBranch(manifest, it) }
  return if (activeSubtaskId == null || featureBranch == null) {
    GoalRunnerHardResetOrphanTrapInspection.NotApplicable
  } else {
    inspectHardResetFeatureBranch(
      manifest = manifest,
      activeSubtaskId = activeSubtaskId,
      featureBranch = featureBranch,
      repoRoot = repoRoot,
      gitOperations = gitOperations,
    )
  }
}

private fun activeHardResetSubtaskId(manifest: DecompositionManifest): Int? {
  val requested =
    manifest.currentSubtaskIntent.subtaskId
      .takeIf { it > 0 }
      ?.let { currentSubtaskId ->
        manifest.subtasks.firstOrNull { subtask ->
          subtask.id == currentSubtaskId &&
            (
              DecompositionSubtaskAction.fromWire(manifest.currentSubtaskIntent.action) ==
                DecompositionSubtaskAction.RESUME ||
                (subtask.status == "in_progress" && !subtask.workflowId.isNullOrBlank())
            )
        }?.id
      }
  return requested ?: manifest.subtasks.firstOrNull { subtask ->
    subtask.status == "in_progress" && !subtask.workflowId.isNullOrBlank()
  }?.id
}

private fun hardResetFeatureBranch(
  manifest: DecompositionManifest,
  activeSubtaskId: Int,
): String? =
  manifest.featureBranch?.trim()?.takeIf(String::isNotBlank)
    ?: manifest.branchPlanFor(activeSubtaskId).branch.trim().takeIf(String::isNotBlank)

private fun inspectHardResetFeatureBranch(
  manifest: DecompositionManifest,
  activeSubtaskId: Int,
  featureBranch: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetOrphanTrapInspection {
  val branchError = ensureHardResetFeatureBranch(featureBranch, repoRoot, gitOperations)
  return branchError?.let { hardResetInspectionUnavailable(manifest.issueKey, it) }
    ?: inspectHardResetHead(
      manifest = manifest,
      activeSubtaskId = activeSubtaskId,
      featureBranch = featureBranch,
      repoRoot = repoRoot,
      gitOperations = gitOperations,
    )
}

private fun ensureHardResetFeatureBranch(
  featureBranch: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): String? {
  val onBranch = gitOperations.currentBranch(repoRoot)
  return when {
    onBranch !is WorkflowGitOperationResult.Ok -> onBranch.error
    onBranch.value.trim() == featureBranch -> null
    else -> checkoutHardResetFeatureBranch(featureBranch, repoRoot, gitOperations)
  }
}

private fun checkoutHardResetFeatureBranch(
  featureBranch: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): String? {
  val checkout = gitOperations.checkoutBranch(repoRoot, featureBranch, null)
  if (checkout !is WorkflowGitOperationResult.Ok) return checkout.error
  val landed = gitOperations.currentBranch(repoRoot)
  return when {
    landed !is WorkflowGitOperationResult.Ok -> landed.error
    landed.value.trim() == featureBranch -> null
    else -> "checkout reported success but HEAD is on '${landed.value.trim()}' instead of '$featureBranch'"
  }
}

private fun inspectHardResetHead(
  manifest: DecompositionManifest,
  activeSubtaskId: Int,
  featureBranch: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetOrphanTrapInspection {
  val headSha =
    gitOperations.headCommitSha(repoRoot)
      .takeIf { it is WorkflowGitOperationResult.Ok }
      ?.value
      ?.trim()
      ?.takeIf(String::isNotBlank)
  return headSha?.let {
    inspectHardResetHeadMessage(
      manifest = manifest,
      head = HardResetHead(activeSubtaskId, featureBranch, it),
      repoRoot = repoRoot,
      gitOperations = gitOperations,
    )
  } ?: hardResetInspectionUnavailable(
    manifest.issueKey,
    "the feature branch HEAD could not be read",
  )
}

private fun inspectHardResetHeadMessage(
  manifest: DecompositionManifest,
  head: HardResetHead,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetOrphanTrapInspection {
  val messageResult = gitOperations.headCommitMessage(repoRoot)
  return if (messageResult !is WorkflowGitOperationResult.Ok) {
    hardResetInspectionUnavailable(manifest.issueKey, messageResult.error)
  } else {
    inspectHardResetIdentity(
      manifest = manifest,
      head = head,
      message = messageResult.value,
    )
  }
}

private fun inspectHardResetIdentity(
  manifest: DecompositionManifest,
  head: HardResetHead,
  message: String,
): GoalRunnerHardResetOrphanTrapInspection {
  val identity = FeatureTaskRuntimeSubtaskCommitIdentity.parse(message)
  val issueMatches = identity?.issueKey.equals(manifest.issueKey.trim(), ignoreCase = true)
  return if (identity != null && issueMatches && identity.subtaskId == head.activeSubtaskId.toString()) {
    GoalRunnerHardResetOrphanTrapInspection.Detected(
      GoalRunnerHardResetOrphanTrap(head.featureBranch, head.sha, head.activeSubtaskId),
    )
  } else {
    GoalRunnerHardResetOrphanTrapInspection.NotApplicable
  }
}

internal fun detectHardResetOrphanTrap(
  manifest: DecompositionManifest,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetOrphanTrap? =
  (
    inspectHardResetOrphanTrap(manifest, repoRoot, gitOperations)
      as? GoalRunnerHardResetOrphanTrapInspection.Detected
  )
    ?.trap

private fun hardResetInspectionUnavailable(
  issueKey: String,
  error: String,
): GoalRunnerHardResetOrphanTrapInspection.Unavailable =
  GoalRunnerHardResetOrphanTrapInspection.Unavailable(
    reason =
      "Hard reset refused because the feature-branch commit identity could not be inspected " +
        "before durable state would be cleared (${error.ifBlank { "unknown git error" }}).",
    remedyCommand = goalPlanningHardResetRemedy(issueKey),
  )

internal fun coordinateHardResetOrphanTrap(
  trap: GoalRunnerHardResetOrphanTrap,
  issueKey: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetBranchCoordination {
  val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, trap.featureBranch)
  return when {
    unpushed !is WorkflowGitOperationResult.Ok ->
      unpublishedStatusUnavailable(trap, issueKey, unpushed.error)
    !unpushed.value.orEmpty().trim().equals("true", ignoreCase = true) ->
      GoalRunnerHardResetBranchCoordination.Documented(
        branchActionTaken =
          "retained_published_trailer_tip_${trap.headSha}; re_adopt_checkpoint_identity_on_relaunch",
      )
    else -> coordinateUnpublishedOrphan(trap, issueKey, repoRoot, gitOperations)
  }
}

private fun unpublishedStatusUnavailable(
  trap: GoalRunnerHardResetOrphanTrap,
  issueKey: String,
  error: String,
): GoalRunnerHardResetBranchCoordination.Refused =
  GoalRunnerHardResetBranchCoordination.Refused(
    reason =
      "Hard reset refused because publication of feature branch '${trap.featureBranch}' could not be " +
        "determined before clearing durable checkpoint identity ($error).",
    remedyCommand = goalPlanningHardResetRemedy(issueKey),
  )

private fun coordinateUnpublishedOrphan(
  trap: GoalRunnerHardResetOrphanTrap,
  issueKey: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): GoalRunnerHardResetBranchCoordination {
  val parent = gitOperations.resolveCommit(repoRoot, "${trap.headSha}~1")
  val parentSha = parent.takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)
  return parentSha?.let {
    resetUnpublishedOrphan(trap, issueKey, repoRoot, gitOperations, it)
  } ?: GoalRunnerHardResetBranchCoordination.Refused(
    reason =
      "Hard reset would clear durable checkpoint identity while branch '${trap.featureBranch}' tip " +
        "${trap.headSha} still carries Skill-Bill-Subtask for active subtask ${trap.activeSubtaskId}, " +
        "and the orphan commit has no parent to reset to.",
    remedyCommand = hardResetOrphanTrapRemedy(issueKey, repoRoot, trap.headSha),
  )
}

private fun resetUnpublishedOrphan(
  trap: GoalRunnerHardResetOrphanTrap,
  issueKey: String,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
  parentSha: String,
): GoalRunnerHardResetBranchCoordination {
  val reset = gitOperations.resetHardToCommit(repoRoot, parentSha)
  return if (reset is WorkflowGitOperationResult.Ok) {
    GoalRunnerHardResetBranchCoordination.Documented(
      branchActionTaken = "reset_feature_branch_tip_to_$parentSha",
    )
  } else {
    GoalRunnerHardResetBranchCoordination.Refused(
      reason =
        "Hard reset would clear durable checkpoint identity while branch '${trap.featureBranch}' tip " +
          "${trap.headSha} still carries Skill-Bill-Subtask for active subtask ${trap.activeSubtaskId}, " +
          "and the feature branch tip could not be moved (${reset.error}).",
      remedyCommand = hardResetOrphanTrapRemedy(issueKey, repoRoot, parentSha),
    )
  }
}

internal fun hardResetOrphanTrapRemedy(
  issueKey: String,
  repoRoot: Path,
  resetTargetSha: String,
): String =
  "git -C ${repoRoot.toAbsolutePath().normalize()} reset --hard $resetTargetSha && " +
    goalPlanningHardResetRemedy(issueKey)

internal fun reviewBaselineBlockedReason(
  manifest: DecompositionManifest,
  subtaskId: Int,
  reason: String,
): String {
  if (subtaskId <= 1) return reason
  val priorCommit =
    manifest.subtasks.firstOrNull { it.id == subtaskId - 1 }
      ?.commitSha
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return reason
  return "$reason Recover with: '${goalPlanningHardResetRemedy(manifest.issueKey)}' " +
    "(prior subtask commit $priorCommit is the review base)."
}
