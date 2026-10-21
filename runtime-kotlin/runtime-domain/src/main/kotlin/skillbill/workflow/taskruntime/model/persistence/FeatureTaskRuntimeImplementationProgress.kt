package skillbill.workflow.taskruntime.model.persistence

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

data class FeatureTaskRuntimeImplementationProgress(
  val criterionIds: List<String>,
  val currentIndex: Int,
  val workerCount: Int,
  val launchCount: Int = 0,
) {
  init {
    val reason = violation(criterionIds, currentIndex, workerCount, launchCount)
    require(reason == null) { reason.orEmpty() }
  }

  val closedIds: List<String> get() = criterionIds.take(currentIndex)

  val complete: Boolean get() = currentIndex == criterionIds.size

  val currentId: String? get() = criterionIds.getOrNull(currentIndex)

  fun reserveWorker(): FeatureTaskRuntimeImplementationProgress? =
    takeIf { !complete && workerCount < MAX_WORKERS_PER_CRITERION }?.copy(workerCount = workerCount + 1)

  fun launched(): FeatureTaskRuntimeImplementationProgress = copy(launchCount = launchCount + 1)

  fun closeCurrent(): FeatureTaskRuntimeImplementationProgress? =
    takeIf { !complete && workerCount > 0 }?.copy(currentIndex = currentIndex + 1, workerCount = 0)

  fun reopenCurrent(): FeatureTaskRuntimeImplementationProgress? =
    takeIf { !complete && workerCount > 0 }?.copy(workerCount = 0)

  companion object {
    const val MAX_WORKERS_PER_CRITERION: Int = 3

    fun initial(criterionIds: List<String>): FeatureTaskRuntimeImplementationProgress =
      FeatureTaskRuntimeImplementationProgress(criterionIds, 0, 0)

    fun violation(
      criterionIds: List<String>,
      currentIndex: Int,
      workerCount: Int,
      launchCount: Int,
    ): String? =
      when {
        criterionIds.isEmpty() -> "criterion_ids must name at least one criterion."
        criterionIds.any { !CRITERION_ID.matches(it) } -> "criterion_ids must contain canonical AC-NNN ids."
        criterionIds.distinct().size != criterionIds.size -> "criterion_ids must be unique."
        currentIndex !in 0..criterionIds.size -> "current_index must lie between 0 and the criterion count."
        workerCount !in 0..MAX_WORKERS_PER_CRITERION ->
          "worker_count must lie between 0 and $MAX_WORKERS_PER_CRITERION."
        currentIndex == criterionIds.size && workerCount != 0 -> "worker_count must be 0 once every criterion closed."
        launchCount < 0 -> "launch_count must not be negative."
        else -> null
      }

    private val CRITERION_ID = Regex("^AC-[0-9]{3}$")
  }
}

enum class FeatureTaskRuntimeImplementationTurnRole(val wireValue: String) {
  WORKER_LAUNCH("worker-launch"),
  CHECK_LAUNCH("check-launch"),
  WORKER_SETTLED("worker-settled"),
  CHECK_SETTLED("check-settled"),
  REOPEN("reopen"),
  ;

  val launch: Boolean get() = this == WORKER_LAUNCH || this == CHECK_LAUNCH
}

enum class FeatureTaskRuntimeImplementationCheckDecision(val wireValue: String) {
  SATISFIED("satisfied"),
  UNSATISFIED("unsatisfied"),
  ;

  companion object {
    fun fromWireValue(value: String?): FeatureTaskRuntimeImplementationCheckDecision? =
      entries.firstOrNull { it.wireValue == value }
  }
}

data class FeatureTaskRuntimeImplementationTurnMarker(
  val role: FeatureTaskRuntimeImplementationTurnRole,
  val criterionId: String,
  val worker: Int,
  val decision: FeatureTaskRuntimeImplementationCheckDecision? = null,
  val retryIdentity: String? = null,
) {
  init {
    require(worker in 0..FeatureTaskRuntimeImplementationProgress.MAX_WORKERS_PER_CRITERION) {
      "Implementation turn worker must lie between 0 and the worker cap, was $worker."
    }
    require((decision != null) == (role == FeatureTaskRuntimeImplementationTurnRole.CHECK_SETTLED)) {
      "Only a settled check carries a decision."
    }
    require((retryIdentity != null) == (role == FeatureTaskRuntimeImplementationTurnRole.REOPEN)) {
      "Only a reopen grant carries a retry identity."
    }
    require(listOfNotNull(criterionId, retryIdentity).none { it.isBlank() || it.any(Char::isWhitespace) }) {
      "Implementation turn tokens must be non-blank and free of whitespace."
    }
  }

  fun encode(): String =
    listOfNotNull(
      MARKER_PREFIX,
      "$ROLE=${role.wireValue}",
      "$CRITERION=$criterionId",
      "$WORKER=$worker",
      decision?.let { "$DECISION=${it.wireValue}" },
      retryIdentity?.let { "$RETRY=$it" },
    ).joinToString(" ")

  companion object {
    private const val MARKER_PREFIX = "skill-bill-implementation-turn"
    private const val ROLE = "role"
    private const val CRITERION = "criterion"
    private const val WORKER = "worker"
    private const val DECISION = "decision"
    private const val RETRY = "retry"

    fun isMarker(prompt: String?): Boolean = prompt?.trim()?.startsWith(MARKER_PREFIX) == true

    fun parse(prompt: String?): FeatureTaskRuntimeImplementationTurnMarker? {
      val tokens = prompt?.trim()?.split(' ')?.takeIf { it.firstOrNull() == MARKER_PREFIX } ?: return null
      val fields =
        tokens.drop(1).associate { token ->
          token.substringBefore('=') to token.substringAfter('=', missingDelimiterValue = "")
        }
      val role =
        FeatureTaskRuntimeImplementationTurnRole.entries.firstOrNull { it.wireValue == fields[ROLE] } ?: return null
      val criterionId = fields[CRITERION]?.takeIf(String::isNotBlank) ?: return null
      val worker =
        fields[WORKER]?.toIntOrNull()
          ?.takeIf { it in 0..FeatureTaskRuntimeImplementationProgress.MAX_WORKERS_PER_CRITERION }
      val decision = FeatureTaskRuntimeImplementationCheckDecision.fromWireValue(fields[DECISION])
      val retry = fields[RETRY]?.takeIf(String::isNotBlank)
      val shaped =
        worker != null &&
          (decision != null || DECISION !in fields) &&
          (decision != null) == (role == FeatureTaskRuntimeImplementationTurnRole.CHECK_SETTLED) &&
          (retry != null) == (role == FeatureTaskRuntimeImplementationTurnRole.REOPEN)
      return worker?.takeIf { shaped }?.let {
        FeatureTaskRuntimeImplementationTurnMarker(role, criterionId, it, decision, retry)
      }
    }
  }
}

