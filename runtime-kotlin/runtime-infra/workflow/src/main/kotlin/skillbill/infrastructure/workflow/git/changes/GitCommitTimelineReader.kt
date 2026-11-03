package skillbill.infrastructure.workflow.git.changes

import skillbill.ports.workchanges.model.WorkBranchCommit
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import java.nio.file.Path

private const val RECORD_SEPARATOR = '\u0000'
private const val FIELD_SEPARATOR = '\u001f'
private const val LOG_FIELDS = 4
private const val MESSAGE_FIELD = 3
private val BRANCH_LOG_OPTIONS =
  listOf("log", "--no-show-signature", "--reverse", "--topo-order", "--no-color", "-z", "--format=%H%x1f%P%x1f%s%x1f%B")

internal fun readCommitTimeline(
  root: Path,
  targetBranch: String?,
  recordedShas: List<String>,
  headBranch: String?,
): WorkCommitTimelineResult {
  repositoryProblem(
    root,
    WorkCommitTimelineResult::NotARepository,
    WorkCommitTimelineResult::Failed,
  )?.let { return it }
  val head = headBranch ?: "HEAD"
  val headRead = scalar(root, listOf("rev-parse", "--verify", "$head^{commit}"), "head $head is unavailable")
  val headSha =
    when (headRead) {
      is BaseRead.Resolved -> headRead.sha
      is BaseRead.Unavailable -> return WorkCommitTimelineResult.BaseUnavailable(headRead.reason)
      is BaseRead.Failed -> return WorkCommitTimelineResult.Failed(headRead.message)
    }
  return timelineFromHead(root, targetBranch, headSha, recordedShas)
}

private fun timelineFromHead(
  root: Path,
  targetBranch: String?,
  headSha: String,
  recordedShas: List<String>,
): WorkCommitTimelineResult {
  val mergeBase =
    when (val read = mergeBase(root, targetBranch, headSha)) {
      is BaseRead.Resolved -> read.sha
      is BaseRead.Unavailable -> return WorkCommitTimelineResult.BaseUnavailable(read.reason)
      is BaseRead.Failed -> return WorkCommitTimelineResult.Failed(read.message)
    }
  return listTimeline(root, mergeBase, headSha, recordedShas)
}

private fun listTimeline(
  root: Path,
  mergeBase: String,
  headSha: String,
  recordedShas: List<String>,
): WorkCommitTimelineResult {
  val commits =
    when (val read = branchCommits(root, mergeBase, headSha)) {
      is TimelineStep.Value -> read.value
      is TimelineStep.Stop -> return read.result
    }
  val branchRead = scalar(root, listOf("symbolic-ref", "--quiet", "--short", "HEAD"), "HEAD is detached")
  val currentBranch =
    when (val read = branchRead) {
      is BaseRead.Resolved -> read.sha
      is BaseRead.Unavailable -> null
      is BaseRead.Failed -> return WorkCommitTimelineResult.Failed(read.message)
    }
  val timeline = WorkCommitTimelineResult.Listed(mergeBase, headSha, currentBranch, commits, emptySet())
  return reachableCommits(root, timeline, recordedShas)
}

private fun reachableCommits(
  root: Path,
  timeline: WorkCommitTimelineResult.Listed,
  recordedShas: List<String>,
): WorkCommitTimelineResult {
  val reachable = linkedSetOf<String>()
  val inTimeline = timeline.commits.mapTo(hashSetOf(), WorkBranchCommit::sha)
  val headSha = timeline.headSha
  for (sha in recordedShas.distinct()) {
    if (sha in inTimeline) {
      reachable += sha
      continue
    }
    val ancestor =
      scalar(root, listOf("merge-base", "--is-ancestor", sha, headSha), "$sha is not an ancestor of $headSha")
    when (val read = ancestor) {
      is BaseRead.Resolved -> reachable += sha
      is BaseRead.Unavailable -> Unit
      is BaseRead.Failed -> return WorkCommitTimelineResult.Failed(read.message)
    }
  }
  return timeline.copy(reachableRecordedShas = reachable)
}

private sealed interface TimelineStep {
  data class Value(val value: List<WorkBranchCommit>) : TimelineStep

  data class Stop(val result: WorkCommitTimelineResult) : TimelineStep
}

private fun branchCommits(
  root: Path,
  mergeBase: String,
  headSha: String,
): TimelineStep =
  captureList(
    root,
    BRANCH_LOG_OPTIONS + "$mergeBase..$headSha",
    { TimelineStep.Stop(WorkCommitTimelineResult.Failed(it)) },
  ) { text ->
    val commits =
      text.split(RECORD_SEPARATOR).filter(String::isNotBlank).map { record ->
        val fields = record.trim('\n').split(FIELD_SEPARATOR, limit = LOG_FIELDS)
        val sha = fields[0]
        val parent =
          fields.getOrNull(1)?.split(' ')?.firstOrNull(String::isNotBlank)
            ?: return@captureList TimelineStep.Stop(WorkCommitTimelineResult.Failed("commit $sha has no parent"))
        WorkBranchCommit(sha, parent, fields.getOrNull(2).orEmpty(), fields.getOrNull(MESSAGE_FIELD).orEmpty())
      }
    TimelineStep.Value(commits)
  }
