package skillbill.ports.workflow.goalstate

import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class LocalGoalRuntimeStateFileStore(
  private val failingPaths: Set<Path> = emptySet(),
) : GoalRuntimeStateFileStore {
  override fun directoryExists(path: Path): Boolean = Files.exists(path)

  override fun deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion {
    if (!Files.exists(path)) return GoalRuntimeDirectoryDeletion.Absent
    if (path in failingPaths) return GoalRuntimeDirectoryDeletion.Failed("simulated delete failure")
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    return GoalRuntimeDirectoryDeletion.Deleted
  }
}
