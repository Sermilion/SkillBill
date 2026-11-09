package skillbill.workflow.taskruntime.model.persistence

import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationProgressSchema
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCheckedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressClassification
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAccounting
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairBudget
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionRepairAccounting
import skillbill.workflow.taskruntime.model.audit.terminal
import skillbill.workflow.taskruntime.model.audit.validAcceptanceCriterionRepairBudgets
import skillbill.workflow.taskruntime.model.audit.validateAcceptanceCriterionEvidence
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

data class FeatureTaskRuntimeImplementationProgress(
  val criterionIds: List<String>,
  val currentIndex: Int,
  val workerCount: Int,
  val launchCount: Int = 0,
  val completedMutations: List<AcceptanceCriterionCompletedMutation> = emptyList(),
  val independentChecks: List<AcceptanceCriterionCheckedMutation> = emptyList(),
  val repairBudgets: List<AcceptanceCriterionRepairBudget> = emptyList(),
  val workerReservationId: String? = null,
) {
  init {
    validateAcceptanceCriterionEvidence(completedMutations, independentChecks)
    require(validAcceptanceCriterionRepairBudgets(repairBudgets, completedMutations, criterionIds)) {
      "Repair budget identity or origin is invalid."
    }
    require(completedMutations.all { it.criterionRef in criterionIds }) { "Mutation names an unknown criterion." }
    require(workerReservationId == null || validRepairIdentity(workerReservationId)) {
      "Worker reservation identity is invalid."
    }
    val reason = violation(criterionIds, currentIndex, workerCount, launchCount)
    require(reason == null) { reason.orEmpty() }
  }

  val closedIds: List<String> get() = criterionIds.take(currentIndex)

  val complete: Boolean get() = currentIndex == criterionIds.size

  val currentId: String? get() = criterionIds.getOrNull(currentIndex)

  fun accounting(ref: String): AcceptanceCriterionRepairAccounting =
    acceptanceCriterionRepairAccounting(ref, completedMutations, independentChecks, repairBudgets)

  fun reserveWorker(
    reservationId: String = "$currentId:worker:${workerCount + 1}",
  ): FeatureTaskRuntimeImplementationProgress? {
    val ref = currentId ?: return null
    if (workerCount == Int.MAX_VALUE) {
      throw invalidFeatureTaskRuntimeImplementationProgressSchema(
        "implementation-progress",
        "Worker ordinal capacity exhausted.",
      )
    }
    val accounting = accounting(ref)
    val ready =
      accounting.pendingCheckAttemptIds.isEmpty() && !accounting.evidenceMissing &&
        (!FocusedRepairStrategy.exhausted(accounting.noProgressOutcomes) || accounting.escalatedOpportunityOwed)
    val previous = completedMutations.lastOrNull { it.criterionRef == ref }
    val known = workerCount == 0 || previous != null && previous.repairAttemptId == workerReservationId
    val validId =
      validRepairIdentity(reservationId) &&
        completedMutations.none { it.repairAttemptId == reservationId }
    return takeIf { ready && known && validId }
      ?.copy(workerCount = workerCount + 1, workerReservationId = reservationId)
  }

  fun launched(): FeatureTaskRuntimeImplementationProgress {
    if (launchCount == Int.MAX_VALUE) {
      throw invalidFeatureTaskRuntimeImplementationProgressSchema(
        "implementation-progress",
        "Launch ordinal capacity exhausted.",
      )
    }
    return copy(launchCount = launchCount + 1)
  }

  fun retainCompletedMutation(
    mutation: AcceptanceCriterionCompletedMutation,
  ): FeatureTaskRuntimeImplementationProgress? {
    mutation.validate()
    val previous = completedMutations.singleOrNull { it.repairAttemptId == mutation.repairAttemptId }
    if (previous != null) {
      if (previous != mutation) {
        throw invalidFeatureTaskRuntimeImplementationProgressSchema(
          "repair-evidence",
          "Conflicting terminal mutation replay.",
        )
      }
      return this
    }
    if (completedMutations.size >= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES) {
      throw invalidFeatureTaskRuntimeImplementationProgressSchema(
        "repair-evidence",
        "Completed mutation capacity exhausted.",
      )
    }
    return takeIf { acceptsMutationCapture(mutation) }?.copy(completedMutations = completedMutations + mutation)
  }

  private fun acceptsMutationCapture(mutation: AcceptanceCriterionCompletedMutation): Boolean {
    val worker =
      mutation.criterionRef == currentId && mutation.repairAttemptId == workerReservationId && mutation.terminal
    val inventory = independentChecks.lastOrNull { it.mutation.criterionRef == currentId }?.establishedObligations()
    val preserved = inventory == null || inventory == mutation.establishedObligations
    val launch = mutation.launchAttribution
    val escalated = launch != null && launch.reasoning && launch.validFor(mutation.repairAttemptId)
    val reasoning = !accounting(currentId.orEmpty()).escalationRequired || escalated
    return worker && workerCount > 0 && preserved && reasoning
  }

  fun retainIndependentCheck(check: AcceptanceCriterionCheckedMutation): FeatureTaskRuntimeImplementationProgress? {
    check.validate()
    if (check.mutation !in completedMutations) return null
    val previous = independentChecks.singleOrNull { it.mutation.repairAttemptId == check.mutation.repairAttemptId }
    if (previous != null) {
      if (previous != check) {
        throw invalidFeatureTaskRuntimeImplementationProgressSchema(
          "repair-evidence",
          "Conflicting independent check replay.",
        )
      }
      return this
    }
    if (independentChecks.size >= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES) {
      throw invalidFeatureTaskRuntimeImplementationProgressSchema(
        "repair-evidence",
        "Independent check capacity exhausted.",
      )
    }
    val worker = check.mutation.repairAttemptId == workerReservationId && check.mutation.criterionRef == currentId
    return takeIf { worker }?.copy(independentChecks = independentChecks + check)
  }

  fun closeCurrent(): FeatureTaskRuntimeImplementationProgress? {
    val mutation = completedMutations.lastOrNull { it.criterionRef == currentId } ?: return null
    val check = independentChecks.singleOrNull { it.mutation == mutation } ?: return null
    check.validate()
    return takeIf {
      !complete &&
        workerCount > 0 &&
        check.comparison.classification == AcceptanceCriterionProgressClassification.SATISFIED
    }
      ?.copy(currentIndex = currentIndex + 1, workerCount = 0, workerReservationId = null)
  }

  fun reopenCurrent(
    retryIdentity: String = "operator:${repairBudgets.size + 1}",
  ): FeatureTaskRuntimeImplementationProgress? {
    val ref = currentId ?: return null
    if (repairBudgets.any { it.retryIdentity == retryIdentity }) return this
    val accounting = accounting(ref)
    val ready = accounting.pendingCheckAttemptIds.isEmpty() && !accounting.evidenceMissing && workerCount > 0
    if (repairBudgets.size >= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES) {
      throw invalidFeatureTaskRuntimeImplementationProgressSchema("repair-budget", "Budget history capacity exhausted.")
    }
    return takeIf { ready && validRepairIdentity(retryIdentity) }?.copy(
      repairBudgets =
        repairBudgets +
          AcceptanceCriterionRepairBudget(
            ref,
            retryIdentity,
            completedMutations.size,
          ),
    )
  }

  companion object {
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
        workerCount < 0 -> "worker_count must not be negative."
        currentIndex == criterionIds.size && workerCount != 0 -> "worker_count must be 0 once every criterion closed."
        launchCount < 0 -> "launch_count must not be negative."
        else -> null
      }

    private val CRITERION_ID = Regex("^AC-[0-9]{3}$")
  }
}

