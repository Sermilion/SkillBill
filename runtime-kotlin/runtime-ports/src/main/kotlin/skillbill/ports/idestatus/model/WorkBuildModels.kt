package skillbill.ports.idestatus.model

import java.time.Instant

enum class WorkBuildState(val wireValue: String) {
  RUNNING("running"),
  PASSED("passed"),
  FAILED("failed"),
  FINISHED("finished"),
}

data class WorkBuildPrevious(
  val state: WorkBuildState,
  val durationMs: Long,
  val finishedAt: Instant,
) {
  init {
    require(state != WorkBuildState.RUNNING)
  }
}

data class WorkBuildView(
  val state: WorkBuildState,
  val command: String,
  val shortCommand: String,
  val tool: String,
  val projectKind: String,
  val pid: Long,
  val startedAt: Instant,
  val elapsedMs: Long?,
  val elapsedAsOf: Instant?,
  val usualMs: Long?,
  val exitCode: Int?,
  val finishedAt: Instant?,
  val previous: WorkBuildPrevious?,
  val sampleCount: Int? = null,
)
