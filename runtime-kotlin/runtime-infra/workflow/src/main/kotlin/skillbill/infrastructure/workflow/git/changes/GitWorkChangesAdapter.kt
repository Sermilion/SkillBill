package skillbill.infrastructure.workflow.git.changes

import me.tatarka.inject.annotations.Inject
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val NUL = '\u0000'

@Inject
class GitWorkChangesAdapter : WorkChangesPort {
  override fun changes(baseline: WorkChangeBaseline): WorkChangeListResult {
    val root = Path.of(baseline.worktreeRoot)
    listRepositoryProblem(root)?.let { return it }
    if (baseline is WorkChangeBaseline.CommittedBranchRange) {
      return committedBranchRange(root, baseline.targetBranch, baseline.headBranch)
    }
    return workingChanges(root, baseline)
  }

  private fun workingChanges(
    root: Path,
    baseline: WorkChangeBaseline,
  ): WorkChangeListResult {
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
          else -> patch(root, trackedPatchArgs(listOf(baseSha), file), SUCCESS_EXIT)
        }
    }
  }

  override fun committedChanges(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
  ): WorkChangeListResult {
    val root = Path.of(worktreeRoot)
    listRepositoryProblem(root)?.let { return it }
    return when (val missing = missingCommit(root, listOf(fromSha, toSha))) {
      is BaseRead.Unavailable -> WorkChangeListResult.BaseUnavailable(missing.reason)
      is BaseRead.Failed -> WorkChangeListResult.Failed(missing.message)
      else -> committedList(root, fromSha, toSha)
    }
  }

  private fun committedList(
    root: Path,
    fromSha: String,
    toSha: String,
  ): WorkChangeListResult {
    if (fromSha == toSha) return sumListed(fromSha, emptyList())
    return when (val read = trackedFiles(root, listOf(fromSha, toSha))) {
      is ListStep.Files -> sumListed(fromSha, read.files)
      is ListStep.Stop -> WorkChangeListResult.Failed(read.message)
    }
  }

  override fun committedFileDiff(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
    file: WorkChangedFile,
  ): WorkFileDiffResult {
    val root = Path.of(worktreeRoot)
    when (val missing = missingCommit(root, listOf(fromSha, toSha))) {
      is BaseRead.Unavailable -> return WorkFileDiffResult.BaseUnavailable(missing.reason)
      is BaseRead.Failed -> return WorkFileDiffResult.Failed(missing.message)
      else -> Unit
    }
    if (file.insertions == null && file.deletions == null) return WorkFileDiffResult.Binary
    return patch(root, trackedPatchArgs(listOf(fromSha, toSha), file), SUCCESS_EXIT)
  }

  override fun uncommittedChanges(worktreeRoot: String): WorkChangeListResult {
    val root = Path.of(worktreeRoot)
    listRepositoryProblem(root)?.let { return it }
    val head = scalar(root, listOf("rev-parse", "--verify", "HEAD^{commit}"), "repository has no commit")
    return when (head) {
      is BaseRead.Resolved -> changes(WorkChangeBaseline.SubtaskStart(worktreeRoot, head.sha, emptyList()))
      is BaseRead.Unavailable -> WorkChangeListResult.BaseUnavailable(head.reason)
      is BaseRead.Failed -> WorkChangeListResult.Failed(head.message)
    }
  }

  override fun commitTimeline(
    worktreeRoot: String,
    targetBranch: String?,
    recordedShas: List<String>,
    headBranch: String?,
  ): WorkCommitTimelineResult = readCommitTimeline(Path.of(worktreeRoot), targetBranch, recordedShas, headBranch)

  private fun resolveBase(
    root: Path,
    baseline: WorkChangeBaseline,
  ): BaseRead =
    when (baseline) {
      is WorkChangeBaseline.SubtaskStart -> BaseRead.Resolved(baseline.baseSha)
      is WorkChangeBaseline.RunStart -> BaseRead.Resolved(baseline.baseSha)
      is WorkChangeBaseline.TargetMergeBase -> mergeBase(root, baseline.targetBranch)
      is WorkChangeBaseline.CommittedBranchRange -> error("Committed branch ranges resolve separately.")
    }

  private fun listChanges(
    root: Path,
    base: String,
    excluded: Set<String>,
  ): WorkChangeListResult {
    val tracked =
      when (val read = trackedFiles(root, listOf(base))) {
        is ListStep.Files -> read.files
        is ListStep.Stop -> return WorkChangeListResult.Failed(read.message)
      }
    val untracked =
      when (val read = untrackedFiles(root, excluded)) {
        is ListStep.Files -> read.files
        is ListStep.Stop -> return WorkChangeListResult.Failed(read.message)
      }
    return sumListed(base, tracked + untracked)
  }

  private fun untrackedFiles(
    root: Path,
    excluded: Set<String>,
  ): ListStep =
    captureList(root, listOf("ls-files", "--others", "--exclude-standard", "-z"), ListStep::Stop) { text ->
      try {
        ListStep.Files(
          text.split(NUL).filter { it.isNotEmpty() && it !in excluded }.mapNotNull { untrackedChange(root, it) },
        )
      } catch (error: IOException) {
        ListStep.Stop("untracked file could not be read: ${error.message}")
      }
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

  private fun excludedUntracked(baseline: WorkChangeBaseline): Set<String> =
    when (baseline) {
      is WorkChangeBaseline.SubtaskStart -> baseline.excludedUntrackedPaths.toSet()
      is WorkChangeBaseline.RunStart -> baseline.excludedUntrackedPaths.toSet()
      is WorkChangeBaseline.TargetMergeBase -> emptySet()
      is WorkChangeBaseline.CommittedBranchRange -> emptySet()
    }

  private fun committedBranchRange(
    root: Path,
    targetBranch: String,
    headBranch: String,
  ): WorkChangeListResult {
    val headRef = "refs/heads/$headBranch"
    val head =
      scalar(
        root,
        listOf("rev-parse", "--verify", "--quiet", "$headRef^{commit}"),
        "goal branch $headBranch no longer exists",
      )
    if (head !is BaseRead.Resolved) {
      return when (head) {
        is BaseRead.Unavailable -> WorkChangeListResult.BaseUnavailable(head.reason)
        is BaseRead.Failed -> WorkChangeListResult.Failed(head.message)
        is BaseRead.Resolved -> error("Resolved branch was handled above.")
      }
    }
    val base =
      scalar(root, listOf("merge-base", targetBranch, headRef), "no merge-base between $targetBranch and $headBranch")
    return when (base) {
      is BaseRead.Resolved ->
        when (val listed = committedList(root, base.sha, headRef)) {
          is WorkChangeListResult.Listed -> listed.copy(baseSha = base.sha)
          else -> listed
        }
      is BaseRead.Unavailable -> WorkChangeListResult.BaseUnavailable(base.reason)
      is BaseRead.Failed -> WorkChangeListResult.Failed(base.message)
    }
  }
}
