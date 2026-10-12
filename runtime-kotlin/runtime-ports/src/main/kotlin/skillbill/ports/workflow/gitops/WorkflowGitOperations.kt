package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations

/** Every git capability the runtime depends on, as one flat contract with no sub-port getters. */
interface WorkflowGitOperations :
  WorkflowGitBranchOperations,
  WorkflowGitRemoteOperations,
  WorkflowGitCommitHistoryOperations,
  WorkflowGitWorktreeOperations,
  SuppressionEvidenceGitOperations,
  CheckpointHistoryGitOperations,
  GoalSubtaskReviewGitOperations,
  RepositoryFingerprintGitOperations,
  RepositoryOwnedPathsGitOperations,
  RuntimePhaseFileManifestGitOperations,
  ScopedStagingGitOperations,
  WorkflowGitReleaseTagOperations
