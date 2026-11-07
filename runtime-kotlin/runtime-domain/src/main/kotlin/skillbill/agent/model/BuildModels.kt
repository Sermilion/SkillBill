package skillbill.agent.model

import java.time.Instant

data class BuildProcessSample(
  val pid: Long,
  val parentPid: Long?,
  val command: String?,
  val arguments: List<String>,
  val startedAt: Instant?,
)

/**
 * Read-only access to files the build matcher inspects for project markers and tool versions.
 * Implementations live outside the domain and never write. Each path is an absolute host path
 * the domain composes as text from [BuildMatchContext]. [readText] returns null when the file is
 * absent or unreadable.
 */
interface BuildRepositoryFiles {
  fun exists(path: String): Boolean

  fun readText(path: String): String?
}

data class BuildMatchContext(
  val repositoryRoot: String,
  val xcodeDeveloperDirectory: String?,
)

data class BuildMatch(
  val pid: Long,
  val startedAt: Instant?,
  val executable: String,
  val displayExecutable: String,
  val displayCommand: String,
  val shortCommand: String,
  val toolLabel: String,
  val projectKind: String,
)

enum class BuildCommandEventKind { STARTED, COMPLETED, FAILED }

data class BuildCommandEvent(
  val callId: String,
  val commandText: String,
  val kind: BuildCommandEventKind,
  val exitCode: Int?,
  val observedAt: Instant,
)

enum class BuildOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  UNKNOWN("unknown"),
}

/** Build lifecycle events the tracker emits for the recorder to persist. */
sealed interface BuildTrackerEvent {
  val pid: Long

  data class Started(
    override val pid: Long,
    val displayCommand: String,
    val shortCommand: String,
    val toolLabel: String,
    val projectKind: String,
    val executable: String,
    val displayExecutable: String,
    val startedAt: Instant,
  ) : BuildTrackerEvent

  data class Settled(
    override val pid: Long,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMs: Long,
    val outcome: BuildOutcome,
    val exitCode: Int?,
  ) : BuildTrackerEvent
}
