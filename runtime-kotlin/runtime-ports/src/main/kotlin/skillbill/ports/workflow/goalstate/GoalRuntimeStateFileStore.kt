package skillbill.ports.workflow.goalstate

import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.nio.file.Path

/**
 * Port for removing repo-local runtime state a goal owns (`.skill-bill/feature-task-tracking/<id>/` and
 * `.skill-bill/run-evidence/<id>/`). Deletion is idempotent: an absent directory reports
 * [GoalRuntimeDirectoryDeletion.Absent].
 */
interface GoalRuntimeStateFileStore {
  fun directoryExists(path: Path): Boolean

  /** Recursively deletes [path]; an I/O failure is reported as [GoalRuntimeDirectoryDeletion.Failed], never thrown. */
  fun deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion
}
