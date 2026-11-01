package skillbill.application.telemetry.sync

import skillbill.application.telemetry.model.TelemetryOutboxStatusSnapshot
import skillbill.application.telemetry.model.TelemetryStatusResult
import skillbill.application.telemetry.model.TelemetrySyncStatusResult
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.telemetry.model.SyncResult
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Path
import java.time.Instant

object TelemetrySyncRuntime {
  fun disabledSync(settings: TelemetrySettings): SyncResult = disabledSyncResult(settings)

  fun reservedTestIdentitySync(
    settings: TelemetrySettings,
    outboxRepository: TelemetryOutboxRepository,
  ): SyncResult = reservedTestIdentitySyncResult(syncContext(settings, outboxRepository.pendingCount()))

  fun syncTelemetry(
    settings: TelemetrySettings,
    outboxRepository: TelemetryOutboxRepository,
    client: TelemetryClient,
    nowSupplier: () -> Instant,
    interruptSignal: InterruptSignalPort,
  ): SyncResult =
    if (!settings.enabled) {
      disabledSyncResult(settings)
    } else {
      syncEnabledTelemetry(settings, outboxRepository, client, nowSupplier, interruptSignal)
    }

  fun syncResult(result: SyncResult): TelemetrySyncStatusResult =
    TelemetrySyncStatusResult(
      configPath = result.configPath.toString(),
      telemetryEnabled = result.telemetryEnabled,
      telemetryLevel = result.telemetryLevel,
      syncTarget = telemetrySyncTarget(result),
      remoteConfigured = result.remoteConfigured,
      proxyConfigured = result.proxyConfigured,
      proxyUrl = result.proxyUrl,
      customProxyUrl = result.customProxyUrl,
      syncStatus = result.status.wireValue,
      syncedEvents = result.syncedEvents,
      pendingEvents = result.pendingEvents,
      message = result.message,
    )

  fun telemetryStatusPayload(
    dbPath: Path,
    settings: TelemetrySettings,
    outbox: TelemetryOutboxStatusSnapshot = TelemetryOutboxStatusSnapshot(),
  ): TelemetryStatusResult =
    baseStatusResult(dbPath, settings).copy(
      pendingEvents = outbox.pendingEvents,
      latestError = outbox.latestError,
      lastSyncedAt = outbox.lastSyncedAt,
      blockedEvents = outbox.blockedEvents,
    )

  fun autoSyncTelemetry(
    settings: TelemetrySettings,
    outboxRepository: TelemetryOutboxRepository,
    client: TelemetryClient,
    nowSupplier: () -> Instant,
    interruptSignal: InterruptSignalPort,
  ): SyncResult? =
    catchingTelemetryFailure(interruptSignal) {
      syncTelemetry(settings, outboxRepository, client, nowSupplier, interruptSignal)
    }
      .getOrElse { thrown ->
        if (thrown is Exception) null else throw thrown
      }
}

internal fun telemetrySyncTarget(settings: TelemetrySettings): String =
  when {
    !settings.enabled -> "disabled"
    settings.customProxyUrl != null -> "custom_proxy"
    else -> "hosted_relay"
  }

private fun syncEnabledTelemetry(
  settings: TelemetrySettings,
  outboxRepository: TelemetryOutboxRepository,
  client: TelemetryClient,
  nowSupplier: () -> Instant,
  interruptSignal: InterruptSignalPort,
): SyncResult {
  val pendingBefore = outboxRepository.pendingCount()
  val syncContext = syncContext(settings, pendingBefore)
  return when {
    !syncContext.remoteConfigured -> unconfiguredSyncResult(syncContext)
    pendingBefore == 0 -> noopSyncResult(syncContext)
    else ->
      drainPendingBatches(
        DrainRequest(
          outboxRepository = outboxRepository,
          settings = settings,
          client = client,
          syncContext = syncContext,
          nowSupplier = nowSupplier,
          interruptSignal = interruptSignal,
        ),
      )
  }
}
