package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException

class StandalonePhaseStatusEventSink(
  private val database: DatabaseSessionFactory,
  private val registrationForWorkflow: (String?) -> StandalonePhaseStatusRegistration,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskRuntimeRunEventSink {
  private var registered: StandalonePhaseStatusRecord? = null
  private var revision: String? = null
  private var currentStep: String = "run"
  private var currentActivity: String? = null

  override fun emit(event: FeatureTaskRuntimeRunEvent) {
    runCatching {
      ensureRegistered(event.workflowId.takeIf(String::isNotBlank))
      val state =
        when (event) {
          is FeatureTaskRuntimeRunEvent.PhasePaused -> "paused"
          else -> "active"
        }
      currentStep = event.phaseId
      currentActivity = boundedIdeStatusActivity(activityFor(event))
      persist(state, finishedAt = null, terminalResult = null)
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status publication failed", error)
    }
  }

  fun settle(result: PhaseRunResult) {
    runCatching {
      ensureRegistered(null)
      when (result) {
        is PhaseRunResult.Completed ->
          persist(
            state = "terminal",
            finishedAt = clock.instant(),
            terminalResult = result.value,
          )
        is PhaseRunResult.Blocked ->
          persist(
            state = "blocked",
            finishedAt = null,
            terminalResult = result.reason,
          )
      }
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status settlement failed", error)
    }
  }

  fun settlePlan(
    result: String,
    workflowId: String? = null,
  ) {
    runCatching {
      ensureRegistered(workflowId)
      persist(
        state = "terminal",
        finishedAt = clock.instant(),
        terminalResult = result,
      )
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status plan settlement failed", error)
    }
  }

  fun settleBlocked(
    reason: String,
    workflowId: String? = null,
  ) {
    runCatching {
      ensureRegistered(workflowId)
      persist(state = "blocked", finishedAt = null, terminalResult = reason)
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status blocked settlement failed", error)
    }
  }

  fun settleFailure(
    error: Throwable,
    workflowId: String? = null,
  ) {
    runCatching {
      ensureRegistered(workflowId)
      persist(
        state = "failed",
        finishedAt = clock.instant(),
        terminalResult = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty(),
      )
    }.onFailure { settlementError ->
      if (settlementError is CancellationException) throw settlementError
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status failure settlement failed", settlementError)
    }
  }

  private fun persist(
    state: String,
    finishedAt: Instant?,
    terminalResult: String?,
  ) {
    val current = registered ?: return
    val result =
      database.transaction { unitOfWork ->
        unitOfWork.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            executionId = current.executionId,
            expectedRevision = requireNotNull(revision),
            leaseOwner = current.leaseOwner,
            leaseGeneration = current.leaseGeneration,
            lifecycleState = state,
            currentStep = currentStep,
            currentActivity = currentActivity,
            updatedAt = clock.instant(),
            finishedAt = finishedAt,
            terminalResult = terminalResult?.takeIf(String::isNotBlank),
          ),
        )
      }
    when (result) {
      StandalonePhaseStatusUpdateResult.ACCEPTED -> {
        revision = incrementDecimal(requireNotNull(revision))
      }
      StandalonePhaseStatusUpdateResult.IDEMPOTENT -> Unit
      else -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "IDE status publication rejected: execution=${current.executionId}; result=$result",
        )
      }
    }
  }

  private fun ensureRegistered(workflowId: String?) {
    if (registered != null) return
    val record = database.transaction { it.standalonePhaseStatuses.register(registrationForWorkflow(workflowId)) }
    registered = record
    revision = record.statusRevision
    currentStep = record.phaseId
  }

  private fun activityFor(event: FeatureTaskRuntimeRunEvent): String =
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> "Phase started: ${event.phaseId}"
      is FeatureTaskRuntimeRunEvent.PhaseLoopEdge -> "Loop edge: ${event.phaseId}"
      is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> "Fix iteration: ${event.phaseId}"
      is FeatureTaskRuntimeRunEvent.ValidationGateProgress -> "Validation gate: ${event.phaseId}"
      is FeatureTaskRuntimeRunEvent.CiStillRunning -> ciStillRunningActivity(event.pendingChecks)
      is FeatureTaskRuntimeRunEvent.PhaseCompleted -> "Phase completed: awaiting settlement"
      is FeatureTaskRuntimeRunEvent.PhaseBlocked -> "Phase blocked"
      is FeatureTaskRuntimeRunEvent.PhasePaused -> "Phase paused"
      is FeatureTaskRuntimeRunEvent.BranchResolved -> "Branch resolved"
      is FeatureTaskRuntimeRunEvent.BranchSetupBlocked -> "Branch setup blocked"
      is FeatureTaskRuntimeRunEvent.RunStarted -> "Run started"
      is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning -> "Planning decomposed"
    }

  private fun ciStillRunningActivity(pendingChecks: List<String>): String {
    val names = pendingChecks.filter(String::isNotBlank).distinct()
    return if (names.isEmpty()) "CI still running" else "CI still running: ${names.joinToString(", ")}"
  }

  private fun incrementDecimal(value: String): String {
    val digits = value.toCharArray()
    var carry = 1
    for (index in digits.lastIndex downTo 0) {
      if (carry == 0) break
      if (digits[index] == '9') {
        digits[index] = '0'
      } else {
        digits[index] = (digits[index].code + 1).toChar()
        carry = 0
      }
    }
    return if (carry == 1) "1${digits.concatToString()}" else digits.concatToString()
  }
}
