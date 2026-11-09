package skillbill.ports.work.model

import java.nio.file.Path
import java.util.concurrent.CompletionStage

data class WorkRunLaunchRequest(
  val repositoryRoot: Path,
  val databasePath: Path,
  val ownershipKey: String,
  val kind: WorkRunLaunchKind,
  val agentEnvironment: Map<String, String> = emptyMap(),
  val operationLaunchId: String? = null,
  val pinnedAgentHomes: Map<String, String>? = null,
)

sealed interface WorkRunLaunchKind {
  data class RuntimeCommand(val arguments: List<String>) : WorkRunLaunchKind
}

data class WorkRunExit(val code: Int, val failureLogLines: List<String>)

sealed interface WorkRunLaunchResult {
  /** [exit] completes when the runner exits, or is null when the launcher cannot observe that. */
  data class Started(val logPath: Path, val exit: CompletionStage<WorkRunExit>? = null) : WorkRunLaunchResult

  /** The runner exited with the awaiting-confirmation code before the startup grace ended; its card is published. */
  data class AwaitingConfirmation(val logPath: Path) : WorkRunLaunchResult

  data class Refused(val reason: String) : WorkRunLaunchResult
}

data class WorkRunStopRequest(val databasePath: Path, val ownershipKey: String)

sealed interface WorkRunStopResult {
  data object Stopped : WorkRunStopResult

  data object NotRunning : WorkRunStopResult

  data class Refused(val reason: String) : WorkRunStopResult
}
