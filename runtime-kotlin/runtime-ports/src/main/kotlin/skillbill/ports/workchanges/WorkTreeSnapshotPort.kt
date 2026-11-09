package skillbill.ports.workchanges

import skillbill.ports.workchanges.model.WorkTreeComparisonResult
import skillbill.ports.workchanges.model.WorkTreeSnapshotResult

/**
 * Captures a working tree baseline with blocking git calls and never throws.
 * `git stash create` writes an unreferenced commit and creates no ref, so capture
 * lives outside the read-only [WorkChangesPort].
 */
interface WorkTreeSnapshotPort {
  fun capture(worktreeRoot: String): WorkTreeSnapshotResult

  /**
   * Incorporates integrated remote default-branch changes into an existing baseline.
   * Writes only unreferenced git objects, preserving the index, working tree and refs.
   * Returns a typed failure when the baseline cannot be combined without conflicts.
   */
  fun comparisonBaseline(
    worktreeRoot: String,
    headSha: String,
    snapshotSha: String?,
  ): WorkTreeComparisonResult
}
