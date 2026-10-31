package skillbill.infrastructure.workflow.git.changes

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.git.standard.gitDefaultBranch
import skillbill.infrastructure.workflow.process.GitStdoutCapture
import skillbill.infrastructure.workflow.process.captureGitStdout
import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val PATCH_LIMIT_BYTES = 1_048_576
private const val LIST_LIMIT_BYTES = 8L * 1_048_576
private const val NUL = '\u0000'
private val SUCCESS_EXIT = setOf(0)
private val NO_INDEX_EXIT = setOf(0, 1)
private val RENAME_CONFIG = listOf("-c", "diff.renames=true")
private val DIFF_OPTIONS = listOf("-M", "--relative", "--no-ext-diff", "--no-textconv", "--no-color")
private val PATCH_OPTIONS = listOf("--unified=3", "--src-prefix=a/", "--dst-prefix=b/")
private val BINARY_PATCH = Regex("^Binary files .* differ$", RegexOption.MULTILINE)

@Inject
class GitWorkChangesAdapter : WorkChangesPort {
  override fun changes(baseline: WorkChangeBaseline): WorkChangeListResult {
    val root = Path.of(baseline.worktreeRoot)
    repositoryProblem(root)?.let { return it }
    val base =
      when (val resolved = resolveBase(root, baseline)) {
        is BaseRead.Resolved -> resolved.sha
        is BaseRead.Unavailable -> return WorkChangeListResult.BaseUnavailable(resolved.reason)
        is BaseRead.Failed -> return WorkChangeListResult.Failed(resolved.message)
      }
    return when (val reachable = commitExists(root, base)) {
      is BaseRead.Resolved -> listChanges(root, base, excludedUntracked(baseline))
      is BaseRead.Unavailable -> WorkChangeListResult.BaseUnavailable(reachable.reason)
      is BaseRead.Failed -> WorkChangeListResult.Failed(reachable.message)
    }
  }

  override fun fileDiff(
    worktreeRoot: String,
    baseSha: String,
    file: WorkChangedFile,
  ): WorkFileDiffResult {
    val root = Path.of(worktreeRoot)
    return when (val reachable = commitExists(root, baseSha)) {
      is BaseRead.Unavailable -> WorkFileDiffResult.BaseUnavailable(reachable.reason)
      is BaseRead.Failed -> WorkFileDiffResult.Failed(reachable.message)
      is BaseRead.Resolved ->
        when {
          file.insertions == null && file.deletions == null -> WorkFileDiffResult.Binary
          file.status == WorkChangeStatus.UNTRACKED -> untrackedDiff(root, file.path)
          else -> patch(root, trackedDiffArgs(baseSha, file), SUCCESS_EXIT)
        }
    }
  }

  private fun repositoryProblem(root: Path): WorkChangeListResult? {
    if (!Files.isDirectory(root)) return WorkChangeListResult.NotARepository(root.toString())
    val args = listOf("rev-parse", "--is-inside-work-tree")
    val result = runGitProcess(root, args)
    return when {
      result.timedOut -> WorkChangeListResult.Failed(gitTimedOutError(args))
      result.readFailure != null -> WorkChangeListResult.Failed(result.readFailure.message.orEmpty())
      result.exitCode != 0 || result.output != "true" -> WorkChangeListResult.NotARepository(root.toString())
      else -> null
    }
  }

  private fun resolveBase(
    root: Path,
    baseline: WorkChangeBaseline,
  ): BaseRead =
    when (baseline) {
      is WorkChangeBaseline.SubtaskStart -> BaseRead.Resolved(baseline.baseSha)
      is WorkChangeBaseline.RunStart -> BaseRead.Resolved(baseline.baseSha)
      is WorkChangeBaseline.TargetMergeBase -> {
        val target =
          baseline.targetBranch
            ?: when (val branch = gitDefaultBranch(root)) {
              is WorkflowGitOperationResult.Ok -> branch.value
              is WorkflowGitOperationResult.Failed -> return BaseRead.Unavailable("no default branch: ${branch.error}")
            }
        scalar(root, listOf("merge-base", "HEAD", target), "no merge-base between HEAD and $target")
      }
    }

  private fun commitExists(
    root: Path,
    sha: String,
  ): BaseRead =
    when (val read = scalar(root, listOf("cat-file", "-e", "$sha^{commit}"), "base $sha is not a reachable commit")) {
      is BaseRead.Resolved -> BaseRead.Resolved(sha)
      else -> read
    }

  private fun scalar(
    root: Path,
    args: List<String>,
    unavailableReason: String,
  ): BaseRead {
    val result = runGitProcess(root, args)
    return when {
      result.timedOut -> BaseRead.Failed(gitTimedOutError(args))
      result.readFailure != null -> BaseRead.Failed(result.readFailure.message.orEmpty())
      result.exitCode != 0 -> BaseRead.Unavailable(unavailableReason)
      else -> BaseRead.Resolved(result.output)
    }
  }

