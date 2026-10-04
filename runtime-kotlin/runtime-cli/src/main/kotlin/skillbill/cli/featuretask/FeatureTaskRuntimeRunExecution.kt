package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.service.TelemetryService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.drainTelemetryOnCompletion
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.time.Duration.Companion.minutes

@Inject
class FeatureTaskRuntimeRunExecution(
  private val entry: FeatureTaskRuntimeRunEntry,
  private val telemetryService: TelemetryService,
  private val diagnostics: RuntimeDiagnostics,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) {
  internal fun run(
    options: FeatureTaskRuntimePhaseAgentCommand,
    prepared: PreparedRuntimeRun,
    explicitWorkflowId: String?,
  ) {
    val input =
      FeatureTaskRuntimeRunInput(
        issueKey = prepared.issueKey,
        specPath = prepared.specPath,
        repoRoot = prepared.repoRoot,
        explicitWorkflowId = explicitWorkflowId,
        invokedAgentId = prepared.invokedAgentId,
        agentAssignment = prepared.agentAssignment,
        modelAssignment = prepared.modelAssignment,
        compactionSettings = prepared.compactionSettings,
        environment = inputs.environment,
        timeout = options.maxWallClockMinutes.takeIf { it > 0 }?.minutes,
        requestedCodeReviewMode = options.requestedCodeReviewMode(),
        goalContinuation = prepared.goalContinuation,
        operatorDecision = prepared.operatorDecision,
        agentAddonSelection = prepared.agentAddonSelection,
        eventSink = runtimeRunEventSink(inputs, options.monitor),
      )
    val report =
      inputs.featureTaskRuntimeRunOverride?.invoke(input)
        ?: entry.run(input) { throw UsageError("Could not open a feature-task workflow: ${it.error}") }
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(report), payload, exitCode = report.runtimeRunExitCode())
    drainTelemetryOnCompletion(telemetryService, diagnostics)
  }
}
