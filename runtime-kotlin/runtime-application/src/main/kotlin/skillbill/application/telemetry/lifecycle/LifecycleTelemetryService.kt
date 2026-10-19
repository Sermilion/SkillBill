package skillbill.application.telemetry.lifecycle

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.application.telemetry.validation.validateFeatureVerifyFinished
import skillbill.application.telemetry.validation.validateQualityCheckFinished
import skillbill.application.telemetry.validation.validateQualityCheckStarted
import skillbill.contracts.JsonPayloadContract
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import java.time.Clock

@Inject
class LifecycleTelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
) : GoalLifecycleTelemetryEmitter by
  LifecycleTelemetryGoalEmission(database, settingsProvider, diagnostics, clock, workerSupervisor) {
  fun featureTaskRuntimeStarted(request: FeatureTaskRuntimeStartedRequest): JsonPayloadContract {
    val sessionId = request.sessionId.ifBlank { generateLifecycleSessionId("ftr", clock) }
    return enabledStandaloneResult(settingsProvider, diagnostics, sessionId) { settings ->
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeStarted(request.toRecord(sessionId), settings.level)
      }
    }
  }

  fun featureTaskRuntimeFinished(request: FeatureTaskRuntimeFinishedRequest): JsonPayloadContract =
    enabledStandaloneResult(settingsProvider, diagnostics, request.sessionId) { settings ->
      val reconciledRequest = request.reconcileBlockedRuntimeFields()
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeFinished(reconciledRequest.toRecord(), settings.level)
      }
    }

  fun qualityCheckStarted(request: QualityCheckStartedRequest): JsonPayloadContract {
    val sessionId = generateLifecycleSessionId("qck", clock)
    val normalizedRequest = request.normalizedLabels()
    return when {
      normalizedRequest.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        validateQualityCheckStarted(normalizedRequest)
          ?.let { lifecycleErrorPayload(sessionId, it) }
          ?: enabledStandaloneResult(settingsProvider, diagnostics, sessionId) { settings ->
            database.transaction { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckStarted(
                normalizedRequest.toRecord(sessionId),
                settings.level,
              )
            }
          }
    }
  }

  fun qualityCheckFinished(request: QualityCheckFinishedRequest): JsonPayloadContract {
    val normalizedRequest = request.normalizedLabels()
    return validateQualityCheckFinished(normalizedRequest)
      ?.let { lifecycleErrorPayload(normalizedRequest.sessionId, it) }
      ?: when {
        normalizedRequest.orchestrated ->
          normalizedRequest.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider, diagnostics))
        else ->
          enabledStandaloneResult(settingsProvider, diagnostics, normalizedRequest.sessionId) { settings ->
            database.transaction { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckFinished(
                normalizedRequest.toRecord(),
                settings.level,
              )
            }
          }
      }
  }

  fun featureVerifyStarted(request: FeatureVerifyStartedRequest): JsonPayloadContract {
    val sessionId = generateLifecycleSessionId("fvr", clock)
    return when {
      request.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        enabledStandaloneResult(settingsProvider, diagnostics, sessionId) { settings ->
          database.transaction { unitOfWork ->
            unitOfWork.lifecycleTelemetry.featureVerifyStarted(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }

  fun featureVerifyFinished(request: FeatureVerifyFinishedRequest): JsonPayloadContract =
    validateFeatureVerifyFinished(request)
      ?.let { lifecycleErrorPayload(request.sessionId, it) }
      ?: when {
        request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider, diagnostics))
        else ->
          enabledStandaloneResult(settingsProvider, diagnostics, request.sessionId) { settings ->
            database.transaction { unitOfWork ->
              unitOfWork.lifecycleTelemetry.featureVerifyFinished(request.toRecord(), settings.level)
            }
          }
      }

  fun prDescriptionGenerated(request: PrDescriptionGeneratedRequest): JsonPayloadContract {
    val sessionId = if (request.orchestrated) "" else generateLifecycleSessionId("prd", clock)
    return when {
      request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider, diagnostics))
      else ->
        enabledStandaloneResult(settingsProvider, diagnostics, sessionId) { settings ->
          database.transaction { unitOfWork ->
            unitOfWork.lifecycleTelemetry.prDescriptionGenerated(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }
}
