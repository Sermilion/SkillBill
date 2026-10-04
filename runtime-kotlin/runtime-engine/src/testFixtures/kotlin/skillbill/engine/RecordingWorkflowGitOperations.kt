package skillbill.engine

import skillbill.ports.workflow.gitops.WorkflowGitOperationsTestBase
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.ReadinessTreeIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.text.RECORD_FIELD_SEPARATOR
import skillbill.workflow.model.goalobservability.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

private const val COMMITTED_HEAD_SHA = "ffffffffffffffffffffffffffffffffffffffff"

class RecordingWorkflowGitOperations(
  var currentBranchValue: String = "feat/existing-runtime-branch",
  var currentBranchResult: WorkflowGitOperationResult? = null,
  var checkoutResult: WorkflowGitOperationResult? = null,
  var landedBranchAfterCheckout: String? = null,
  var existingBranches: Set<String>? = null,
  var branchExistsResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperationsTestBase() {
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
  val runtimePhaseHeadCommitSequence = ArrayDeque<String>()
  var changedPathsBetweenCommitsValue: List<String> = emptyList()
  var worktreeStatusValue: String = " M src/Foo.kt"
  var worktreeStatusResult: WorkflowGitOperationResult? = null
  val worktreeStatusSequence = ArrayDeque<String>()
  var ownedPathsValue: List<String> = emptyList()
  var ownedPathsResult: WorkflowGitNameListResult? = null
  var trackedPathsValue: List<String> = emptyList()
  var commitCountAheadValue: String? = null
  var mergeBaseWithHeadValue: String? = null
  val repositoryFingerprintSequence = ArrayDeque<String>()
  var repositoryFingerprintValue: String? = null
  var repositoryFingerprintCalls: Int = 0
  var readinessTreeIdentity: ReadinessTreeIdentity =
    ReadinessTreeIdentity(
      sourceTreeSha = "a".repeat(40),
      baseRefSha = "b".repeat(40),
      headSha = "c".repeat(40),
    )
  val createCommitMessages = mutableListOf<String>()
  var createCommitResult: WorkflowGitCommitResult? = null
  var localBranchHasUnpushedCommitsValue: Boolean = true
  var headCommitMessageValue: String = ""
  val amendCommitMessages = mutableListOf<String>()
  var amendHeadCommitResult: WorkflowGitOperationResult? = null
  val checkpointRefs = mutableMapOf<String, String>()
  val updateCheckpointRefCalls = mutableListOf<Pair<String, String>>()
  var updateCheckpointRefResult: WorkflowGitOperationResult? = null
  var resolveCheckpointRefResult: WorkflowGitOperationResult? = null
  var onResolveCheckpointRef: ((String) -> WorkflowGitOperationResult?)? = null
  var onResolveCommit: ((String) -> WorkflowGitOperationResult?)? = null
  var invalidShaOnRemediationCommit: Boolean = false
  val resetSoftToCommitCalls = mutableListOf<String>()
  var resetSoftToCommitResult: WorkflowGitOperationResult? = null
  val resetHardToCommitCalls = mutableListOf<String>()
  var resetHardToCommitResult: WorkflowGitOperationResult? = null
  val nonAncestorPairs = mutableSetOf<Pair<String, String>>()
  val stagePathsCalls = mutableListOf<String>()
  var stagePathsResult: WorkflowGitOperationResult? = null
  var indexSnapshotValue: String = ""
  var captureIndexStateResult: WorkflowGitIndexSnapshotResult? = null
  val restoreIndexStateCalls = mutableListOf<String>()
  var restoreIndexStateResult: WorkflowGitOperationResult? = null
  val contentIdentities = mutableMapOf<String, String>()
  var onStagedPathsRead: (() -> Unit)? = null
  var stagedPathsValue: List<String> = emptyList()
  var stagedPathsResult: WorkflowGitNameListResult? = null
  val goalReviewBuildInputs = mutableListOf<GoalSubtaskReviewBaseline>()
  val goalReviewBuildResults = ArrayDeque<GoalSubtaskReviewInputResult>()
  var goalReviewTrackedDelta: String = ""
  var goalReviewRecoveredBaseline: GoalSubtaskReviewBaseline? = null
  var goalReviewRecoverCalls: Int = 0
  val goalReviewRecoverRequests =
    mutableListOf<GoalSubtaskReviewBaselineRecoveryRequest>()

  data class CheckoutCall(val branch: String, val baseBranch: String?)

  val checkoutCalls = mutableListOf<CheckoutCall>()
  val branchExistsCalls = mutableListOf<String>()
  var currentBranchCalls: Int = 0

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    checkoutCalls += CheckoutCall(branch, baseBranch)
    val result = checkoutResult ?: WorkflowGitOperationResult.Ok(value = branch)
    if (result is WorkflowGitOperationResult.Ok) {
      currentBranchValue = landedBranchAfterCheckout ?: branch
    }
    return result
  }

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    branchExistsCalls += branch
    branchExistsResult?.let { return it }
    val exists = existingBranches?.contains(branch.trim()) ?: true
    return WorkflowGitOperationResult.Ok(value = exists.toString())
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    currentBranchCalls++
    return currentBranchResult ?: WorkflowGitOperationResult.Ok(value = currentBranchValue)
  }

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult {
    createCommitMessages += message
    if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
      val bogus = "not-a-valid-commit-sha"
      headCommitShaValue = bogus
      return WorkflowGitCommitResult.Committed(commitSha = bogus)
    }
    val result =
      createCommitResult
        ?: WorkflowGitCommitResult.Committed(commitSha = createCommitMessages.size.toString(16).padStart(40, '0'))
    if (result is WorkflowGitCommitResult.Committed && result.commitSha.isNotBlank()) {
      headCommitShaValue = result.commitSha.trim()
      headCommitMessageValue = message
    }
    return result
  }

  override fun localBranchHasUnpushedCommits(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = localBranchHasUnpushedCommitsValue.toString())

  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult {
    amendHeadCommitResult?.let { return it }
    if (expectedOwnedHeadSha.trim() != headCommitShaValue.trim()) {
      return WorkflowGitOperationResult.Failed(
        error = "HEAD is '$headCommitShaValue' but the caller owns '$expectedOwnedHeadSha'.",
      )
    }
    replacementMessage?.let { message ->
      amendCommitMessages += message
      if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
        createCommitMessages += message
        val bogus = "not-a-valid-commit-sha"
        headCommitShaValue = bogus
        return WorkflowGitOperationResult.Ok(value = bogus)
      }
    }
    headCommitShaValue = "a${amendCommitMessages.size.toString(16)}".padStart(40, '0')
    headCommitMessageValue = replacementMessage ?: headCommitMessageValue
    return WorkflowGitOperationResult.Ok(value = headCommitShaValue)
  }

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = headCommitMessageValue)

  override fun updateCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    updateCheckpointRefCalls += refName to targetSha
    updateCheckpointRefResult?.let { return it }
    checkpointRefs[refName] = targetSha
    return WorkflowGitOperationResult.Ok(value = refName)
  }

  override fun resolveCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult =
    onResolveCheckpointRef?.invoke(refName)
      ?: resolveCheckpointRefResult
      ?: WorkflowGitOperationResult.Ok(value = checkpointRefs[refName].orEmpty())

  override fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(checkpointRefs.keys.toList())

  override fun deleteCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult {
    checkpointRefs.remove(refName)
    return WorkflowGitOperationResult.Ok(value = refName)
  }

  override fun deleteCheckpointRefsUnderPrefix(
    repoRoot: Path,
    namespacePrefix: String,
    subtaskRefPrefix: String,
  ): WorkflowGitOperationResult {
    val refs = checkpointRefs.keys.toList()
    refs.forEach { refName -> checkpointRefs.remove(refName) }
    return WorkflowGitOperationResult.Ok(value = refs.size.toString())
  }

  override fun resetSoftToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult {
    resetSoftToCommitCalls += commitSha.trim()
    val result = resetSoftToCommitResult ?: WorkflowGitOperationResult.Ok(value = commitSha.trim())
    if (result is WorkflowGitOperationResult.Ok) {
      headCommitShaValue = commitSha.trim()
    }
    return result
  }

  override fun resetHardToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult {
    resetHardToCommitCalls += commitSha.trim()
    val result = resetHardToCommitResult ?: WorkflowGitOperationResult.Ok(value = commitSha.trim())
    if (result is WorkflowGitOperationResult.Ok) {
      headCommitShaValue = commitSha.trim()
    }
    return result
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    val ancestor = ancestorSha.trim()
    val descendant = descendantSha.trim()
    if (ancestor.isBlank() || descendant.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "Ancestor and descendant required.")
    }
    val reachable = ancestor == descendant || (ancestor to descendant) !in nonAncestorPairs
    return WorkflowGitOperationResult.Ok(value = if (reachable) "true" else "false")
  }

  var headCommitShaCalls: Int = 0

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    headCommitShaCalls++
    return headCommitShaResult ?: WorkflowGitOperationResult.Ok(value = headCommitShaValue)
  }

  val pushedBranches: MutableList<String> = mutableListOf()
  val leasePushedBranches: MutableList<String> = mutableListOf()
  var pushBranchResult: WorkflowGitOperationResult? = null

  override fun pushBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    pushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun pushBranchWithLease(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    leasePushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult =
    onResolveCommit?.invoke(revision)
      ?: if (revision.startsWith("origin/")) {
        WorkflowGitOperationResult.Failed(
          error = "Revision '$revision' does not name a commit in this repository.",
        )
      } else {
        WorkflowGitOperationResult.Ok(
          value = revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) } ?: COMMITTED_HEAD_SHA,
        )
      }

  override fun runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(
      value = runtimePhaseHeadCommitSequence.removeFirstOrNull().orEmpty(),
    )

  override fun runtimePhaseChangedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Listed(
      if (beforeCommit == afterCommit) emptyList() else changedPathsBetweenCommitsValue,
    )

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    worktreeStatusResult ?: WorkflowGitOperationResult.Ok(
      value = worktreeStatusSequence.removeFirstOrNull() ?: worktreeStatusValue,
    )

  override fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult {
    stagePathsCalls += paths
    return stagePathsResult ?: WorkflowGitOperationResult.Ok(value = "")
  }

  override fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult =
    captureIndexStateResult
      ?: WorkflowGitIndexSnapshotResult.Captured(WorkflowGitIndexSnapshot(indexSnapshotValue))

  override fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult {
    restoreIndexStateCalls += snapshot.encoded
    return restoreIndexStateResult ?: WorkflowGitOperationResult.Ok(value = "")
  }

  override fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult {
    onStagedPathsRead?.invoke()
    return stagedPathsResult ?: WorkflowGitNameListResult.Listed(stagedPathsValue)
  }

  override fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult =
    WorkflowPathContentIdentitiesResult.Resolved(
      identities = paths.associateWith { path -> contentIdentities[path] ?: "identity" },
    )

  override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult =
    ownedPathsResult ?: WorkflowGitNameListResult.Listed(ownedPathsValue)

  override fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Listed(
      trackedPathsValue,
    )

  override fun resolveReadinessTreeIdentity(
    repoRoot: Path,
    baseBranch: String,
    workflowId: String,
  ): WorkflowReadinessTreeIdentityResult =
    WorkflowReadinessTreeIdentityResult.Resolved(
      identity = readinessTreeIdentity.copy(headSha = headCommitShaValue.ifBlank { readinessTreeIdentity.headSha }),
    )

  override fun readinessChangedPathsAgainstBase(
    repoRoot: Path,
    baseBranch: String,
  ): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(ownedPathsValue)

  override fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    commitCountAheadValue?.let { count -> WorkflowGitOperationResult.Ok(value = count) }
      ?: super.commitCountAhead(repoRoot, baseRevision)

  override fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    mergeBaseWithHeadValue?.let { sha -> WorkflowGitOperationResult.Ok(value = sha) }
      ?: super.mergeBaseWithHead(repoRoot, baseRevision)

  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    repositoryFingerprintCalls += 1
    return WorkflowGitOperationResult.Ok(
      value =
        repositoryFingerprintSequence.removeFirstOrNull()
          ?: repositoryFingerprintValue
          ?: "repository-fingerprint-$repositoryFingerprintCalls",
    )
  }

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult {
    repositoryFingerprintCalls += 1
    val scopeHash =
      listOf(
        baseCommit.orEmpty(),
        headCommit,
        ownedPaths.distinct().sorted().joinToString(RECORD_FIELD_SEPARATOR),
      ).joinToString(RECORD_FIELD_SEPARATOR).hashCode().toUInt().toString(16)
    return WorkflowGitOperationResult.Ok(
      value =
        repositoryFingerprintSequence.removeFirstOrNull()
          ?: repositoryFingerprintValue
          ?: "repository-checkpoint-$scopeHash",
    )
  }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(
      status = WorkflowGitOperationStatus.OK,
      changedFileSummary =
        GoalObservabilityChangedFileSummary(
          total = 0,
          added = 0,
          modified = 0,
          deleted = 0,
          renamed = 0,
          untracked = 0,
        ),
      diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
    )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult =
    WorkflowSelectedDiffHunksResult(
      status = WorkflowGitOperationStatus.OK,
      selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
    )

  override fun captureGoalSubtaskReviewBaseline(
    repoRoot: Path,
    expectedBranch: String,
  ) = GoalSubtaskReviewBaselineResult(
    status = WorkflowGitOperationStatus.OK,
    baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
  )

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    goalReviewBuildInputs += baseline
    return goalReviewBuildResults.removeFirstOrNull() ?: GoalSubtaskReviewInputResult(
      status = WorkflowGitOperationStatus.OK,
      input =
        GoalSubtaskReviewInput(
          reviewBaseSha = baseline.reviewBaseSha,
          currentHeadSha = baseline.reviewBaseSha,
          trackedDelta = goalReviewTrackedDelta,
          ownedUntrackedPatches = "",
        ),
    )
  }

  override fun recoverGoalSubtaskReviewBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    goalReviewRecoverCalls++
    goalReviewRecoverRequests += request
    return goalReviewRecoveredBaseline?.let {
      GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.OK, baseline = it)
    } ?: GoalSubtaskReviewBaselineResult(
      status = WorkflowGitOperationStatus.ERROR,
      error = "no recovered baseline configured",
    )
  }
}
