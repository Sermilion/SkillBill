package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureTaskRuntimeCorrelation
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
class FeatureTaskRuntimeLifecycleTelemetry(
  private val lifecycleTelemetryService: LifecycleTelemetryService,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun started(request: FeatureTaskRuntimeRunRequest): String =
    isolate("started", "") {
      lifecycleTelemetryService.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRequest(
          featureSize = request.runInvariants.featureSize.name,
          issueKey = request.issueKey,
          featureName = request.runInvariants.specReference,
          sessionId = request.sessionId,
          correlation =
            FeatureTaskRuntimeCorrelation(
              workflowId = request.workflowId,
              goalParentWorkflowId = request.goalContinuation?.parentWorkflowId,
              goalSubtaskId = request.goalContinuation?.subtaskId,
            ),
        ),
      ).toPayload()["session_id"]?.toString().orEmpty()
    }

  internal fun finished(
    report: FeatureTaskRuntimeRunReport,
    context: FeatureTaskRuntimeFinishedTelemetryContext,
  ) {
    if (context.telemetrySessionId.isBlank()) {
      return
    }
    isolate("finished", Unit) {
      emitFeatureTaskRuntimeFinished(
        lifecycleTelemetryService,
        report,
        context,
        completionStatusOf(report),
      )
    }
  }

  internal fun finishedError(
    context: FeatureTaskRuntimeFinishedTelemetryContext,
    error: Throwable? = null,
  ) {
    if (context.telemetrySessionId.isBlank()) {
      return
    }
    isolate("finishedError", Unit) {
      val outcomes =
        runCatching(context.phaseOutcomes)
          .onFailure { error ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "Feature-task-runtime lifecycle telemetry error outcome loading failed; " +
                "emitting terminal error without outcomes.",
              error,
            )
          }
          .getOrDefault(emptyMap())
      emitFeatureTaskRuntimeFinishedError(lifecycleTelemetryService, context, outcomes, error)
    }
  }

  fun prDescriptionGenerated(request: PrDescriptionGeneratedRequest) {
    isolate("prDescriptionGenerated", Unit) {
      lifecycleTelemetryService.prDescriptionGenerated(request)
    }
  }

  private fun <T> isolate(
    stage: String,
    fallback: T,
    block: () -> T,
  ): T =
    runCatching(block)
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Feature-task-runtime lifecycle telemetry $stage emission failed; the run is unaffected.",
          error,
        )
      }
      .getOrDefault(fallback)
}
