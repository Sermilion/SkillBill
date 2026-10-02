package skillbill.infrastructure.launcher.process.launch

import skillbill.infrastructure.launcher.process.support.launcherSha256Hex
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun interface AgentRunActivityProbe {
  fun activityToken(): String?

  fun activityLabel(): String? = null

  companion object {
    val NONE: AgentRunActivityProbe = AgentRunActivityProbe { null }
  }
}

data class AgentRunIdleSignals(
  val lastLiveHeartbeatNanos: Long,
  val lastOutputNanos: Long?,
  val idleTimeoutNanos: Long,
  val nowNanos: Long,
)

fun interface AgentRunIdlePolicy {
  fun extendIdleWindow(signals: AgentRunIdleSignals): Boolean

  companion object {
    val HEARTBEAT_EXTENDED: AgentRunIdlePolicy =
      AgentRunIdlePolicy { signals ->
        signals.nowNanos - signals.lastLiveHeartbeatNanos < signals.idleTimeoutNanos
      }
    val DB_PROGRESS_ONLY: AgentRunIdlePolicy = AgentRunIdlePolicy { false }

    val OUTPUT_EXTENDED: AgentRunIdlePolicy =
      AgentRunIdlePolicy { signals ->
        signals.lastOutputNanos?.let { observed -> signals.nowNanos - observed < signals.idleTimeoutNanos } == true
      }
  }
}

internal val DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT: Duration = 2.minutes
internal val DEFAULT_STATUS_HEARTBEAT_INTERVAL: Duration = 90.seconds

data class AgentRunProcessResult(
  val exitStatus: Int?,
  val stdout: String,
  val stdoutBytes: ByteArray = stdout.encodeToByteArray(),
  val stderr: String,
  val timedOut: Boolean,
  val interrupted: Boolean,
  val spawnFailed: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
  val processStarted: Boolean = !spawnFailed,
  val mcpStartupObserved: Boolean = false,
  val stdoutTruncated: Boolean = false,
  val stdoutByteSize: Long = stdoutBytes.size.toLong(),
  val stdoutSha256: String = launcherSha256Hex(stdoutBytes),
  val outputCaptureIncomplete: Boolean = false,
  val spawnDenied: AgentRunSpawnAuthorizationResult.Denied? = null,
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
