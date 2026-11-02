package skillbill.infrastructure.workflow.git.changes

import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitRange
import skillbill.ports.workchanges.model.WorkOutsideCommit
import skillbill.ports.workchanges.model.WorkOutsideCommitsResult
import java.nio.file.Path

internal const val OUTSIDE_COMMIT_LIMIT = 200
private const val RECORD_SEPARATOR = '\u0000'
private const val FIELD_SEPARATOR = '\u001f'
private const val LOG_FIELDS = 3
private val BRANCH_LOG_OPTIONS =
  listOf("log", "--no-show-signature", "--reverse", "--topo-order", "--no-color", "-z", "--format=%H%x1f%P%x1f%s")

private data class BranchCommit(val sha: String, val parentSha: String?, val subject: String)

private sealed interface OutsideStep<out T> {
  data class Value<T>(val value: T) : OutsideStep<T>

  data class Stop(val result: WorkOutsideCommitsResult) : OutsideStep<Nothing>
}

internal fun readOutsideCommits(
  root: Path,
  targetBranch: String?,
  ranges: List<WorkCommitRange>,
): WorkOutsideCommitsResult {
  repositoryProblem(
    root,
    WorkOutsideCommitsResult::NotARepository,
    WorkOutsideCommitsResult::Failed,
  )?.let { return it }
  return when (val read = mergeBase(root, targetBranch)) {
    is BaseRead.Resolved -> outsideSince(root, read.sha, ranges)
    is BaseRead.Unavailable -> WorkOutsideCommitsResult.BaseUnavailable(read.reason)
    is BaseRead.Failed -> WorkOutsideCommitsResult.Failed(read.message)
  }
}

private fun outsideSince(
  root: Path,
  mergeBase: String,
  ranges: List<WorkCommitRange>,
): WorkOutsideCommitsResult {
  val branch =
    when (val read = branchCommits(root, mergeBase)) {
      is OutsideStep.Value -> read.value
      is OutsideStep.Stop -> return read.result
    }
  val subtaskShas =
    when (val read = subtaskCommits(root, ranges)) {
      is OutsideStep.Value -> read.value
      is OutsideStep.Stop -> return read.result
    }
  val outside = branch.filterNot { it.sha in subtaskShas }
  if (outside.size > OUTSIDE_COMMIT_LIMIT) return WorkOutsideCommitsResult.TooMany(OUTSIDE_COMMIT_LIMIT)
  return describeOutside(root, mergeBase, outside)
}

private fun describeOutside(
  root: Path,
  mergeBase: String,
  outside: List<BranchCommit>,
): WorkOutsideCommitsResult {
  val commits =
    outside.map { commit ->
      val parent = commit.parentSha ?: return WorkOutsideCommitsResult.Failed("commit ${commit.sha} has no parent")
      when (val read = trackedFiles(root, listOf(parent, commit.sha))) {
        is ListStep.Files -> outsideCommit(commit, parent, read.files)
        is ListStep.Stop -> return WorkOutsideCommitsResult.Failed(read.message)
      }
    }
  return WorkOutsideCommitsResult.Listed(mergeBase, commits)
}

private fun outsideCommit(
  commit: BranchCommit,
  parentSha: String,
  files: List<WorkChangedFile>,
): WorkOutsideCommit {
  val listed = sumListed(parentSha, files)
  return WorkOutsideCommit(commit.sha, parentSha, commit.subject, listed.files, listed.insertions, listed.deletions)
}

private fun branchCommits(
  root: Path,
  mergeBase: String,
): OutsideStep<List<BranchCommit>> =
  captureList(
    root,
    BRANCH_LOG_OPTIONS + "$mergeBase..HEAD",
    { OutsideStep.Stop(WorkOutsideCommitsResult.Failed(it)) },
  ) { text ->
    OutsideStep.Value(text.split(RECORD_SEPARATOR).filter(String::isNotBlank).map(::branchCommit))
  }

private fun branchCommit(record: String): BranchCommit {
  val fields = record.trim('\n').split(FIELD_SEPARATOR, limit = LOG_FIELDS)
  return BranchCommit(
    sha = fields[0],
    parentSha = fields.getOrNull(1)?.split(' ')?.firstOrNull(String::isNotBlank),
    subject = fields.getOrNull(2).orEmpty(),
  )
}

private fun subtaskCommits(
  root: Path,
  ranges: List<WorkCommitRange>,
): OutsideStep<Set<String>> {
  val shas = mutableSetOf<String>()
  for (range in ranges) {
    val read: OutsideStep<List<String>> =
      captureList(
        root,
        listOf("rev-list", "${range.fromSha}..${range.toSha ?: "HEAD"}"),
        { OutsideStep.Stop(rangeFailure(root, range, it)) },
      ) { text -> OutsideStep.Value(text.lines().filter(String::isNotBlank)) }
    when (read) {
      is OutsideStep.Value -> shas += read.value
      is OutsideStep.Stop -> return read
    }
  }
  return OutsideStep.Value(shas)
}

private fun rangeFailure(
  root: Path,
  range: WorkCommitRange,
  message: String,
): WorkOutsideCommitsResult =
  when (val missing = missingCommit(root, listOfNotNull(range.fromSha, range.toSha))) {
    is BaseRead.Unavailable -> WorkOutsideCommitsResult.BaseUnavailable(missing.reason)
    is BaseRead.Failed -> WorkOutsideCommitsResult.Failed(missing.message)
    else -> WorkOutsideCommitsResult.Failed(message)
  }
