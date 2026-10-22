package skillbill.ports.workflow.gitops.model

sealed interface WorkflowIgnoredFilesCopyResult {
  data class Copied(
    val copiedPaths: List<String>,
    val skippedPaths: List<String>,
  ) : WorkflowIgnoredFilesCopyResult

  data class Failed(val error: String) : WorkflowIgnoredFilesCopyResult
}
