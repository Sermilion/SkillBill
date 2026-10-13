package skillbill.ports.work.model

import java.nio.file.Path

data class WorkRunLaunchRequest(
  val repositoryRoot: Path,
  val databasePath: Path,
  val agentId: String,
  val prompt: String,
  val ownershipKey: String,
)

sealed interface WorkRunLaunchResult {
  data class Started(val logPath: Path) : WorkRunLaunchResult

  data class Refused(val reason: String) : WorkRunLaunchResult
}

data class WorkRunStopRequest(val databasePath: Path, val ownershipKey: String)

sealed interface WorkRunStopResult {
  data object Stopped : WorkRunStopResult

  data object NotRunning : WorkRunStopResult

  data class Refused(val reason: String) : WorkRunStopResult
}
