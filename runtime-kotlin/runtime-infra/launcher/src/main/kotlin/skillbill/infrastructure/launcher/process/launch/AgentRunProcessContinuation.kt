package skillbill.infrastructure.launcher.process.launch

import java.time.Clock
import java.time.Duration.between
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

internal fun runAgentRunContinuations(
  request: AgentRunProcessRequest,
  clock: Clock,
  run: (AgentRunProcessRequest) -> AgentRunProcessResult,
): AgentRunProcessResult {
  val startedAt = clock.instant()
  var current = request
  while (true) {
    val result = run(current)
    if (!result.canContinue()) return result
    val next = request.launch.continuation?.invoke(result) ?: return result
    val elapsed = between(startedAt, clock.instant()).toKotlinDuration().coerceAtLeast(Duration.ZERO)
    val timeout = request.timing.timeout?.minus(elapsed)
    val deadline = request.timing.operationDeadline?.minus(elapsed)
    if (timeout?.isPositive() == false || deadline?.isPositive() == false) {
      return result.copy(exitStatus = null, timedOut = true)
    }
    current =
      request.copy(
        launch = next.copy(outputSink = request.launch.outputSink, continuation = request.launch.continuation),
        timing = request.timing.copy(timeout = timeout, operationDeadline = deadline),
      )
  }
}

private fun AgentRunProcessResult.canContinue(): Boolean =
  when {
    timedOut || interrupted -> false
    spawnFailed || spawnDenied != null -> false
    outputCaptureIncomplete || stdoutTruncated -> false
    else -> exitStatus != null && exitStatus != 0
  }
