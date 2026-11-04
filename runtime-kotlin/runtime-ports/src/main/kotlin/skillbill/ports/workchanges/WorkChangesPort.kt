package skillbill.ports.workchanges

import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkFileDiffResult

/**
 * Read-only git view of the files an execution changed since its baseline.
 *
 * Every read blocks on git and must run off the UI thread and outside any database read.
 * Every failure is returned as a typed result, never thrown.
 */
interface WorkChangesPort {
  /** Lists tracked changes from the resolved base to the working tree, plus untracked files. */
  fun changes(baseline: WorkChangeBaseline): WorkChangeListResult

  /** Returns git's unified diff for one file listed by [changes] against the same [baseSha]. */
  fun fileDiff(
    worktreeRoot: String,
    baseSha: String,
    file: WorkChangedFile,
  ): WorkFileDiffResult

  /**
   * Lists tracked changes between two commits, never untracked files, with [fromSha] as the listed base.
   * Blocks on git, never throws, and returns a typed result.
   */
  fun committedChanges(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
  ): WorkChangeListResult

  /**
   * Returns git's unified diff between two commits for one file listed by [committedChanges].
   * Blocks on git, never throws, and returns a typed result.
   */
  fun committedFileDiff(
    worktreeRoot: String,
    fromSha: String,
    toSha: String,
    file: WorkChangedFile,
  ): WorkFileDiffResult

  /**
   * Lists staged, unstaged and untracked changes against HEAD; [fileDiff] with the listed base serves patches.
   * Blocks on git, never throws, and returns a typed result.
   */
  fun uncommittedChanges(worktreeRoot: String): WorkChangeListResult

  /**
   * Lists the branch commit timeline since the merge base with [targetBranch].
   * Blocks on git, never throws, and returns a typed result.
   */
  fun commitTimeline(
    worktreeRoot: String,
    targetBranch: String?,
    recordedShas: List<String>,
    headBranch: String? = null,
  ): WorkCommitTimelineResult
}
