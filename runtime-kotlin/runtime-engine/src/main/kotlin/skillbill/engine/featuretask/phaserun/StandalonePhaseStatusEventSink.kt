package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerGenerationObservation
import skillbill.ports.idestatus.StandaloneOutputCaptureScheduler
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.StandalonePhaseIncidentAlias
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

public class StandalonePhaseStatusEventSink(
  private val database: DatabaseSessionFactory,
  private val registrationForWorkflow: (String?) -> StandalonePhaseStatusRegistration,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val blockIncidentCapture: BlockIncidentCapture,
  private val outputScheduler: StandaloneOutputCaptureScheduler? = null,
  private val monitorSelector: String? = null,
  private val capturePlanOutput: Boolean = false,
) : FeatureTaskRuntimeRunEventSink {
  private var registered: StandalonePhaseStatusRecord? = null
  private var revision: String? = null
  private var activeDurationMs: Long = 0
  private var activeDurationAsOf: Instant? = null
  private var currentStep: String = "run"
  private var currentActivity: String? = null
  private var lastCiObservedPendingSampleNames: Set<String>? = null
  private var monitorCleanup: StandaloneMonitorCleanup? = null
  private var qaOutcome: StandaloneQaOutcome? = null
  private var qaReportText: String? = null
  private var qaDetails: QaRunDetails? = null
  private var planReport: StandalonePlanReport? = null
  private var reviewRunId: String? = null
  private val outputLock = Any()
  private val outputBuffers = mutableMapOf<AgentRunOutputStream, StringBuilder>()
  private var outputSnapshot = StandalonePhaseOutputSnapshot()
  private var snapshotLoaded = false
  private var outputDirty = false
  private var outputClosed = false
  private var scheduledOutputWrite: AutoCloseable? = null
  private var outputScheduleGeneration = 0L

  public val outputSink: AgentRunOutputSink =
    AgentRunOutputSink { stream, text ->
      synchronized(outputLock) {
        if (outputClosed) return@synchronized
        val buffer = outputBuffers.getOrPut(stream) { StringBuilder() }
        text.forEach { character ->
          if (character == '\n') {
            appendOutputLine(buffer.toString().trimEnd('\r'), clock.instant())
            buffer.setLength(0)
          } else if (buffer.length < MAX_OUTPUT_LINE_CHARS) {
            buffer.append(character)
          }
        }
      }
    }

  public val planOutputSink: AgentRunOutputSink
    get() = if (capturePlanOutput) outputSink else AgentRunOutputSink.NONE

  internal fun recordMonitorCleanup(cleanup: StandaloneMonitorCleanup) {
    monitorCleanup = cleanup
  }

  internal fun recordQaOutcome(outcome: StandaloneQaOutcome) {
    qaOutcome = outcome
  }

  override fun emit(event: FeatureTaskRuntimeRunEvent) {
    catchingNonCancellation {
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
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status publication failed", error)
    }
  }

  internal fun publishActivity(
    stepName: String,
    activity: String,
  ) {
    catchingNonCancellation {
      ensureRegistered(null)
      currentStep = stepName
      currentActivity = boundedIdeStatusActivity(activity)
      persist(state = "active", finishedAt = null, terminalResult = null)
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status activity publication failed", error)
    }
  }

  internal fun settle(result: PhaseRunResult) {
    flushOutput()
    catchingNonCancellation {
      ensureRegistered(null)
      when (result) {
        is PhaseRunResult.Completed -> {
          result.qaOutcome?.let(::recordQaOutcome)
          qaReportText = result.qaReportText
          qaDetails = result.qaDetails
          reviewRunId = result.reviewRunId
          persist(
            state = "terminal",
            finishedAt = clock.instant(),
            terminalResult = result.value,
            acceptedResult = result.acceptedOutput ?: result.value,
          )
        }
        is PhaseRunResult.Blocked -> {
          val providerLimitPause = FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)
          persist(
            state = if (providerLimitPause) "paused" else "blocked",
            finishedAt = null,
            terminalResult = result.reason,
          )
          if (!providerLimitPause) {
            observeBlocked(result.reason, null, result.stepId)
          }
        }
      }
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status settlement failed", error)
    }
  }

  public fun settlePlan(
    result: String,
    workflowId: String? = null,
    report: StandalonePlanReport? = null,
  ) {
    flushOutput()
    catchingNonCancellation {
      ensureRegistered(workflowId)
      planReport = report
      persist(
        state = "terminal",
        finishedAt = clock.instant(),
        terminalResult = result,
      )
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status plan settlement failed", error)
    }
  }

  public fun publishPlanReport(report: () -> StandalonePlanReport?) {
    catchingNonCancellation {
      val built = report() ?: return@catchingNonCancellation
      ensureRegistered(null)
      planReport = built
      persist(state = "active", finishedAt = null, terminalResult = null)
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status plan report publication failed", error)
    }
  }

  public fun settleBlocked(
    reason: String,
    workflowId: String? = null,
    stepId: String? = null,
  ) {
    flushOutput()
    catchingNonCancellation {
      ensureRegistered(workflowId)
      currentStep = stepId ?: currentStep
      persist(state = "blocked", finishedAt = null, terminalResult = reason)
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status blocked settlement failed", error)
    }
    observeBlocked(reason, workflowId, stepId)
  }

  private fun observeBlocked(
    reason: String,
    workflowId: String?,
    stepId: String?,
  ) {
    val observation =
      catchingNonCancellation {
        val registration = registrationForWorkflow(workflowId)
        val target = registered?.monitorTarget ?: registration.monitorTarget
        val blockedWorkflowId = (registered?.workflowId ?: registration.workflowId)?.takeIf(String::isNotBlank)
        val blockedStepId = stepId ?: registered?.phaseId ?: registration.phaseId
        val invocationId = registered?.invocationId ?: registration.invocationId
        val workerCoordinates = blockedWorkflowId?.let { workerCoordinates(it, blockedStepId) }
        BlockIncidentObservation(
          workflowId = blockedWorkflowId,
          phaseId = blockedStepId,
          invocationId = invocationId,
          executionId = registered?.executionId ?: registration.executionId,
          reason = reason,
          alias = StandalonePhaseIncidentAlias.create(invocationId, blockedStepId),
          linkAcceptedFeatureTaskPhase = blockedWorkflowId != null,
          attempt = workerCoordinates?.phaseAttempt,
          generation = workerCoordinates?.generation,
          origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
          exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
          monitorSelector = monitorSelector?.takeIf { it.isNotBlank() && it.length <= MAX_MONITOR_SELECTOR_CHARS },
          pullRequestId = target?.takeIf { it.branchRef == null }?.pullRequestKey,
          pullRequestUrl = target?.takeIf { it.branchRef == null }?.pullRequestUrl,
          headRepository = target?.headRepository,
          headBranch = target?.headRef,
          worktreePath = target?.worktreePath,
          unavailableFields =
            mapOf(
              BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
              BlockIncidentCorrelatedField.ATTEMPT to
                if (workerCoordinates == null) {
                  BlockIncidentFieldAvailability.NOT_CAPTURED
                } else {
                  BlockIncidentFieldAvailability.AVAILABLE
                },
            ),
        )
      }.onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone block incident observation failed", error)
      }.getOrNull() ?: return
    blockIncidentCapture.capture(observation)
  }

  private fun workerCoordinates(
    workflowId: String,
    blockedStepId: String,
  ): FeatureTaskRuntimeWorkerGenerationObservation? =
    database.read { unit ->
      val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      val step = workflow.step(blockedStepId) ?: return@read null
      unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(workflowId)?.takeIf {
        workflow.currentStepId == blockedStepId && workflow.workflowStatus == WorkflowStatus.BLOCKED &&
          step.status == WorkflowStepStatus.BLOCKED && step.attemptCount == it.phaseAttempt &&
          it.phaseId == blockedStepId
      }
    }

  public fun settleFailure(
    error: Throwable,
    workflowId: String? = null,
  ) {
    flushOutput()
    catchingNonCancellation {
      ensureRegistered(workflowId)
      persist(
        state = "failed",
        finishedAt = clock.instant(),
        terminalResult = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty(),
      )
    }.onFailure { settlementError ->
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
    val now = clock.instant()
    val elapsed = activeDurationAsOf?.let { Duration.between(it, now).toMillis().coerceAtLeast(0) } ?: 0
    val duration = activeDurationMs + elapsed
    val anchor = now.takeIf { state == "active" }
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
            updatedAt = now,
            finishedAt = finishedAt,
            activeDurationMs = duration,
            activeDurationAsOf = anchor,
            terminalResult = terminalResult?.takeIf(String::isNotBlank),
            acceptedResult = acceptedResult?.takeIf(String::isNotBlank),
            monitorCleanup = monitorCleanup,
            qaOutcome = qaOutcome,
            qaReportText = qaReportText,
            qaDetails = qaDetails,
            planReport = planReport,
            reviewRunId = reviewRunId,
          ),
        )
      }
    when (result) {
      StandalonePhaseStatusUpdateResult.ACCEPTED -> {
        revision = incrementDecimal(requireNotNull(revision))
        activeDurationMs = duration
        activeDurationAsOf = anchor
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
    synchronized(outputLock) {
      if (registered != null) return
      val record = database.transaction { it.standalonePhaseStatuses.register(registrationForWorkflow(workflowId)) }
      registered = record
      revision = record.statusRevision
      activeDurationMs = record.activeDurationMs ?: 0
      activeDurationAsOf = record.activeDurationAsOf ?: record.startedAt.takeIf { record.lifecycleState == "active" }
      currentStep = record.phaseId
    }
  }

  private fun flushOutput() {
    synchronized(outputLock) {
      cancelScheduledOutputWrite()
      outputBuffers.values.forEach { buffer ->
        if (buffer.isNotEmpty()) {
          appendOutputLine(buffer.toString(), clock.instant())
          buffer.setLength(0)
        }
      }
      writeOutputSnapshot()
    }
  }

  public fun closeOutputCapture() {
    synchronized(outputLock) {
      if (outputClosed) return
      flushOutput()
      outputClosed = true
      cancelScheduledOutputWrite()
    }
  }

  public fun flushOutputCapture() {
    flushOutput()
  }

  private fun appendOutputLine(
    line: String,
    completedAt: Instant,
  ) {
    if (line.isBlank()) return
    val boundedLine = line.take(MAX_OUTPUT_LINE_CHARS)
    val tail = (outputSnapshot.tail + boundedLine).takeLast(OUTPUT_TAIL_LINES)
    val attributed = LinkedHashMap(outputSnapshot.latestAttributedLines)
    val attributedAt = LinkedHashMap(outputSnapshot.latestAttributedLineAt)
    SUBTASK_ATTRIBUTION.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { unitId ->
      attributed.remove(unitId)
      attributed[unitId] = boundedLine
      attributedAt.remove(unitId)
      attributedAt[unitId] = completedAt
      while (attributed.size > MAX_ATTRIBUTED_SUBTASKS) attributed.remove(attributed.keys.first())
      attributedAt.keys.retainAll(attributed.keys)
    }
    outputSnapshot = StandalonePhaseOutputSnapshot(tail, completedAt, attributed, attributedAt)
    outputDirty = true
    catchingNonCancellation {
      ensureRegistered(null)
      loadOutputSnapshot()
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone plan output initialization failed", error)
    }
    scheduleOutputWrite()
  }

  private fun loadOutputSnapshot() {
    if (snapshotLoaded) return
    val current = requireNotNull(registered)
    val persisted = database.read { it.standalonePhaseStatuses.readOutput(current.executionId) }
    if (persisted != null) {
      val tail = (persisted.tail + outputSnapshot.tail).takeLast(OUTPUT_TAIL_LINES)
      val attributed = LinkedHashMap(persisted.latestAttributedLines)
      val attributedAt = LinkedHashMap(persisted.latestAttributedLineAt)
      outputSnapshot.latestAttributedLines.forEach { (unitId, line) ->
        attributed.remove(unitId)
        attributed[unitId] = line
        outputSnapshot.latestAttributedLineAt[unitId]?.let { at ->
          attributedAt.remove(unitId)
          attributedAt[unitId] = at
        }
      }
      while (attributed.size > MAX_ATTRIBUTED_SUBTASKS) attributed.remove(attributed.keys.first())
      attributedAt.keys.retainAll(attributed.keys)
      outputSnapshot =
        StandalonePhaseOutputSnapshot(
          tail,
          outputSnapshot.latestLineAt ?: persisted.latestLineAt,
          attributed,
          attributedAt,
        )
    }
    snapshotLoaded = true
  }

  private fun scheduleOutputWrite() {
    if (scheduledOutputWrite != null || outputClosed) return
    val scheduler = outputScheduler ?: return
    outputScheduleGeneration++
    val generation = outputScheduleGeneration
    scheduledOutputWrite =
      catchingNonCancellation {
        scheduler.schedule(OUTPUT_WRITE_DELAY) {
          synchronized(outputLock) {
            if (generation != outputScheduleGeneration) return@synchronized
            scheduledOutputWrite = null
            if (!outputClosed) writeOutputSnapshot()
          }
        }
      }.onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone output write scheduling failed", error)
      }.getOrNull()
  }

  private fun cancelScheduledOutputWrite() {
    val scheduled = scheduledOutputWrite ?: return
    outputScheduleGeneration++
    scheduledOutputWrite = null
    catchingNonCancellation { scheduled.close() }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone output write cancellation failed", error)
    }
  }

  private fun writeOutputSnapshot() {
    if (!outputDirty) return
    catchingNonCancellation {
      ensureRegistered(null)
      loadOutputSnapshot()
      val executionId = requireNotNull(registered).executionId
      database.transaction { unit -> unit.standalonePhaseStatuses.writeOutputSnapshot(executionId, outputSnapshot) }
      outputDirty = false
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone plan output persistence failed", error)
    }
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
    const val OUTPUT_TAIL_LINES = 4
    const val MAX_OUTPUT_LINE_CHARS = 160
    const val MAX_ATTRIBUTED_SUBTASKS = 32
    val OUTPUT_WRITE_DELAY: Duration = Duration.ofMillis(250)
    val SUBTASK_ATTRIBUTION = Regex("^\\[subtask ([1-9][0-9]*)] ")
    const val MAX_CHECK_NAME_LENGTH = 160
    const val MAX_VISIBLE_NAME_TEXT = 28
    const val MAX_VISIBLE_TRANSITIONS = 2
    const val MAX_VISIBLE_PENDING_NAMES = 2
    const val MAX_MONITOR_SELECTOR_CHARS = 2048
  }
}