  private fun listChanges(
    root: Path,
    base: String,
    excluded: Set<String>,
  ): WorkChangeListResult {
    val tracked =
      when (val read = trackedFiles(root, base)) {
        is ListStep.Files -> read.files
        is ListStep.Stop -> return WorkChangeListResult.Failed(read.message)
      }
    val untracked =
      when (val read = untrackedFiles(root, excluded)) {
        is ListStep.Files -> read.files
        is ListStep.Stop -> return WorkChangeListResult.Failed(read.message)
      }
    val files = (tracked + untracked).sortedBy(WorkChangedFile::path)
    return WorkChangeListResult.Listed(
      baseSha = base,
      files = files,
      insertions = files.sumOf { it.insertions ?: 0 },
      deletions = files.sumOf { it.deletions ?: 0 },
    )
  }

  private fun trackedFiles(
    root: Path,
    base: String,
  ): ListStep =
    captureList(root, RENAME_CONFIG + listOf("diff", "--raw", "--numstat", "-z") + DIFF_OPTIONS + base) { text ->
      parseTrackedChanges(text)?.let(ListStep::Files) ?: ListStep.Stop("unparseable git diff output")
    }

  private fun untrackedFiles(
    root: Path,
    excluded: Set<String>,
  ): ListStep =
    captureList(root, listOf("ls-files", "--others", "--exclude-standard", "-z")) { text ->
      try {
        ListStep.Files(
          text.split(NUL).filter { it.isNotEmpty() && it !in excluded }.mapNotNull { untrackedChange(root, it) },
        )
      } catch (error: IOException) {
        ListStep.Stop("untracked file could not be read: ${error.message}")
      }
    }

  private fun captureList(
    root: Path,
    args: List<String>,
    onText: (String) -> ListStep,
  ): ListStep =
    when (val read = captureGitStdout(root, args, LIST_LIMIT_BYTES, SUCCESS_EXIT)) {
      is GitStdoutCapture.Captured -> onText(read.text)
      GitStdoutCapture.TooLarge -> ListStep.Stop("git ${args.joinToString(" ")} output exceeds $LIST_LIMIT_BYTES bytes")
      is GitStdoutCapture.Failed -> ListStep.Stop(read.message)
    }

  private fun untrackedDiff(
    root: Path,
    path: String,
  ): WorkFileDiffResult {
    val size =
      try {
        Files.readAttributes(root.resolve(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
      } catch (error: IOException) {
        return WorkFileDiffResult.Failed("untracked file could not be read: ${error.message}")
      }
    if (size > PATCH_LIMIT_BYTES) return WorkFileDiffResult.TooLarge(PATCH_LIMIT_BYTES)
    val args =
      listOf("diff", "--no-index", "--no-color", "--no-ext-diff", "--no-textconv") +
        PATCH_OPTIONS + listOf("--", "/dev/null", path)
    return patch(root, args, NO_INDEX_EXIT)
  }

  private fun trackedDiffArgs(
    baseSha: String,
    file: WorkChangedFile,
  ): List<String> =
    RENAME_CONFIG + listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "-M", "--relative") +
      PATCH_OPTIONS + baseSha + "--" + listOfNotNull(file.oldPath, file.path)

  private fun patch(
    root: Path,
    args: List<String>,
    acceptedExitCodes: Set<Int>,
  ): WorkFileDiffResult =
    when (val read = captureGitStdout(root, args, PATCH_LIMIT_BYTES.toLong(), acceptedExitCodes)) {
      is GitStdoutCapture.Captured ->
        if (BINARY_PATCH.containsMatchIn(read.text)) WorkFileDiffResult.Binary else WorkFileDiffResult.Patch(read.text)
      GitStdoutCapture.TooLarge -> WorkFileDiffResult.TooLarge(PATCH_LIMIT_BYTES)
      is GitStdoutCapture.Failed -> WorkFileDiffResult.Failed(read.message)
    }

  private fun excludedUntracked(baseline: WorkChangeBaseline): Set<String> =
    when (baseline) {
      is WorkChangeBaseline.SubtaskStart -> baseline.excludedUntrackedPaths.toSet()
      is WorkChangeBaseline.RunStart -> baseline.excludedUntrackedPaths.toSet()
      is WorkChangeBaseline.TargetMergeBase -> emptySet()
    }
}

private sealed interface ListStep {
  data class Files(val files: List<WorkChangedFile>) : ListStep

  data class Stop(val message: String) : ListStep
}

private sealed interface BaseRead {
  data class Resolved(val sha: String) : BaseRead

  data class Unavailable(val reason: String) : BaseRead

  data class Failed(val message: String) : BaseRead
}
