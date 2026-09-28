package skillbill.engine.operation.verify

import skillbill.application.review.service.ReviewService
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.service.TelemetryService
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.diagnostics.RuntimeDiagnostics

/** The verify run's telemetry, under the `bill-feature-verify` labels; no method may fail the run. */
interface VerifyTelemetry {
  /** Records `feature_verify_started`; the returned session id is blank when nothing was recorded. */
  fun started(request: FeatureVerifyStartedRequest): String

  fun finished(request: FeatureVerifyFinishedRequest)

  /** Imports the code-review register as an orchestrated child review and triages it with no decisions. */
  fun reviewImported(reviewText: String)
}

class ServiceVerifyTelemetry(
  private val lifecycle: LifecycleTelemetryService,
  private val reviews: ReviewService,
  private val telemetry: TelemetryService,
  private val diagnostics: RuntimeDiagnostics,
) : VerifyTelemetry {
  override fun started(request: FeatureVerifyStartedRequest): String =
    isolate("started", "") {
      lifecycle.featureVerifyStarted(request).toPayload()["session_id"]?.toString().orEmpty()
    }

  override fun finished(request: FeatureVerifyFinishedRequest) =
    isolate("finished", Unit) {
      lifecycle.featureVerifyFinished(request)
      Unit
    }

  override fun reviewImported(reviewText: String) =
    isolate("review import", Unit) {
      if (!telemetry.isEnabled()) return@isolate
      val imported = reviews.importReview("-", finishZeroFindingTelemetry = false, stdinText = reviewText)
      val runId = imported.preview.reviewRunId
      reviews.markOrchestrated(runId)
      if (imported.preview.findingCount == 0) {
        reviews.reviewFinishedTelemetryPayload(runId)
      } else {
        reviews.triage(runId, emptyList(), listOnly = false, listWhenNoDecisions = false)
      }
      telemetry.autoSync()
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
          "Verify operation telemetry $stage failed; the run is unaffected.",
          error,
        )
      }
      .getOrDefault(fallback)
}
