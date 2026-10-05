package skillbill.application.telemetry.settings

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.config.loadTelemetrySettingsFromStore
import skillbill.application.telemetry.config.resolveTelemetrySettingsFromStore
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings

@Inject
class DefaultTelemetrySettingsProvider(
  private val context: EnvironmentContext,
  private val configStore: TelemetryConfigStore,
) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    loadTelemetrySettingsFromStore(
      materialize = materialize,
      environment = context.environment,
      configStore = configStore,
    )

  override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
    resolveTelemetrySettingsFromStore(
      materialize = materialize,
      environment = context.environment,
      configStore = configStore,
    )
}
