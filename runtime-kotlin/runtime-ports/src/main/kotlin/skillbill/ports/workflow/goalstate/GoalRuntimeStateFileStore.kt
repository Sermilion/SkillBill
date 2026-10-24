package skillbill.ports.workflow.goalstate

import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.nio.file.Path

/**
 * Port for removing project-store runtime state a goal owns (`<store>/feature-task-tracking/<id>/` and
 * `<store>/run-evidence/<id>/`). Deletion is idempotent: an absent directory reports
 * [GoalRuntimeDirectoryDeletion.Absent].
 */
interface GoalRuntimeStateFileStore {
  fun directoryExists(path: Path): Boolean

  /** Recursively deletes [path]; an I/O failure is reported as [GoalRuntimeDirectoryDeletion.Failed], never thrown. */
  fun deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion
}
