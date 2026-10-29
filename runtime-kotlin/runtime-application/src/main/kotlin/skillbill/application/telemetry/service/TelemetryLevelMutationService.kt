package skillbill.application.telemetry.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.config.TelemetryConfigMutations
import skillbill.application.telemetry.config.clearsPendingOutbox
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.telemetry.model.TelemetryLevelMutationResult
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider

@Inject
class TelemetryLevelMutationService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val configStore: TelemetryConfigStore,
) : TelemetryLevelMutator {
  override fun setLevel(level: String): TelemetryLevelMutationResult {
    val (settings, clearedEvents) =
      configStore.withConfigTransaction {
        if (clearsPendingOutbox(settingsProvider.load(materialize = false).level, level) && database.databaseExists()) {
          database.transaction { unitOfWork ->
            TelemetryConfigMutations.setTelemetryLevel(
              level = level,
              configStore = configStore,
              settingsProvider = settingsProvider,
              outbox = unitOfWork.telemetryOutbox,
            )
          }
        } else {
          TelemetryConfigMutations.setTelemetryLevel(
            level = level,
            configStore = configStore,
            settingsProvider = settingsProvider,
          )
        }
      }
    return TelemetryLevelMutationResult(settings, clearedEvents)
  }
}
