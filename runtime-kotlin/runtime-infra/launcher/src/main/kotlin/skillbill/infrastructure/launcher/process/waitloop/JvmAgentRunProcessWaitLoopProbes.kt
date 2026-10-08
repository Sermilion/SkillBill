package skillbill.infrastructure.launcher.process.waitloop

import skillbill.idestatus.model.AgentActivityLabel
import skillbill.infrastructure.launcher.process.launch.parseWorkflowIdAndStep
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver

internal fun ProcessWaitLoop.pollWorkflowProgress(nowNanos: Long) {
  val read = request.probes.progressProbe.readProgressToken(degradation)
  if (read.failed) return
  val progressToken = read.value
  if (progressToken == null && lastProgressToken != null) {
    lastProgressToken = null
    degradation.recordProbeAbsence("progress_token")
    return
  }
  if (progressToken != lastProgressToken) {
    lastProgressToken = progressToken
    lastWorkflowProgressNanos = nowNanos
    lastProgressInstant = clock.instant()
    fileActivityWindowStartNanos = null
    writeProgressLabel(nowNanos)
    request.probes.activityStampSink.safeStamp(AgentActivityLabel.DURABLE_PROGRESS)
  }
}

internal fun ProcessWaitLoop.pollOutputActivity(nowNanos: Long) {
  val observedMillis = outputTracker.lastObservedAt()?.toEpochMilli() ?: return
  if (observedMillis != lastObservedOutputMillis) {
    lastObservedOutputMillis = observedMillis
    lastOutputNanos = nowNanos
    request.probes.activityStampSink.safeStamp(AgentActivityLabel.STDOUT)
  }
}

internal fun ProcessWaitLoop.pollFileActivity(nowNanos: Long) {
  val read = request.probes.activityProbe.readActivityToken(degradation)
  if (read.failed) return
  val activityToken = read.value
  if (activityToken == null && lastActivityToken != null) {
    lastActivityToken = null
    degradation.recordProbeAbsence("activity_token")
    return
  }
  if (activityToken != lastActivityToken) {
    lastActivityToken = activityToken
    lastActivityInstant = clock.instant()
    if (fileActivityWindowStartNanos == null) {
      fileActivityWindowStartNanos = nowNanos
    }
    writeActivityLabel()
    request.probes.activityStampSink.safeStamp(AgentActivityLabel.WORKTREE_WRITE)
    request.probes.worktreeEditObserver.safeObserve()
  }
}

internal fun ProcessWaitLoop.pollStatusHeartbeat(nowNanos: Long) {
  val alive = process.isAlive
  if (alive) lastLiveHeartbeatNanos = nowNanos
  if (nowNanos - lastStatusHeartbeatNanos < statusHeartbeatNanos) return
  lastStatusHeartbeatNanos = nowNanos
  lifecycleEmitter.emitHeartbeat(alive)
  request.probes.progressProbe.readProgressLabel(degradation).value?.takeIf(String::isNotBlank)?.let { label ->
    lastProgressLabel = label
    observeWorkflowStepLabel(label, nowNanos)
  }
  val workflowLabel = lastProgressLabel?.takeIf(String::isNotBlank)
  val activityLabel = lastActivityLabel?.takeIf(String::isNotBlank)
  val details =
    listOfNotNull(
      workflowLabel?.let { "workflow: $it" },
      activityLabel?.let { "file activity: $it" },
    ).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { "; $it" }
      .orEmpty()
  request.launch.outputSink.write(
    AgentRunOutputStream.STDERR,
    "skill-bill: status heartbeat (${request.timing.statusHeartbeatInterval}): child run still active$details\n",
  )
}

internal fun ProcessWaitLoop.writeProgressLabel(nowNanos: Long) {
  request.probes.progressProbe.readProgressLabel(degradation).value
    ?.takeIf(String::isNotBlank)
    ?.let { label ->
      lastProgressLabel = label
      lastSnapshotInstant = clock.instant()
      observeWorkflowStepLabel(label, nowNanos)
      request.launch.outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: workflow progress: $label\n")
    }
}

internal fun ProcessWaitLoop.observeWorkflowStepLabel(
  label: String,
  nowNanos: Long,
) {
  val step = parseWorkflowIdAndStep(label).second ?: return
  if (lastObservedWorkflowStep != null && lastObservedWorkflowStep != step) {
    resetWallClock(nowNanos)
  }
  lastObservedWorkflowStep = step
}

internal fun ProcessWaitLoop.writeActivityLabel() {
  val activityLabel = request.probes.activityProbe.readActivityLabel(degradation).value?.takeIf(String::isNotBlank)
  if (activityLabel != null) {
    lastActivityLabel = activityLabel
  }
  val workflowLabel = request.probes.progressProbe.readProgressLabel(degradation).value?.takeIf(String::isNotBlank)
  val suffix =
    listOfNotNull(activityLabel, workflowLabel).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { label -> ": $label" }
      .orEmpty()
  request.launch.outputSink.write(
    AgentRunOutputStream.STDERR,
    "skill-bill: file activity observed; durable workflow progress is still pending$suffix\n",
  )
}

internal fun AgentRunActivityStampSink.safeStamp(label: AgentActivityLabel) {
  runCatching { stamp(label) }
}

internal fun AgentRunWorktreeEditObserver.safeObserve() {
  runCatching { observe() }
}
