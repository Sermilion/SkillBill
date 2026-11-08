package skillbill.infrastructure.workflow.git.changes

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.workchanges.WorkTreeSnapshotPort
import skillbill.ports.workchanges.model.WorkTreeSnapshotResult
import java.nio.file.Path

@Inject
class GitWorkTreeSnapshotAdapter : WorkTreeSnapshotPort {
  override fun capture(worktreeRoot: String): WorkTreeSnapshotResult {
    val root = Path.of(worktreeRoot)
    repositoryProblem(root, WorkTreeSnapshotResult::NotARepository, WorkTreeSnapshotResult::Failed)?.let { return it }
    val headArgs = listOf("rev-parse", "--verify", "HEAD^{commit}")
    return when (val head = scalar(root, headArgs, "HEAD does not name a commit")) {
      is BaseRead.Resolved -> captureAtHead(root, head.sha)
      is BaseRead.Unavailable -> WorkTreeSnapshotResult.NoCommit(head.reason)
      is BaseRead.Failed -> WorkTreeSnapshotResult.Failed(head.message)
    }
  }

  private fun captureAtHead(
    root: Path,
    headSha: String,
  ): WorkTreeSnapshotResult {
    var pathFailure: String? = null
    val paths =
      captureList(root, listOf("ls-files", "--others", "--exclude-standard", "-z"), { error ->
        pathFailure = error
        emptyList()
      }) { output -> output.split('\u0000').filter(String::isNotEmpty) }
    pathFailure?.let { return WorkTreeSnapshotResult.Failed(it) }
    val stashArgs = listOf("stash", "create")
    val stash = runGitProcess(root, stashArgs)
    return when {
      stash.timedOut -> WorkTreeSnapshotResult.Failed(gitTimedOutError(stashArgs))
      stash.readFailure != null -> WorkTreeSnapshotResult.Failed(stash.readFailure.message.orEmpty())
      stash.exitCode != 0 -> WorkTreeSnapshotResult.Failed(stash.output)
      else -> WorkTreeSnapshotResult.Captured(headSha, paths, stash.output.takeIf(String::isNotBlank))
    }
  }
}