private fun validRepairIdentity(value: String): Boolean =
  value.isNotBlank() && value.length <= AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH

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
    require(worker >= 0) {
      "Implementation turn worker must not be negative, was $worker."
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
          ?.takeIf { it >= 0 }
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
  val accounting = progress.accountingTurnState(criterionId)
  return when {
    accounting != null -> accounting
    progress.workerCount == 0 -> FeatureTaskRuntimeImplementationTurnState.WorkerNeeded(criterionId, 1)
    else ->
      implementationWorkerTurnState(progress, records.filter { it.marker.criterionId == criterionId }, criterionId)
  }
}

private fun FeatureTaskRuntimeImplementationProgress.accountingTurnState(
  criterionId: String,
): FeatureTaskRuntimeImplementationTurnState? {
  val accounting = accounting(criterionId)
  return when {
    accounting.pendingCheckAttemptIds.isNotEmpty() ->
      FeatureTaskRuntimeImplementationTurnState.CheckOwed(criterionId, workerCount)
    accounting.evidenceMissing ->
      FeatureTaskRuntimeImplementationTurnState.Incoherent(
        "Terminal repair evidence for $criterionId needs clarification.",
      )
    FocusedRepairStrategy.exhausted(accounting.noProgressOutcomes) && !accounting.escalatedOpportunityOwed -> {
      val gap =
        independentChecks.lastOrNull { it.mutation.criterionRef == criterionId }
          ?.comparison?.remainingGaps?.joinToString("; ").orEmpty()
      FeatureTaskRuntimeImplementationTurnState.Exhausted(criterionId, gap)
    }
    else -> null
  }
}

private fun implementationWorkerTurnState(
  progress: FeatureTaskRuntimeImplementationProgress,
  turns: List<FeatureTaskRuntimeImplementationTurnRecord>,
  criterionId: String,
): FeatureTaskRuntimeImplementationTurnState {
  val worker = progress.workerCount
  val mutation = progress.completedMutations.singleOrNull { it.repairAttemptId == progress.workerReservationId }
  val independent = progress.independentChecks.singleOrNull { it.mutation == mutation }
  if (mutation == null || independent == null) {
    return FeatureTaskRuntimeImplementationTurnState.Incoherent(
      "Worker $worker of $criterionId lacks terminal capture or independent comparison evidence.",
    )
  }
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
      if (independent.comparison.classification == AcceptanceCriterionProgressClassification.SATISFIED) {
        FeatureTaskRuntimeImplementationTurnState.Incoherent("The check marker contradicts retained satisfaction.")
      } else if (worker == Int.MAX_VALUE) {
        FeatureTaskRuntimeImplementationTurnState.Incoherent("Worker ordinal capacity exhausted.")
      } else {
        FeatureTaskRuntimeImplementationTurnState.WorkerNeeded(criterionId, worker + 1)
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
