package skillbill.ports.idestatus.model

sealed interface WorkChangeBaseline {
  val worktreeRoot: String

  data class SubtaskStart(
    override val worktreeRoot: String,
    val baseSha: String,
    val excludedUntrackedPaths: List<String>,
  ) : WorkChangeBaseline

  data class RunStart(
    override val worktreeRoot: String,
    val baseSha: String,
    val excludedUntrackedPaths: List<String>,
  ) : WorkChangeBaseline

  data class TargetMergeBase(
    override val worktreeRoot: String,
    val targetBranch: String?,
  ) : WorkChangeBaseline
}
