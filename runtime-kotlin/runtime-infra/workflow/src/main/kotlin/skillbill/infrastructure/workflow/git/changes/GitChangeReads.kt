package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.standard.gitDefaultBranch
import skillbill.infrastructure.workflow.process.GitStdoutCapture
import skillbill.infrastructure.workflow.process.captureGitStdout
import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path

internal const val PATCH_LIMIT_BYTES = 1_048_576
internal const val LIST_LIMIT_BYTES = 8L * 1_048_576
internal val SUCCESS_EXIT = setOf(0)
internal val NO_INDEX_EXIT = setOf(0, 1)
private val RENAME_CONFIG = listOf("-c", "diff.renames=true")
private val DIFF_OPTIONS = listOf("-M", "--relative", "--no-ext-diff", "--no-textconv", "--no-color")
internal val PATCH_OPTIONS = listOf("--unified=3", "--src-prefix=a/", "--dst-prefix=b/")
private val BINARY_PATCH = Regex("^Binary files .* differ$", RegexOption.MULTILINE)

internal sealed interface ListStep {
  data class Files(val files: List<WorkChangedFile>) : ListStep

  data class Stop(val message: String) : ListStep
}

internal sealed interface BaseRead {
  data class Resolved(val sha: String) : BaseRead

  data class Unavailable(val reason: String) : BaseRead

  data class Failed(val message: String) : BaseRead
}

internal fun trackedListArgs(endpoints: List<String>): List<String> =
  RENAME_CONFIG + listOf("diff", "--raw", "--numstat", "-z") + DIFF_OPTIONS + endpoints

internal fun trackedPatchArgs(
  endpoints: List<String>,
  file: WorkChangedFile,
): List<String> =
  RENAME_CONFIG + listOf("diff") + DIFF_OPTIONS + PATCH_OPTIONS + endpoints + "--" +
    listOfNotNull(file.oldPath, file.path)

internal fun sumListed(
  baseSha: String,
  files: List<WorkChangedFile>,
): WorkChangeListResult.Listed {
  val sorted = files.sortedBy(WorkChangedFile::path)
  return WorkChangeListResult.Listed(
    baseSha = baseSha,
    files = sorted,
    insertions = sorted.sumOf { it.insertions ?: 0 },
    deletions = sorted.sumOf { it.deletions ?: 0 },
  )
}

internal fun trackedFiles(
  root: Path,
  endpoints: List<String>,
): ListStep =
  captureList(root, trackedListArgs(endpoints), ListStep::Stop) { text ->
    parseTrackedChanges(text)?.let(ListStep::Files) ?: ListStep.Stop("unparseable git diff output")
  }

internal fun <T> repositoryProblem(
  root: Path,
  notARepository: (String) -> T,
  failed: (String) -> T,
): T? {
  if (!Files.isDirectory(root)) return notARepository(root.toString())
  val args = listOf("rev-parse", "--is-inside-work-tree")
  val result = runGitProcess(root, args)
  return when {
    result.timedOut -> failed(gitTimedOutError(args))
    result.readFailure != null -> failed(result.readFailure.message.orEmpty())
    result.exitCode != 0 || result.output != "true" -> notARepository(root.toString())
    else -> null
  }
}

internal fun listRepositoryProblem(root: Path): WorkChangeListResult? =
  repositoryProblem(root, WorkChangeListResult::NotARepository, WorkChangeListResult::Failed)

internal fun mergeBase(
  root: Path,
  targetBranch: String?,
): BaseRead {
  val target =
    targetBranch
      ?: when (val branch = gitDefaultBranch(root)) {
        is WorkflowGitOperationResult.Ok -> branch.value
        is WorkflowGitOperationResult.Failed -> return BaseRead.Unavailable("no default branch: ${branch.error}")
      }
  return scalar(root, listOf("merge-base", "HEAD", target), "no merge-base between HEAD and $target")
}

internal fun commitExists(
  root: Path,
  sha: String,
): BaseRead =
  when (val read = scalar(root, listOf("cat-file", "-e", "$sha^{commit}"), "base $sha is not a reachable commit")) {
    is BaseRead.Resolved -> BaseRead.Resolved(sha)
    else -> read
  }

internal fun missingCommit(
  root: Path,
  shas: List<String>,
): BaseRead? = shas.asSequence().map { commitExists(root, it) }.firstOrNull { it !is BaseRead.Resolved }

internal fun scalar(
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

internal fun <T> captureList(
  root: Path,
  args: List<String>,
  onStop: (String) -> T,
  onText: (String) -> T,
): T =
  when (val read = captureGitStdout(root, args, LIST_LIMIT_BYTES, SUCCESS_EXIT)) {
    is GitStdoutCapture.Captured -> onText(read.text)
    GitStdoutCapture.TooLarge -> onStop("git ${args.joinToString(" ")} output exceeds $LIST_LIMIT_BYTES bytes")
    is GitStdoutCapture.Failed -> onStop(read.message)
  }

internal fun patch(
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
