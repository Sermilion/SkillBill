package skillbill.application.telemetry.settings

import skillbill.application.telemetry.model.TelemetryMutationResult
import skillbill.application.telemetry.sync.telemetrySyncTarget
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.telemetry.model.TelemetrySettings

internal const val TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE =
  "Telemetry settings could not be loaded; treating telemetry as disabled."

internal fun loadTelemetrySettings(settingsProvider: TelemetrySettingsProvider): TelemetrySettings =
  settingsProvider.load()

internal fun telemetrySettingsOrNull(
  settingsProvider: TelemetrySettingsProvider,
  diagnostics: RuntimeDiagnostics,
): TelemetrySettings? =
  when (val result = settingsProvider.loadOrUnavailable()) {
    is TelemetrySettingsLoad.Loaded -> result.settings
    is TelemetrySettingsLoad.Unavailable -> telemetrySettingsLoadFailure(diagnostics, result.reason)
  }

private fun telemetrySettingsLoadFailure(
  diagnostics: RuntimeDiagnostics,
  reason: String,
): Nothing? {
  diagnostics.error(TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE, IllegalArgumentException(reason))
  return null
}

internal fun feedbackTelemetryOptions(
  settingsProvider: TelemetrySettingsProvider,
  diagnostics: RuntimeDiagnostics,
): FeedbackTelemetryOptions {
  val settings = telemetrySettingsOrNull(settingsProvider, diagnostics)
  return FeedbackTelemetryOptions(
    enabled = settings?.enabled ?: false,
    level = settings?.level ?: "off",
  )
}

internal fun telemetryMutationResult(
  settings: TelemetrySettings,
  clearedEvents: Int,
): TelemetryMutationResult =
  TelemetryMutationResult(
    configPath = settings.configPath.toString(),
    telemetryEnabled = settings.enabled,
    telemetryLevel = settings.level,
    syncTarget = telemetrySyncTarget(settings),
    remoteConfigured = settings.proxyUrl.isNotBlank(),
    proxyConfigured = settings.customProxyUrl != null,
    proxyUrl = settings.proxyUrl,
    customProxyUrl = settings.customProxyUrl,
    installId = settings.installId,
    clearedEvents = clearedEvents,
  )

internal fun mapWorkflow(workflow: String): String =
  when (workflow) {
    "verify", "bill-feature-verify" -> "bill-feature-verify"
    "implement", "feature-task-prose" -> "feature-task-prose"
    "feature-task-runtime" -> "feature-task-runtime"
    else -> throw IllegalArgumentException("workflow must be one of: verify, implement, feature-task-runtime.")
  }
