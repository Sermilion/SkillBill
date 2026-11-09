package skillbill.ports.workchanges.model

sealed interface WorkTreeProjectResult {
  data class Listed(
    val projectId: String,
    val checkoutRoot: String,
    val worktreeRoots: List<String>,
  ) : WorkTreeProjectResult

  data class Unavailable(val reason: String) : WorkTreeProjectResult
}