data class FeatureTaskRuntimeImplementationTurnRecord(
  val attempt: FeatureTaskRuntimeImplementationAttempt,
  val marker: FeatureTaskRuntimeImplementationTurnMarker,
)

sealed interface FeatureTaskRuntimeImplementationTurnState {
  data object Complete : FeatureTaskRuntimeImplementationTurnState

  data class WorkerNeeded(
    val criterionId: String,
    val worker: Int,
  ) : FeatureTaskRuntimeImplementationTurnState

  data class CheckOwed(
    val criterionId: String,
    val worker: Int,
  ) : FeatureTaskRuntimeImplementationTurnState

  data class Exhausted(
    val criterionId: String,
    val latestGap: String,
  ) : FeatureTaskRuntimeImplementationTurnState

  data class Incoherent(
    val reason: String,
  ) : FeatureTaskRuntimeImplementationTurnState
}

fun featureTaskRuntimeImplementationTurnRecords(
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
): List<FeatureTaskRuntimeImplementationTurnRecord> =
  attempts
    .sortedBy(FeatureTaskRuntimeImplementationAttempt::sequenceNumber)
    .mapNotNull { attempt ->
      implementationTurnMarkerOf(attempt)?.let { FeatureTaskRuntimeImplementationTurnRecord(attempt, it) }
    }

internal fun implementationTurnMarkerOf(
  attempt: FeatureTaskRuntimeImplementationAttempt,
): FeatureTaskRuntimeImplementationTurnMarker? =
  attempt
    .takeIf {
      it.phaseId == FeatureTaskRuntimePhaseIds.IMPLEMENT &&
        it.loopId == null &&
        it.edgeIteration == null
    }?.let { FeatureTaskRuntimeImplementationTurnMarker.parse(it.prompt) }

fun featureTaskRuntimeImplementationTurnState(
  progress: FeatureTaskRuntimeImplementationProgress,
  records: List<FeatureTaskRuntimeImplementationTurnRecord>,
): FeatureTaskRuntimeImplementationTurnState {
  val criterionId = progress.currentId ?: return FeatureTaskRuntimeImplementationTurnState.Complete
  val turns = records.filter { it.marker.criterionId == criterionId }
  val worker = progress.workerCount
  if (worker == 0) return FeatureTaskRuntimeImplementationTurnState.WorkerNeeded(criterionId, 1)
  val launch =
    turns.indexOfLast {
      it.marker.role == FeatureTaskRuntimeImplementationTurnRole.WORKER_LAUNCH && it.marker.worker == worker
    }
  if (launch < 0) {
    return FeatureTaskRuntimeImplementationTurnState.Incoherent(
      "Worker $worker of $criterionId is reserved but no launch record for it is retained.",
    )
  }
  val check =
    turns.drop(launch + 1).lastOrNull {
      it.marker.role == FeatureTaskRuntimeImplementationTurnRole.CHECK_SETTLED && it.marker.worker == worker
    }
  return when (check?.marker?.decision) {
    null -> FeatureTaskRuntimeImplementationTurnState.CheckOwed(criterionId, worker)
    FeatureTaskRuntimeImplementationCheckDecision.UNSATISFIED ->
      if (worker < FeatureTaskRuntimeImplementationProgress.MAX_WORKERS_PER_CRITERION) {
        FeatureTaskRuntimeImplementationTurnState.WorkerNeeded(criterionId, worker + 1)
      } else {
        FeatureTaskRuntimeImplementationTurnState.Exhausted(criterionId, check.attempt.value)
      }
    FeatureTaskRuntimeImplementationCheckDecision.SATISFIED ->
      FeatureTaskRuntimeImplementationTurnState.Incoherent(
        "A satisfied check of $criterionId is retained but the progress cursor did not advance.",
      )
  }
}

fun featureTaskRuntimeLaunchedImplementationTurn(
  records: List<FeatureTaskRuntimeImplementationTurnRecord>,
  attemptNumber: Int,
): FeatureTaskRuntimeImplementationTurnMarker? =
  records.lastOrNull { it.marker.role.launch }
    ?.takeIf { it.attempt.attemptNumber == attemptNumber }
    ?.marker
