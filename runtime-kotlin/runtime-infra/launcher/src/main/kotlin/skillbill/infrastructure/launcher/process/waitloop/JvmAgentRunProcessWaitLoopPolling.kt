package skillbill.infrastructure.launcher.process.waitloop

import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.infrastructure.launcher.process.launch.AgentRunIdleSignals
import skillbill.infrastructure.launcher.process.launch.PROGRESS_POLL_INTERVAL_MILLIS
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal fun ProcessWaitLoop.elapsedMillis(): Long =
  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallClockOriginNanos)

internal fun ProcessWaitLoop.resetWallClock(nowNanos: Long) {
  wallClockOriginNanos = nowNanos
  declaredTracker.restartOperationClock(nowNanos)
}

internal fun ProcessWaitLoop.waitMillisBeforeNextPoll(): Long? {
  val configuredTimeoutMillis = timeoutMillis ?: return PROGRESS_POLL_INTERVAL_MILLIS
  val remainingMillis = configuredTimeoutMillis - elapsedMillis()
  return if (remainingMillis <= 0) {
    null
  } else {
    min(PROGRESS_POLL_INTERVAL_MILLIS, remainingMillis)
  }
}

internal fun ProcessWaitLoop.pollProgress(): ProcessWait? {
  val nowNanos = System.nanoTime()
  pollDeclaredProgress(nowNanos)
  pollWorkflowProgress(nowNanos)
  pollFileActivity(nowNanos)
  pollOutputActivity(nowNanos)
  pollStatusHeartbeat(nowNanos)
  return if (declaredTracker.hasDeclaredEvent) {
    declaredProgressWait(nowNanos)
  } else {
    legacyIdleWait(nowNanos)
  }
}

internal fun ProcessWaitLoop.pollDeclaredProgress(nowNanos: Long) {
  val read = request.probes.declaredProgressProbe.readDeclaredProgress(degradation)
  if (read.failed) return
  val snapshot = read.value ?: return
  val previousSequence = declaredTracker.latestEvent?.sequenceNumber
  val phaseTransitioned = declaredTracker.observe(snapshot, nowNanos)
  if (phaseTransitioned) {
    resetWallClock(nowNanos)
  }
  val latest = declaredTracker.latestEvent
  if (latest != null && latest.sequenceNumber != previousSequence) {
    request.probes.activityStampSink.safeStamp(AgentActivityLabel.TOOL_STREAM)
  }
}

internal fun ProcessWaitLoop.declaredProgressWait(nowNanos: Long): ProcessWait? {
  val decision = declaredTracker.classify(nowNanos, operationDeadlineNanos, idleTimeoutNanos)
  return when (decision.state) {
    GoalRunnerLivenessState.UNRESPONSIVE ->
      ProcessWait(
        finished = false,
        progressIdleTimedOut = true,
        fileActivityGraceExhausted = false,
        wallClockTimedOut = false,
        liveness =
          declaredLiveness(
            "watchdog",
            "operation_deadline_overrun",
            GoalRunnerProcessState.KILLED,
            decision.state,
          ),
      )
    GoalRunnerLivenessState.IDLE ->
      if (idleTimeoutNanos != null && nowNanos - declaredTracker.lastAdvanceNanos >= idleTimeoutNanos) {
        val processLiveWithinWindow =
          request.probes.idlePolicy.extendIdleWindow(idleSignals(idleTimeoutNanos, nowNanos))
        if (processLiveWithinWindow) {
          null
        } else {
          ProcessWait(
            finished = false,
            progressIdleTimedOut = true,
            fileActivityGraceExhausted = false,
            wallClockTimedOut = false,
            liveness =
              declaredLiveness(
                "watchdog",
                "progress_idle_timeout",
                GoalRunnerProcessState.KILLED,
                decision.state,
              ),
          )
        }
      } else {
        null
      }
    GoalRunnerLivenessState.WORKING, GoalRunnerLivenessState.PROGRESSING -> null
  }
}

internal fun ProcessWaitLoop.legacyIdleWait(nowNanos: Long): ProcessWait? =
  if (idleTimeoutNanos != null && nowNanos - lastWorkflowProgressNanos >= idleTimeoutNanos) {
    val graceActive =
      fileActivityWindowStartNanos?.let { windowStart ->
        nowNanos - windowStart < fileActivityGraceNanos
      } == true
    val processLiveWithinWindow =
      request.probes.idlePolicy.extendIdleWindow(idleSignals(idleTimeoutNanos, nowNanos))
    if (graceActive || processLiveWithinWindow) {
      null
    } else {
      ProcessWait(
        finished = false,
        progressIdleTimedOut = true,
        fileActivityGraceExhausted = fileActivityWindowStartNanos != null,
        wallClockTimedOut = false,
        liveness =
          declaredLiveness(
            "watchdog",
            "progress_idle_timeout",
            GoalRunnerProcessState.KILLED,
            GoalRunnerLivenessState.IDLE,
          ),
      )
    }
  } else {
    null
  }

internal fun ProcessWaitLoop.idleSignals(
  idleTimeoutNanos: Long,
  nowNanos: Long,
): AgentRunIdleSignals =
  AgentRunIdleSignals(
    lastLiveHeartbeatNanos = lastLiveHeartbeatNanos,
    lastOutputNanos = lastOutputNanos,
    idleTimeoutNanos = idleTimeoutNanos,
    nowNanos = nowNanos,
  )
