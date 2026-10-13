package skillbill.ports.work.model

import java.nio.file.Path

data class WorkRunLaunchRequest(
  val repositoryRoot: Path,
  val databasePath: Path,
  val arguments: List<String>,
  val ownershipKey: String,
)

sealed interface WorkRunLaunchResult {
  data class Started(val logPath: Path) : WorkRunLaunchResult

  data class Refused(val reason: String) : WorkRunLaunchResult
}
