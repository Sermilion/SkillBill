package skillbill.ports.workflow.goalstate

import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.nio.file.Path

object UnavailableGoalRuntimeStateFileStore : GoalRuntimeStateFileStore {
  override fun directoryExists(path: Path): Boolean = unavailable()

  override fun deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion = unavailable()

  private fun unavailable(): Nothing {
    error("Goal runtime state file store is not configured for this runtime.")
  }
}
