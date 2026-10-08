package skillbill.infrastructure.workflow.git.goal

import skillbill.infrastructure.contracts.newSha256Digest
import skillbill.infrastructure.workflow.git.standard.gitDefaultBranch
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import java.nio.file.Path

internal const val GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES: Int = 1_000_000

internal object GitGoalSubtaskReviewOperations : GoalSubtaskReviewGitOperations {
  override fun captureGoalSubtaskReviewBaseline(
    repoRoot: Path,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    val stable = stableSnapshot(repoRoot, expectedBranch, ::baselineSnapshot)
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.ERROR, error = stable.error)
    } else {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = GoalSubtaskReviewBaseline(snapshot.headSha, snapshot.untrackedPaths),
      )
    }
  }

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    val stable =
      stableSnapshot(repoRoot, expectedBranch) { root, branch ->
        reviewInputSnapshot(root, baseline, branch)
      }
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewInputResult(
        status = WorkflowGitOperationStatus.ERROR,
        error = stable.error,
        failureReason = stable.failureReason,
      )
    } else {
      GoalSubtaskReviewInputResult(
        status = WorkflowGitOperationStatus.OK,
        input =
          GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = snapshot.headSha,
            trackedDelta = snapshot.trackedDelta,
            ownedUntrackedPatches = snapshot.ownedUntrackedPatches,
          ),
      )
    }
  }

  override fun recoverGoalSubtaskReviewBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    val stable =
      stableSnapshot(repoRoot, expectedBranch) { root, branch ->
        recoveredBaselineSnapshot(root, request, branch)
      }
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.ERROR, error = stable.error)
    } else {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = request.toRecoveredBaseline(snapshot.recoveredBaseSha),
      )
    }
  }
}

private fun <T> stableSnapshot(
  repoRoot: Path,
  expectedBranch: String,
  readSnapshot: (Path, String) -> GoalReviewSnapshotResult<T>,
): GoalReviewSnapshotResult<T> {
  val expected = expectedBranch.trim()
  if (expected.isBlank()) {
    return GoalReviewSnapshotResult(error = "Goal-subtask durable child branch is required.")
  }
  val first = readSnapshot(repoRoot, expected)
  val second = if (first.snapshot != null && first.error.isBlank()) readSnapshot(repoRoot, expected) else first
  return when {
    first.snapshot == null || first.error.isNotBlank() -> first
    second.snapshot == null || second.error.isNotBlank() -> second
    first.snapshot != second.snapshot ->
      GoalReviewSnapshotResult(
        error =
          "Goal-subtask repository state changed while preparing its immutable review state; " +
            "refusing a torn snapshot.",
      )
    else -> first
  }
}

private data class GoalReviewBaselineSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
)

private data class GoalReviewInputSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
  val ownedUntrackedPatches: String,
)

private data class GoalReviewRecoveredBaselineSnapshot(
  val branch: String,
  val headSha: String,
  val recoveredBaseSha: String,
)

private data class GoalReviewSnapshotResult<T>(
  val snapshot: T? = null,
  val error: String = "",
  val failureReason: GoalSubtaskReviewInputFailureReason? = null,
)

