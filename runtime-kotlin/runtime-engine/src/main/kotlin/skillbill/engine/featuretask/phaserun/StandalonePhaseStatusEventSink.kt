package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.validation.check.readValidationOutcome
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.agentrun.model.AgentRunActivityOutputSink
import skillbill.ports.agentrun.model.AgentRunModelObserver
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerGenerationObservation
import skillbill.ports.idestatus.StandaloneOutputCaptureScheduler
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandalonePlanActivity
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.StandalonePhaseIncidentAlias
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

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
  private var reportedModel: String? = null
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
  private var validationOutcome: StandaloneValidationOutcome? = null
  private val outputLock = Any()
  private val truncatedOutputStreams = mutableSetOf<AgentRunOutputStream>()
  private val outputBuffers = mutableMapOf<AgentRunOutputStream, StringBuilder>()
  private var outputSnapshot = StandalonePhaseOutputSnapshot()
  private var snapshotLoaded = false
  private var outputDirty = false
  private var outputClosed = false
  private var scheduledOutputWrite: AutoCloseable? = null
  private var outputScheduleGeneration = 0L
  private val activityStarts = linkedMapOf<String, Instant>()

  public val outputSink: AgentRunOutputSink =
    object : AgentRunActivityOutputSink {
      override val capturesActivity: Boolean = capturePlanOutput

      override fun write(
        stream: AgentRunOutputStream,
        text: String,
      ) {
        synchronized(outputLock) {
          if (outputClosed) return@synchronized
          val buffer = outputBuffers.getOrPut(stream) { StringBuilder() }
          text.forEach { character ->
            if (character == '\n') {
              appendOutputLine(buffer.toString().trimEnd('\r'), clock.instant())
              buffer.setLength(0)
              truncatedOutputStreams.remove(stream)
            } else if (buffer.length < MAX_LOG_LINE_CHARS) {
              buffer.append(character)
            } else if (truncatedOutputStreams.add(stream)) {
              RuntimeDiagnosticsBestEffortWarning.record(
                diagnostics,
                "Standalone output history line exceeded $MAX_LOG_LINE_CHARS characters; suffix omitted",
              )
            }
          }
        }
      }

      override fun activity(event: AgentRunActivity) {
        synchronized(outputLock) {
          if (outputClosed) return
          val now = clock.instant()
          val start = activityStarts[event.id] ?: now
          if (event.state == AgentRunActivityState.STARTED) {
            if (activityStarts.size >= MAX_PENDING_ACTIVITY) {
              RuntimeDiagnosticsBestEffortWarning.record(
                diagnostics,
                "Plan activity start buffer full; oldest start time omitted",
              )
              activityStarts.remove(activityStarts.keys.first())
            }
            activityStarts.putIfAbsent(event.id, now)
          } else {
            activityStarts.remove(event.id)
          }
          val duration = event.durationMs ?: if (start != now) Duration.between(start, now).toMillis() else null
          val phase = currentStep.substringBefore(':')
          val id = if (event.kind == AgentRunActivityKind.STREAM) "$phase:stream" else event.id
          val record =
            StandalonePlanActivity(
              phase,
              start,
              now,
              event.copy(id = id, durationMs = duration?.coerceAtLeast(0)),
            )
          val pending = outputSnapshot.pendingActivity.filterNot { it.event.id == record.event.id } + record
          if (pending.size > MAX_PENDING_ACTIVITY) {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "Plan activity buffer full; oldest pending event omitted",
            )
          }
          outputSnapshot = outputSnapshot.copy(pendingActivity = pending.takeLast(MAX_PENDING_ACTIVITY))
          outputDirty = true
          scheduleOutputWrite()
        }
      }

      override fun raw(
        stream: AgentRunOutputStream,
        text: String,
        subtaskId: Int?,
      ) {
        synchronized(outputLock) {
          if (outputClosed) return
          val now = clock.instant()
          val line =
            StandalonePhaseOutputLine(
              UUID.randomUUID().toString(),
              text.take(MAX_RAW_LINE_CHARS),
              now,
              subtaskId,
              isRaw = true,
            )
          val pending = outputSnapshot.pendingLines + line
          if (pending.size > MAX_PENDING_OUTPUT_LINES) {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "Raw transcript buffer full; oldest pending line omitted",
            )
          }
          outputSnapshot =
            outputSnapshot.copy(
              pendingLines = pending.takeLast(MAX_PENDING_OUTPUT_LINES),
              latestLineAt = now,
            )
          outputDirty = true
          scheduleOutputWrite()
        }
      }
    }

  public val planOutputSink: AgentRunOutputSink
    get() = if (capturePlanOutput) outputSink else AgentRunOutputSink.NONE

  internal fun modelObserver(delegate: AgentRunModelObserver?): AgentRunModelObserver =
    AgentRunModelObserver { model ->
      reportModel(model)
      delegate?.reported(model)
    }

  @Synchronized
  internal fun reportModel(model: String?) {
    if (registered != null && reportedModel == model) return
    catchingNonCancellation {
      ensureRegistered(null)
      reportedModel = model
      persist(state = "active", finishedAt = null, terminalResult = null)
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "IDE status model publication failed", error)
    }
  }

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
          captureValidationOutcome()
          persist(
            state = "terminal",
            finishedAt = clock.instant(),
            terminalResult = result.value,
            acceptedResult = result.acceptedOutput ?: result.value,
          )
        }
        is PhaseRunResult.Blocked -> {
          val providerLimitPause = FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)
          if (!providerLimitPause) captureValidationOutcome()
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

  private fun captureValidationOutcome() {
    val current = registered?.takeIf { SkeletonDefinition.reportsLiveChecksOf(it.phaseId) } ?: return
    catchingNonCancellation { database.readValidationOutcome(current.invocationId) }
      .onSuccess { validationOutcome = it }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "IDE status validation outcome derivation failed",
          error,
        )
      }
  }

  @Synchronized
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
            validationOutcome = validationOutcome,
            reportedModel = reportedModel,
            replaceReportedModel = true,
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
    val unitId = SUBTASK_ATTRIBUTION.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    val entry =
      StandalonePhaseOutputLine(UUID.randomUUID().toString(), line.take(MAX_LOG_LINE_CHARS), completedAt, unitId)
    if (outputSnapshot.pendingLines.size == MAX_PENDING_OUTPUT_LINES) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Standalone output history buffer full; oldest unpersisted line dropped",
      )
    }
    val pending = (outputSnapshot.pendingLines + entry).takeLast(MAX_PENDING_OUTPUT_LINES)
    outputSnapshot =
      outputSnapshot.copy(
        tail = tail,
        latestLineAt = completedAt,
        latestAttributedLines = attributed,
        latestAttributedLineAt = attributedAt,
        pendingLines = pending,
      )
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
          outputSnapshot.pendingLines,
          pendingActivity = outputSnapshot.pendingActivity,
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
      outputSnapshot = outputSnapshot.copy(pendingLines = emptyList(), pendingActivity = emptyList())
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
    const val MAX_PENDING_ACTIVITY = 512
    const val MAX_RAW_LINE_CHARS = 65536
    const val OUTPUT_TAIL_LINES = 4
    const val MAX_OUTPUT_LINE_CHARS = 160
    const val MAX_LOG_LINE_CHARS = 8192
    const val MAX_PENDING_OUTPUT_LINES = 1000
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
