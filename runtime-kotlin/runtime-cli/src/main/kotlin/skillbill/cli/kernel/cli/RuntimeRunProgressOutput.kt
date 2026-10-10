package skillbill.cli.kernel.cli

import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink

internal fun runtimeRunEventSink(
  inputs: CliRunInputs,
  monitor: Boolean,
): FeatureTaskRuntimeRunEventSink =
  if (!monitor) {
    FeatureTaskRuntimeRunEventSink.NONE
  } else {
    FeatureTaskRuntimeRunEventSink { event ->
      inputs.liveStdout(event.runtimeProgressLine())
    }
  }

internal fun FeatureTaskRuntimeRunEvent.runtimeProgressLine(): String =
  when (this) {
    is FeatureTaskRuntimeRunEvent.RunStarted ->
      "feature-task-runtime $workflowId: run started feature_size=$featureSize\n"
    is FeatureTaskRuntimeRunEvent.BranchResolved ->
      "feature-task-runtime $workflowId: branch ${if (reused) "reused" else "created"} $branch\n"
    is FeatureTaskRuntimeRunEvent.BranchSetupBlocked ->
      "feature-task-runtime $workflowId: branch setup blocked at phase $phaseId: $blockedReason\n"
    is FeatureTaskRuntimeRunEvent.PhaseStarted -> progressLine()
    is FeatureTaskRuntimeRunEvent.PhaseLoopEdge ->
      "feature-task-runtime $workflowId: phase $phaseId $continuationKind loop=$loopId " +
        "edge_iteration=$edgeIteration driving_verdict=$drivingVerdict\n"
    is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> progressLine()
    is FeatureTaskRuntimeRunEvent.ValidationGateProgress ->
      "feature-task-runtime $workflowId: phase $phaseId gate_run_count=$gateRunCount\n"
    is FeatureTaskRuntimeRunEvent.CiStillRunning ->
      "feature-task-runtime $workflowId: phase $phaseId CI still running pending=${pendingChecks.joinToString(",")}\n"
    is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
      "feature-task-runtime $workflowId: phase $phaseId completed agent=$resolvedAgentId attempt=$attemptCount\n"
    is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
      "feature-task-runtime $workflowId: phase $phaseId blocked attempt=$attemptCount: $blockedReason\n"
    is FeatureTaskRuntimeRunEvent.PhasePaused ->
      "feature-task-runtime $workflowId: phase $phaseId paused attempt=$attemptCount: $pauseReason\n"
    is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning ->
      "feature-task-runtime $workflowId: decomposed at planning into $subtaskCount subtasks: $reason. " +
        "Work the first subtask first.\n"
  }

internal fun FeatureTaskRuntimeRunEvent.PhaseStarted.progressLine(): String =
  "feature-task-runtime $workflowId: phase $phaseId ${if (resumed) "resumed" else "started"} " +
    "agent=$resolvedAgentId attempt=$attemptCount" +
    model?.let { " model=$it" }.orEmpty() +
    effort?.let { " effort=$it" }.orEmpty() +
    continuationKind?.let { " continuation=$it" }.orEmpty() +
    "\n"

internal fun FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration.progressLine(): String =
  "feature-task-runtime $workflowId: phase $phaseId " +
    "${continuationKind ?: "fix_loop"} attempt=$attemptCount iteration=$fixLoopIteration\n"
