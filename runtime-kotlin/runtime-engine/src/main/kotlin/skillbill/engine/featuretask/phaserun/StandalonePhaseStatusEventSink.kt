package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
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
  private var lastCiObservedPendingSampleNames: Set<String>? = null
  private var monitorCleanup: StandaloneMonitorCleanup? = null
  private var qaOutcome: StandaloneQaOutcome? = null
  private var qaReport: StandaloneQaReport? = null
  private var planReport: StandalonePlanReport? = null

  fun recordMonitorCleanup(cleanup: StandaloneMonitorCleanup) {
    monitorCleanup = cleanup
  }

  fun recordQaOutcome(outcome: StandaloneQaOutcome) {
    qaOutcome = outcome
  }

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

  fun publishActivity(
    stepName: String,
    activity: String,
  ) {
    runCatching {
      ensureRegistered(null)
      currentStep = stepName
      currentActivity = boundedIdeStatusActivity(activity)
      persist(state = "active", finishedAt = null, terminalResult = null)
    }.onFailure { error ->
      if (error is CancellationException) throw error
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status activity publication failed", error)
    }
  }

  fun settle(result: PhaseRunResult) {
    runCatching {
      ensureRegistered(null)
      when (result) {
        is PhaseRunResult.Completed -> {
          result.qaOutcome?.let(::recordQaOutcome)
          qaReport = result.qaReport
          persist(
            state = "terminal",
            finishedAt = clock.instant(),
            terminalResult = result.value,
            acceptedResult = result.acceptedOutput ?: result.value,
          )
        }
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
    report: StandalonePlanReport? = null,
  ) {
    runCatching {
      ensureRegistered(workflowId)
      planReport = report
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
    acceptedResult: String? = terminalResult,
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
            acceptedResult = acceptedResult?.takeIf(String::isNotBlank),
            monitorCleanup = monitorCleanup,
            qaOutcome = qaOutcome,
            qaReport = qaReport,
            planReport = planReport,
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
      is FeatureTaskRuntimeRunEvent.CiStillRunning -> ciStillRunningActivity(event)
      is FeatureTaskRuntimeRunEvent.PhaseCompleted -> "Phase completed: awaiting settlement"
      is FeatureTaskRuntimeRunEvent.PhaseBlocked -> "Phase blocked"
      is FeatureTaskRuntimeRunEvent.PhasePaused -> "Phase paused"
      is FeatureTaskRuntimeRunEvent.BranchResolved -> "Branch resolved"
      is FeatureTaskRuntimeRunEvent.BranchSetupBlocked -> "Branch setup blocked"
      is FeatureTaskRuntimeRunEvent.RunStarted -> "Run started"
      is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning -> "Planning decomposed"
    }

  private fun ciStillRunningActivity(event: FeatureTaskRuntimeRunEvent.CiStillRunning): String {
    val observedSampleNames = event.pendingChecks.map { it.name.take(MAX_CHECK_NAME_LENGTH) }.toSet()
    if (observedSampleNames.isEmpty()) {
      lastCiObservedPendingSampleNames = emptySet()
      return "CI waiting for check registration"
    }
    val previousSampleNames = lastCiObservedPendingSampleNames
    lastCiObservedPendingSampleNames = observedSampleNames
    val newlyVisible = previousSampleNames?.let { observedSampleNames - it }.orEmpty().sorted()
    val noLongerVisible = previousSampleNames?.let { it - observedSampleNames }.orEmpty().sorted()
    val sampleChanged = previousSampleNames != null && previousSampleNames != observedSampleNames
    val transitionNames =
      (
        newlyVisible.map { "now visible ${it.take(MAX_VISIBLE_NAME_TEXT)}" } +
          noLongerVisible.map { "no longer visible ${it.take(MAX_VISIBLE_NAME_TEXT)}" }
      )
        .take(MAX_VISIBLE_TRANSITIONS)
        .joinToString("; ")
    val names = (newlyVisible + observedSampleNames.sorted()).distinct().take(MAX_VISIBLE_PENDING_NAMES)
    val detail = names.joinToString(", ") { it.take(MAX_VISIBLE_NAME_TEXT) }
    val change =
      when {
        transitionNames.isNotBlank() -> "; $transitionNames"
        sampleChanged -> "; visible pending-check sample changed"
        previousSampleNames == null -> "; pending $detail"
        else -> "; still pending $detail"
      }
    return "CI waiting (pending-check sample: ${event.pendingChecks.size} checks)$change"
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

  private companion object {
    const val MAX_CHECK_NAME_LENGTH = 160
    const val MAX_VISIBLE_NAME_TEXT = 28
    const val MAX_VISIBLE_TRANSITIONS = 2
    const val MAX_VISIBLE_PENDING_NAMES = 2
  }
}
