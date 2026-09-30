package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContentsResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Path

internal const val HASH_RADIX_HEX: Int = 16
internal const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

object NoopSuppressionEvidenceGitOperations : SuppressionEvidenceGitOperations {
  override fun scopedPathContentsAgainstBase(
    repoRoot: Path,
    baseRef: String,
    headPaths: List<String>,
  ) = WorkflowScopedPathContentsResult(
    status = WorkflowGitOperationStatus.ERROR,
    error = "WorkflowGitOperations must provide a suppression-evidence implementation.",
  )
}

object UnavailableScopedStagingGitOperations : ScopedStagingGitOperations {
  override fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult = unavailable("stage an explicit owned-path inventory")

  override fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult =
    WorkflowGitIndexSnapshotResult.Failed(unavailableReason("capture the pre-checkpoint index state"))

  override fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult = unavailable("restore the pre-checkpoint index state")

  override fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Failed(unavailableReason("list staged paths"))

  override fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult =
    WorkflowPathContentIdentitiesResult.Failed(unavailableReason("read owned-path content identities"))

  private fun unavailable(capability: String) = WorkflowGitOperationResult.Failed(error = unavailableReason(capability))

  private fun unavailableReason(capability: String) =
    "This git operations implementation cannot $capability; scoped checkpoints require a git adapter."
}

object NoopRuntimePhaseFileManifestGitOperations : RuntimePhaseFileManifestGitOperations {
  override fun runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "")

  override fun runtimePhaseChangedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())
}

object UnavailableRepositoryOwnedPathsGitOperations : RepositoryOwnedPathsGitOperations {
  override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult =
    error("WorkflowGitOperations must provide a repository owned-paths implementation.")

  override fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult =
    error("WorkflowGitOperations must provide a repository tracked-paths implementation.")
}

object UnavailableReadinessTreeIdentityGitOperations : ReadinessTreeIdentityGitOperations {
  override fun resolveReadinessTreeIdentity(
    repoRoot: Path,
    baseBranch: String,
    workflowId: String,
  ): WorkflowReadinessTreeIdentityResult =
    error("WorkflowGitOperations must provide a readiness tree identity implementation.")

  override fun readinessChangedPathsAgainstBase(
    repoRoot: Path,
    baseBranch: String,
  ): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Failed("WorkflowGitOperations must provide readiness changed-path discovery.")
}

object UnavailableRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    error("WorkflowGitOperations must provide a repository fingerprint implementation.")

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult =
    error("WorkflowGitOperations must provide a repository checkpoint fingerprint implementation.")
}
