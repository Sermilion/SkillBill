package skillbill.infrastructure.workflow.git.changes

import skillbill.ports.workchanges.model.WorkTreeProjectResult
import java.nio.file.Files
import java.nio.file.Path

internal fun readProjectWorktrees(root: Path): WorkTreeProjectResult {
  repositoryProblem(root, WorkTreeProjectResult::Unavailable, WorkTreeProjectResult::Unavailable)?.let { return it }
  val common =
    scalar(
      root,
      listOf("rev-parse", "--path-format=absolute", "--git-common-dir"),
      "Git common directory is unavailable",
    )
  val checkout = scalar(root, listOf("rev-parse", "--show-toplevel"), "Checkout root is unavailable")
  return when {
    common is BaseRead.Failed -> WorkTreeProjectResult.Unavailable(common.message)
    common is BaseRead.Unavailable -> WorkTreeProjectResult.Unavailable(common.reason)
    checkout is BaseRead.Failed -> WorkTreeProjectResult.Unavailable(checkout.message)
    checkout is BaseRead.Unavailable -> WorkTreeProjectResult.Unavailable(checkout.reason)
    common is BaseRead.Resolved && checkout is BaseRead.Resolved ->
      captureList(
        root,
        listOf("worktree", "list", "--porcelain", "-z"),
        WorkTreeProjectResult::Unavailable,
      ) { text ->
        val roots =
          text.split("\u0000\u0000").map { it.split('\u0000') }.filter { "bare" !in it }
            .mapNotNull { fields -> fields.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") }
            .map { Path.of(it).normalize().toString() }.filter { Files.isDirectory(Path.of(it)) }
        if (Path.of(checkout.sha).normalize().toString() !in roots) {
          WorkTreeProjectResult.Unavailable("Current checkout is absent from the project worktree list")
        } else {
          WorkTreeProjectResult.Listed(
            Path.of(common.sha).normalize().toString(),
            Path.of(checkout.sha).normalize().toString(),
            roots,
          )
        }
      }
    else -> WorkTreeProjectResult.Unavailable("Project worktrees are unavailable")
  }
}