private fun baselineSnapshot(
  repoRoot: Path,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewBaselineSnapshot> {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head =
    branch?.let {
      goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()
    }
  val indexTree =
    head?.takeIf(String::isNotBlank)?.let {
      goalReviewGitValue(repoRoot, "write-tree")?.trim()
    }
  val tracked =
    indexTree?.takeIf(String::isNotBlank)?.let {
      goalReviewGitValue(repoRoot, "diff", "--binary", "HEAD")
    }
  val untracked = tracked?.let { goalReviewUntrackedPaths(repoRoot) }
  val error =
    when {
      branch == null ->
        "Goal-subtask review baseline must be captured on durable child branch '$expectedBranch'."
      head.isNullOrBlank() -> "Could not resolve HEAD."
      indexTree.isNullOrBlank() ->
        "Could not resolve the git index while capturing the immutable review baseline."
      tracked == null ->
        "Could not read tracked worktree state while capturing the immutable review baseline."
      untracked == null ->
        "Could not read untracked inventory while capturing the immutable review baseline."
      else -> null
    }
  return if (error != null) {
    GoalReviewSnapshotResult(error = error)
  } else {
    GoalReviewSnapshotResult(
      snapshot =
        GoalReviewBaselineSnapshot(
          branch = requireNotNull(branch),
          headSha = requireNotNull(head),
          indexTree = requireNotNull(indexTree),
          trackedDelta = requireNotNull(tracked),
          untrackedPaths = requireNotNull(untracked),
        ),
    )
  }
}

private fun reviewInputSnapshot(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewInputSnapshot> {
  val material = materializeReviewInput(repoRoot, baseline, expectedBranch)
  val failure = reviewInputFailure(material, baseline, expectedBranch)
  return if (failure != null) {
    GoalReviewSnapshotResult(error = failure.message, failureReason = failure.reason)
  } else {
    GoalReviewSnapshotResult(snapshot = material.toSnapshot())
  }
}

private fun recoveredBaselineSnapshot(
  repoRoot: Path,
  request: GoalSubtaskReviewBaselineRecoveryRequest,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewRecoveredBaselineSnapshot> {
  val unreachableSha = request.unreachableSha
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head =
    branch?.let {
      goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
    }

  val nearestAncestor =
    head?.takeIf {
      request.failureReason == GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR &&
        runGitCommand(repoRoot, "cat-file", "-e", "$unreachableSha^{commit}") is WorkflowGitOperationResult.Ok
    }?.let { currentHead ->
      goalReviewGitValue(repoRoot, "merge-base", unreachableSha, currentHead)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeIf {
          runGitCommand(repoRoot, "merge-base", "--is-ancestor", it, currentHead) is WorkflowGitOperationResult.Ok
        }
    }
  val branchBase =
    head?.takeIf { nearestAncestor == null }?.let { currentHead ->
      val defaultBranch = (gitDefaultBranch(repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()
      listOfNotNull(defaultBranch?.let { "origin/$it" }, defaultBranch, "origin/main", "main")
        .filter { runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", it) is WorkflowGitOperationResult.Ok }
        .distinct()
        .firstNotNullOfOrNull { candidate ->
          goalReviewGitValue(repoRoot, "merge-base", candidate, currentHead)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf {
              runGitCommand(repoRoot, "merge-base", "--is-ancestor", it, currentHead) is
                WorkflowGitOperationResult.Ok
            }
        }
    }
  val base = nearestAncestor ?: branchBase
  val error =
    when {
      branch == null ->
        "Goal-subtask review baseline recovery must run on durable child branch '$expectedBranch'."
      head == null -> "Could not resolve current HEAD for goal-subtask review baseline recovery."
      base == null ->
        "Goal-subtask review baseline recovery could not find a reachable base for unreachable sha " +
          "'$unreachableSha' on branch '$expectedBranch'."
      base == head ->
        "Recovered goal-subtask review base must differ from current HEAD."
      base == unreachableSha ->
        "Recovered goal-subtask review base unexpectedly matches the incompatible persisted base."
      else -> null
    }
  return if (error != null) {
    GoalReviewSnapshotResult(error = error)
  } else {
    GoalReviewSnapshotResult(
      snapshot =
        GoalReviewRecoveredBaselineSnapshot(
          branch = requireNotNull(branch),
          headSha = requireNotNull(head),
          recoveredBaseSha = requireNotNull(base),
        ),
    )
  }
}

private fun materializeReviewInput(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewInputMaterial {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head =
    branch?.let {
      goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
    }
  val baseExists =
    head?.let {
      runGitCommand(repoRoot, "cat-file", "-e", "${baseline.reviewBaseSha}^{commit}")
    }
  val baseIsAncestor =
    baseExists?.takeIf { it is WorkflowGitOperationResult.Ok }?.let {
      runGitCommand(repoRoot, "merge-base", "--is-ancestor", baseline.reviewBaseSha, head)
    }
  val indexTree =
    baseIsAncestor?.takeIf { it is WorkflowGitOperationResult.Ok }?.let {
      goalReviewGitValue(repoRoot, "write-tree")?.trim()
    }
  val untracked = indexTree?.takeIf(String::isNotBlank)?.let { goalReviewUntrackedPaths(repoRoot) }
  val ownedUntracked = untracked?.filterNot { it in baseline.baselineUntrackedPaths }
  val scopeFingerprint =
    if (indexTree != null && ownedUntracked != null) {
      GoalReviewStringResult(value = scopeFingerprint(repoRoot, indexTree, ownedUntracked))
    } else {
      null
    }
  return GoalReviewInputMaterial(
    branch,
    head,
    baseExists,
    baseIsAncestor,
    indexTree,
    trackedDelta = scopeFingerprint?.value?.let { "scope-fingerprint:$it\n" },
    untracked,
    patches = scopeFingerprint?.let { GoalReviewStringResult(value = "") },
    totalBytes = null,
  )
}

private fun scopeFingerprint(
  repoRoot: Path,
  indexTree: String,
  ownedUntracked: List<String>,
): String {
  val digest = newSha256Digest()
  digest.update(indexTree.toByteArray())
  digest.update(0)
  ownedUntracked.sorted().forEach { path ->
    digest.update(path.toByteArray())
    digest.update(0)
    val hash = goalReviewGitValue(repoRoot, "hash-object", "--", path)?.trim().orEmpty()
    digest.update(hash.toByteArray())
    digest.update(0)
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private data class GoalReviewInputMaterial(
  val branch: String?,
  val head: String?,
  val baseExists: WorkflowGitOperationResult?,
  val baseIsAncestor: WorkflowGitOperationResult?,
  val indexTree: String?,
  val trackedDelta: String?,
  val untracked: List<String>?,
  val patches: GoalReviewStringResult?,
  val totalBytes: Int?,
)

private data class GoalReviewInputFailure(
  val message: String,
  val reason: GoalSubtaskReviewInputFailureReason? = null,
)

private fun reviewInputFailure(
  material: GoalReviewInputMaterial,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewInputFailure? =
  when {
    material.branch == null ->
      GoalReviewInputFailure("Goal-subtask review must run on durable child branch '$expectedBranch'.")
    material.head == null -> GoalReviewInputFailure("Could not resolve current HEAD.")
    material.baseExists !is WorkflowGitOperationResult.Ok ->
      GoalReviewInputFailure(
        "Persisted review base '${baseline.reviewBaseSha}' is not an existing commit: " +
          material.baseExists?.error.orEmpty(),
        material.baseExists?.takeIf(isDefinitiveMissingObject)?.let {
          GoalSubtaskReviewInputFailureReason.BASE_MISSING
        },
      )
    material.baseIsAncestor !is WorkflowGitOperationResult.Ok ->
      GoalReviewInputFailure(
        "Persisted review base '${baseline.reviewBaseSha}' is not an ancestor of current HEAD; " +
          "refusing a broader review scope.",
        material.baseIsAncestor?.takeIf(isDefinitiveNonAncestor)?.let {
          GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR
        },
      )
    material.indexTree.isNullOrBlank() ->
      GoalReviewInputFailure("Could not resolve the git index while resolving review scope.")
    material.trackedDelta == null ->
      GoalReviewInputFailure("Could not fingerprint the worktree for review scope identity.")
    material.untracked == null ->
      GoalReviewInputFailure("Could not read untracked inventory while resolving review scope.")
    material.patches?.let { patch -> patch.value == null || patch.error.isNotBlank() } != false ->
      GoalReviewInputFailure(material.patches?.error.orEmpty())
    else -> null
  }

private val isDefinitiveMissingObject: (WorkflowGitOperationResult) -> Boolean =
  { result -> result is WorkflowGitOperationResult.Failed && result.error.contains("exit code 128") }

private val isDefinitiveNonAncestor: (WorkflowGitOperationResult) -> Boolean =
  { result -> result is WorkflowGitOperationResult.Failed && result.error.contains("exit code 1") }

private fun GoalReviewInputMaterial.toSnapshot(): GoalReviewInputSnapshot =
  GoalReviewInputSnapshot(
    branch = requireNotNull(branch),
    headSha = requireNotNull(head),
    indexTree = requireNotNull(indexTree),
    trackedDelta = requireNotNull(trackedDelta),
    untrackedPaths = requireNotNull(untracked),
    ownedUntrackedPatches = requireNotNull(patches?.value),
  )

private data class GoalReviewStringResult(
  val value: String? = null,
  val error: String = "",
)

private fun ownedUntrackedPatches(
  repoRoot: Path,
  paths: List<String>,
): GoalReviewStringResult {
  val patches = StringBuilder()
  paths.forEach { path ->
    val patch = runGitProcess(repoRoot, listOf("diff", "--binary", "--no-index", "/dev/null", path))
    if (patch.timedOut || patch.readFailure != null || patch.exitCode !in setOf(0, 1)) {
      return GoalReviewStringResult(
        error = patch.readFailure?.message ?: "Could not diff owned untracked path '$path'.",
      )
    }
    patches.append(patch.output)
    if (!patches.endsWith("\n")) patches.append('\n')
    if (patches.toString().toByteArray().size > GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES) {
      return GoalReviewStringResult(
        error = "Goal-subtask review input exceeds the ${GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES}-byte bound.",
      )
    }
  }
  return GoalReviewStringResult(value = patches.toString())
}
