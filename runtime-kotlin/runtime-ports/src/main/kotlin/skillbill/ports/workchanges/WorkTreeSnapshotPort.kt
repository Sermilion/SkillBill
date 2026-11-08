package skillbill.ports.workchanges

import skillbill.ports.workchanges.model.WorkTreeSnapshotResult

/**
 * Captures a working tree baseline with blocking git calls and never throws.
 * `git stash create` writes an unreferenced commit and creates no ref, so capture
 * lives outside the read-only [WorkChangesPort].
 */
interface WorkTreeSnapshotPort {
  fun capture(worktreeRoot: String): WorkTreeSnapshotResult
}
