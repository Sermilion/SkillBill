package skillbill.infrastructure.launcher.process.waitloop

import skillbill.goalrunner.model.GoalRunnerLivenessClassifier
import skillbill.goalrunner.model.GoalRunnerLivenessDecision
import skillbill.goalrunner.model.GoalRunnerLivenessInputs
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.MIN_TIMEOUT_MILLIS
import skillbill.infrastructure.launcher.process.launch.MIN_TIMEOUT_NANOS
import skillbill.infrastructure.launcher.process.launch.OutputObservationTracker
import skillbill.infrastructure.launcher.process.launch.ProcessRunDegradationRecorder
import skillbill.infrastructure.launcher.process.launch.parseWorkflowIdAndStep
import skillbill.infrastructure.launcher.process.launch.toIsoUtc
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit

internal fun writeAndCloseStdin(
  process: Process,
  stdinText: String?,
  degradation: ProcessRunDegradationRecorder,
) {
  runCatching {
    process.outputStream.use { output ->
      if (stdinText != null) {
        output.write(stdinText.toByteArray(StandardCharsets.UTF_8))
      }
    }
  }.onFailure { failure -> degradation.recordStdinDeliveryFailure(failure) }
}

internal data class ProcessWait(
  val finished: Boolean,
  val progressIdleTimedOut: Boolean,
  val fileActivityGraceExhausted: Boolean,
  val wallClockTimedOut: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

internal class ProcessWaitLoop(
  internal val process: Process,
  internal val request: AgentRunProcessRequest,
  internal val outputTracker: OutputObservationTracker,
  internal val lifecycleEmitter: ProcessLifecycleEmitter,
  internal val clock: Clock,
  internal val degradation: ProcessRunDegradationRecorder,
) {
  internal val timeoutMillis =
    request.timing.timeout
      ?.toLong(DurationUnit.MILLISECONDS)
      ?.coerceAtLeast(MIN_TIMEOUT_MILLIS)
  internal val idleTimeoutNanos =
    request.timing.progressIdleTimeout
      ?.toLong(DurationUnit.NANOSECONDS)
      ?.coerceAtLeast(MIN_TIMEOUT_NANOS)
  internal val fileActivityGraceNanos =
    request.timing.fileActivityGraceTimeout
      .toLong(DurationUnit.NANOSECONDS)
      .coerceAtLeast(MIN_TIMEOUT_NANOS)
  internal val statusHeartbeatNanos =
    request.timing.statusHeartbeatInterval
      .toLong(DurationUnit.NANOSECONDS)
      .coerceAtLeast(MIN_TIMEOUT_NANOS)
  internal val operationDeadlineNanos =
    request.timing.operationDeadline
      ?.toLong(DurationUnit.NANOSECONDS)
      ?.coerceAtLeast(MIN_TIMEOUT_NANOS)
  internal val startNanos = System.nanoTime()
  internal var lastWorkflowProgressNanos = startNanos
  internal var lastStatusHeartbeatNanos = startNanos
  internal var lastLiveHeartbeatNanos = startNanos
  internal var lastProgressToken = request.probes.progressProbe.readProgressToken(degradation).value
  internal var lastActivityToken = request.probes.activityProbe.readActivityToken(degradation).value
  internal var fileActivityWindowStartNanos: Long? = null
  internal var lastProgressLabel: String? = request.probes.progressProbe.readProgressLabel(degradation).value
  internal var lastProgressInstant: Instant? = null
  internal var lastSnapshotInstant: Instant? = null
  internal var lastActivityLabel: String? = null
  internal var lastActivityInstant: Instant? = null
  internal var lastObservedOutputMillis: Long? = null
  internal var lastOutputNanos: Long? = null
  internal var declaredTracker = DeclaredProgressTracker(startNanos)

  fun wait(): ProcessWait {
    var wait: ProcessWait? = null
    while (wait == null) {
      if (Thread.interrupted()) {
        throw InterruptedException()
      }
      wait = nextWait()
    }
    return wait
  }

  private fun nextWait(): ProcessWait? {
    if (request.review.reviewEvidenceBroker?.terminalOutcome() != null) {
      return ProcessWait(
        finished = false,
        progressIdleTimedOut = false,
        fileActivityGraceExhausted = false,
        wallClockTimedOut = false,
        liveness =
          declaredLiveness(
            "review_budget",
            "review_context_budget_exceeded",
            GoalRunnerProcessState.KILLED,
            killLivenessState(),
          ),
      )
    }
    val waitMillis =
      waitMillisBeforeNextPoll() ?: return ProcessWait(
        finished = false,
        progressIdleTimedOut = false,
        fileActivityGraceExhausted = false,
        wallClockTimedOut = true,
        liveness =
          declaredLiveness(
            "watchdog",
            "wall_clock_timeout",
            GoalRunnerProcessState.KILLED,
            killLivenessState(),
          ),
      )
    return when {
      process.waitFor(waitMillis, TimeUnit.MILLISECONDS) ->
        ProcessWait(
          finished = true,
          progressIdleTimedOut = false,
          fileActivityGraceExhausted = false,
          wallClockTimedOut = false,
          liveness = liveness("watchdog", "process_exited", GoalRunnerProcessState.EXITED),
        )
      else -> pollProgress()
    }
  }

  private fun liveness(
    phase: String,
    reason: String,
    processState: GoalRunnerProcessState,
  ): AgentRunLivenessSnapshot = declaredLiveness(phase, reason, processState, livenessState = null)

  private fun killLivenessState(): GoalRunnerLivenessState {
    if (declaredTracker.activeOperationName != null) return GoalRunnerLivenessState.WORKING
    val timeout = idleTimeoutNanos ?: return GoalRunnerLivenessState.IDLE
    val lastOutput = lastOutputNanos ?: return GoalRunnerLivenessState.IDLE
    return if (System.nanoTime() - lastOutput < timeout) {
      GoalRunnerLivenessState.PROGRESSING
    } else {
      GoalRunnerLivenessState.IDLE
    }
  }

  internal fun declaredLiveness(
    phase: String,
    reason: String,
    processState: GoalRunnerProcessState,
    livenessState: GoalRunnerLivenessState?,
  ): AgentRunLivenessSnapshot {
    val declared = declaredTracker.latestEvent
    val (parsedWorkflowId, parsedWorkflowStep) = parseWorkflowIdAndStep(lastProgressLabel)
    return AgentRunLivenessSnapshot(
      phase = phase,
      reason = reason,
      processState = processState,
      workflowId = declared?.workflowId ?: parsedWorkflowId,
      workflowStep = declared?.let { it.stepId ?: it.workflowPhase } ?: parsedWorkflowStep,
      lastDurableProgressAt = declared?.timestamp?.toIsoUtc() ?: lastProgressInstant?.toIsoUtc(),
      lastDurableProgressLabel = lastProgressLabel?.takeIf(String::isNotBlank),
      lastWorkflowSnapshotAt = lastSnapshotInstant?.toIsoUtc(),
      lastFileActivityAt = lastActivityInstant?.toIsoUtc(),
      lastFileActivityLabel = lastActivityLabel?.takeIf(String::isNotBlank),
      lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
      livenessState = livenessState,
      activeOperationName = declaredTracker.activeOperationName,
      activeOperationKind = declaredTracker.activeOperationKind,
      activeOperationExpectedLong = declaredTracker.activeOperationExpectedLong,
    )
  }
}

internal class DeclaredProgressTracker(startNanos: Long) {
  var latestEvent: GoalProgressEvent? = null
    private set
  private var processAlive: Boolean = true
  private var operationActive: Boolean = false
  var activeOperationName: String? = null
    private set
  var activeOperationKind: String? = null
    private set
  var activeOperationExpectedLong: Boolean = false
    private set
  private var operationStartedNanos: Long = startNanos
  var lastAdvanceNanos: Long = startNanos
    private set
  private var lastSequenceNumber: Int = Int.MIN_VALUE
  val hasDeclaredEvent: Boolean get() = latestEvent != null

  fun observe(
    snapshot: AgentRunDeclaredProgressSnapshot,
    nowNanos: Long,
  ) {
    val event = snapshot.latestEvent
    processAlive = snapshot.processAlive
    if (event.sequenceNumber <= lastSequenceNumber && latestEvent != null) {
      return
    }
    lastSequenceNumber = event.sequenceNumber
    latestEvent = event
    lastAdvanceNanos = nowNanos
    when (event.eventKind) {
      GoalProgressEventKind.OPERATION_STARTED, GoalProgressEventKind.OPERATION_HEARTBEAT -> {
        val wasActive = operationActive
        val sameOperation = activeOperationName == event.operationName
        operationActive = true
        activeOperationName = event.operationName
        activeOperationKind = event.operationKind
        activeOperationExpectedLong = event.expectedLong
        if (!wasActive || !sameOperation || event.eventKind == GoalProgressEventKind.OPERATION_STARTED) {
          operationStartedNanos = nowNanos
        }
      }
      GoalProgressEventKind.OPERATION_COMPLETED -> {
        operationActive = false
        activeOperationName = null
        activeOperationKind = null
        activeOperationExpectedLong = false
      }
      GoalProgressEventKind.PHASE_STARTED, GoalProgressEventKind.PHASE_COMPLETED -> Unit
    }
  }

  fun classify(
    nowNanos: Long,
    operationDeadlineNanos: Long?,
    idleTimeoutNanos: Long?,
  ): GoalRunnerLivenessDecision {
    val deadlineOverrun =
      operationDeadlineNanos != null &&
        operationActive &&
        (nowNanos - operationStartedNanos) >= operationDeadlineNanos
    val durableAdvanceWithinInterval =
      idleTimeoutNanos?.let { window ->
        nowNanos - lastAdvanceNanos < window
      } ?: true
    return GoalRunnerLivenessClassifier.classify(
      GoalRunnerLivenessInputs(
        processAlive = processAlive,
        operationActive = operationActive,
        operationExpectedLong = activeOperationExpectedLong,
        durableAdvanceWithinInterval = durableAdvanceWithinInterval,
        operationDeadlineOverrun = deadlineOverrun,
        wallClockCapExceeded = false,
      ),
    )
  }
}

internal class ProcessLifecycleEmitter(
  private val request: AgentRunProcessRequest,
  private val degradation: ProcessRunDegradationRecorder,
) {
  private var started = false
  private var completed = false

  fun emitStarted(processAlive: Boolean) {
    if (started) {
      return
    }
    started = true
    emit(GoalProgressEventKind.OPERATION_STARTED, processAlive, GoalProgressOutcome.NONE)
  }

  fun emitHeartbeat(processAlive: Boolean) {
    if (!started || completed) {
      return
    }
    emit(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive, GoalProgressOutcome.NONE)
  }

  fun emitCompleted(
    processAlive: Boolean,
    outcome: GoalProgressOutcome,
  ) {
    if (completed || !started) {
      return
    }
    completed = true
    emit(GoalProgressEventKind.OPERATION_COMPLETED, processAlive, outcome)
  }

  private fun emit(
    kind: GoalProgressEventKind,
    processAlive: Boolean,
    outcome: GoalProgressOutcome,
  ) {
    runCatching {
      request.probes.progressEmitter.emit(
        AgentRunProgressEmission(
          eventKind = kind,
          processAlive = processAlive,
          operationName = CHILD_OPERATION_NAME,
          operationKind = CHILD_OPERATION_KIND,
          expectedLong = true,
          outcome = outcome,
          authoritative = false,
        ),
      )
    }.onFailure { failure -> degradation.recordLifecyclePublicationFailure(failure) }
  }

  private companion object {
    const val CHILD_OPERATION_NAME = "child_agent_run"
    const val CHILD_OPERATION_KIND = "long_child_run"
  }
}
