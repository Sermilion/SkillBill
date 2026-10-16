package skillbill.ports.workflow.gitops.model

data class WorkflowGitCommitIdentity(
  val treeSha: String,
  val parentShas: List<String>,
)

sealed interface WorkflowGitCommitIdentityResult {
  data class Found(val identity: WorkflowGitCommitIdentity) : WorkflowGitCommitIdentityResult

  data class Failed(val error: String) : WorkflowGitCommitIdentityResult
}
