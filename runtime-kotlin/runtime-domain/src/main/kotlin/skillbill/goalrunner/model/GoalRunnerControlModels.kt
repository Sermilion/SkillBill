package skillbill.goalrunner.model

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class GoalRunnerExecutionLease(
  val generation: Long,
  val ownerToken: String,
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
  val heartbeatAt: Instant,
  val expiresAt: Instant,
) {
  val heartbeatAtInstant: Instant get() = heartbeatAt
  val expiresAtInstant: Instant get() = expiresAt

  constructor(
    generation: Long,
    ownerToken: String,
    hostIdentity: String,
    bootIdentity: String,
    pid: Long,
    processBirthToken: String,
    heartbeatAt: String,
    expiresAt: String,
  ) : this(
    generation,
    ownerToken,
    hostIdentity,
    bootIdentity,
    pid,
    processBirthToken,
    parseExecutionLeaseInstant("heartbeat_at", heartbeatAt),
    parseExecutionLeaseInstant("expires_at", expiresAt),
  )

  init {
    require(generation > 0) { "execution lease generation must be positive." }
    require(ownerToken.isNotBlank()) { "execution lease ownerToken must not be blank." }
    require(hostIdentity.isNotBlank()) { "execution lease hostIdentity must not be blank." }
    require(bootIdentity.isNotBlank()) { "execution lease bootIdentity must not be blank." }
    require(pid > 0) { "execution lease pid must be positive." }
    require(processBirthToken.isNotBlank()) { "execution lease processBirthToken must not be blank." }
  }
}

fun parseExecutionLeaseInstant(
  field: String,
  value: String,
): Instant =
  try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    try {
      OffsetDateTime.parse(value).toInstant()
    } catch (error: DateTimeParseException) {
      throw InvalidWorkflowStateSchemaError(
        "Goal runner execution lease field '$field' must be an RFC 3339 instant.",
        error,
      )
    }
  }

const val GOAL_PAUSE_REASON_OPERATOR_REQUEST: String = "operator_request"

const val GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK: String = "stop_after_subtask"

const val GOAL_PAUSE_REASON_OPERATOR_STOP: String = "operator_stop"

const val GOAL_PAUSE_REASON_RUNNER_INTERRUPTED: String = "runner_interrupted"

const val GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS: Long = 20_000

data class GoalRunnerControlState(
  val stopAfterSubtaskId: Int? = null,
  val pauseRequested: Boolean = false,
  val pauseConsumed: Boolean = false,
  val paused: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterConsumed: Boolean = false,
  val repositoryIdentity: String? = null,
  val executionLease: GoalRunnerExecutionLease? = null,
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val currentSubtaskId: Int? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
  val validationQualityRetriesBySubtask: Map<Int, Int> = emptyMap(),
  val pendingReAttemptCauseBySubtask: Map<Int, String> = emptyMap(),
  val pendingCausingLoopEntryBySubtask: Map<Int, String> = emptyMap(),
) {
  init {
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    require(activeDurationMs >= 0) { "activeDurationMs must not be negative." }
    activeDurationAsOf?.let { require(it.isNotBlank()) { "activeDurationAsOf must not be blank when provided." } }
    currentSubtaskId?.let { require(it > 0) { "currentSubtaskId must be positive when provided." } }
    require(subtaskActiveDurationMs >= 0) { "subtaskActiveDurationMs must not be negative." }
    subtaskActiveDurationAsOf?.let {
      require(it.isNotBlank()) { "subtaskActiveDurationAsOf must not be blank when provided." }
    }
    require(!pauseConsumed || pauseRequested) {
      "pauseConsumed cannot be true when pauseRequested is false."
    }
    require(!stopAfterConsumed || stopAfterSubtaskId != null) {
      "stopAfterConsumed requires stopAfterSubtaskId."
    }
    pauseReason?.let { require(it.isNotBlank()) { "pauseReason must not be blank when provided." } }
    require(!paused || pauseReason != null) { "paused control state requires pauseReason." }
    pausedAt?.let { require(it.isNotBlank()) { "pausedAt must not be blank when provided." } }
    require(!paused || pausedAt != null) { "paused control state requires pausedAt." }
    repositoryIdentity?.let {
      require(it.isNotBlank()) { "repositoryIdentity must not be blank when provided." }
    }
  }

  fun reconciledForCurrentSubtask(manifestSubtaskId: Int): GoalRunnerControlState {
    if (manifestSubtaskId <= 0) return this
    if (currentSubtaskId == manifestSubtaskId) return this
    return copy(
      currentSubtaskId = manifestSubtaskId,
      subtaskActiveDurationMs = 0,
      subtaskActiveDurationAsOf = null,
    )
  }

  fun requiresPauseBoundary(manifest: DecompositionManifest): Boolean =
    pauseRequested || paused || (
      stopAfterSubtaskId != null &&
        !stopAfterConsumed &&
        manifest.subtasks.any {
          it.id == stopAfterSubtaskId && it.status.decompositionStatus() == DecompositionStatus.COMPLETE
        }
    )
}

fun GoalRunnerControlState.pauseAtOperatorBoundary(
  pausedAtNow: String,
  targetReached: Boolean = false,
): GoalRunnerControlState =
  when {
    paused -> copy(stopAfterConsumed = stopAfterConsumed || targetReached)
    pauseRequested ->
      copy(
        pauseConsumed = true,
        paused = true,
        pauseReason = pauseReason ?: GOAL_PAUSE_REASON_OPERATOR_REQUEST,
        pausedAt = pausedAtNow,
        stopAfterConsumed = stopAfterConsumed || targetReached,
      )
    targetReached ->
      copy(
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK,
        pausedAt = pausedAtNow,
        stopAfterConsumed = true,
      )
    else -> this
  }

fun GoalRunnerControlState.targetReached(manifest: DecompositionManifest): Boolean =
  stopAfterSubtaskId?.let { targetId ->
    manifest.subtasks.any {
      it.id == targetId && it.status.decompositionStatus() == DecompositionStatus.COMPLETE
    }
  } == true && !stopAfterConsumed
