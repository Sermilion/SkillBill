package skillbill.ports.workchanges

import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkFileDiffResult

/**
 * Read-only git view of the files an execution changed since its baseline.
 *
 * Both reads block on git and must run off the UI thread and outside any database read.
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
}
