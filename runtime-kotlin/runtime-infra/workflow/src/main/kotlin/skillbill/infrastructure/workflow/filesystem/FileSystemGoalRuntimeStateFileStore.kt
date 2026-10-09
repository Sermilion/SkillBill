package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Inject
class FileSystemGoalRuntimeStateFileStore : GoalRuntimeStateFileStore {
  override fun directoryExists(path: Path): Boolean = Files.exists(path)

  override fun deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion {
    if (!Files.exists(path)) return GoalRuntimeDirectoryDeletion.Absent
    return try {
      Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
      }
      GoalRuntimeDirectoryDeletion.Deleted
    } catch (failure: IOException) {
      GoalRuntimeDirectoryDeletion.Failed(failure.message ?: failure::class.simpleName.orEmpty())
    } catch (failure: UncheckedIOException) {
      GoalRuntimeDirectoryDeletion.Failed(failure.message ?: failure::class.simpleName.orEmpty())
    }
  }
}
