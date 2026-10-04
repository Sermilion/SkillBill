package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

internal fun RepositoryEnclosingRootPort.governedSpecPathForCli(
  repositoryRoot: Path,
  specPath: Path,
): String =
  when (
    val result = resolveFeatureTaskGovernedSpecPath(this, repositoryRoot, specPath)
  ) {
    is FeatureTaskGovernedSpecPathResult.Ok -> result.relativePath
    is FeatureTaskGovernedSpecPathResult.OutsideRepository ->
      throw UsageError("Governed spec path must remain inside repository '${result.repositoryRoot}'.")
    FeatureTaskGovernedSpecPathResult.InvalidGovernedPath ->
      throw UsageError("Governed spec path must be Markdown beneath .feature-specs/.")
  }

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
    is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration ->
      "feature-task-runtime $workflowId: phase $phaseId " +
        "${continuationKind ?: "fix_loop"} attempt=$attemptCount iteration=$fixLoopIteration\n"
    is FeatureTaskRuntimeRunEvent.ValidationGateProgress ->
      "feature-task-runtime $workflowId: phase $phaseId gate_run_count=$gateRunCount\n"
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
