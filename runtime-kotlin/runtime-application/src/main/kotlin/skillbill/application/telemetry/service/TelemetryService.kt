package skillbill.application.telemetry.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.RuntimeConfigEditService
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.telemetry.config.clearsPendingOutbox
import skillbill.application.telemetry.model.TelemetryMutationResult
import skillbill.application.telemetry.model.TelemetryOutboxStatusSnapshot
import skillbill.application.telemetry.model.TelemetrySnapshotPriorLevel
import skillbill.application.telemetry.model.TelemetryStatusResult
import skillbill.application.telemetry.model.TelemetrySyncPayload
import skillbill.application.telemetry.settings.loadTelemetrySettings
import skillbill.application.telemetry.settings.mapWorkflow
import skillbill.application.telemetry.settings.telemetryMutationResult
import skillbill.application.telemetry.settings.telemetrySettingsOrNull
import skillbill.application.telemetry.sync.TelemetrySyncRuntime
import skillbill.application.telemetry.sync.catchingTelemetryFailure
import skillbill.application.telemetry.sync.rethrowInterrupted
import skillbill.application.telemetry.sync.syncResult
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TELEMETRY_DELIVERY_ATTEMPT_BUDGET
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.SyncResult
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.model.TelemetrySyncStatus
import skillbill.telemetry.targetsHostedRelayWithReservedTestIdentity
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

internal const val TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE = "telemetry background sync failed"
internal const val TELEMETRY_LEVEL_UNREADABLE = "telemetry level unreadable; runtime exception enqueued without a level"
internal const val TELEMETRY_CAPTURE_FAILURE_SIGNATURE = "telemetry runtime exception capture failed"
internal const val TELEMETRY_RESERVED_TEST_IDENTITY_REFUSAL =
  "skillbill telemetry: record_kind=refusal; seam=telemetry_sync; value_used=skipped; " +
    "value_expected=hosted_relay_delivery; cause=reserved_test_install_id"

@Inject
class TelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
  private val levelMutationService: TelemetryLevelMutationService,
  private val diagnostics: RuntimeDiagnostics,
  private val interruptSignal: InterruptSignalPort,
) {
  fun isEnabled(): Boolean = telemetrySettingsOrNull(settingsProvider, diagnostics)?.enabled ?: false

  fun status(): TelemetryStatusResult {
    val dbPath = database.resolveDbPath()
    val settings = loadTelemetrySettings(settingsProvider)
    if (!database.databaseExists()) {
      return TelemetrySyncRuntime.telemetryStatusPayload(dbPath, settings)
    }
    return database.read { unitOfWork ->
      TelemetrySyncRuntime.telemetryStatusPayload(
        dbPath = unitOfWork.dbPath,
        settings = settings,
        outbox =
          TelemetryOutboxStatusSnapshot(
            pendingEvents = unitOfWork.telemetryOutbox.pendingCount(),
            latestError = unitOfWork.telemetryOutbox.latestError(),
            lastSyncedAt = unitOfWork.telemetryOutbox.lastSyncedAt(),
            blockedEvents = unitOfWork.telemetryOutbox.blockedCount(TELEMETRY_DELIVERY_ATTEMPT_BUDGET),
          ),
      )
    }
  }

  fun sync(): TelemetrySyncPayload {
    val settings = loadTelemetrySettings(settingsProvider)
    val result =
      if (!settings.enabled) {
        TelemetrySyncRuntime.disabledSync(settings)
      } else if (settings.targetsHostedRelayWithReservedTestIdentity()) {
        refuseReservedTestIdentitySync(settings)
      } else {
        reconcileBeforeSync(
          TelemetryReconciliationRequest(level = settings.level, cadenceSeconds = 0L, now = clock.instant()),
        )
        TelemetrySyncRuntime.syncTelemetry(
          settings,
          sessionTelemetryOutboxRepository(database),
          telemetryClient,
          clock::instant,
          interruptSignal,
        )
      }
    return TelemetrySyncPayload(
      exitCode = if (result.status == TelemetrySyncStatus.FAILED) 1 else 0,
      result = TelemetrySyncRuntime.syncResult(result),
    )
  }

  fun autoSync() {
    val settings = telemetrySettingsOrNull(settingsProvider, diagnostics)
    if (settings == null || !settings.enabled || !database.databaseExists()) return
    if (settings.targetsHostedRelayWithReservedTestIdentity()) {
      refuseReservedTestIdentitySync(settings)
      return
    }
    reconcileBeforeSync(TelemetryReconciliationRequest(level = settings.level, now = clock.instant()))
    try {
      val result =
        TelemetrySyncRuntime.autoSyncTelemetry(
          settings,
          sessionTelemetryOutboxRepository(database),
          telemetryClient,
          clock::instant,
          interruptSignal,
        )
      if (result == null || result.status == TelemetrySyncStatus.FAILED) {
        recordBackgroundSyncFailure(null)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (interrupted: InterruptedException) {
      rethrowInterrupted(interrupted, interruptSignal)
    }
  }

  private fun refuseReservedTestIdentitySync(settings: TelemetrySettings): SyncResult {
    diagnostics.warning(TELEMETRY_RESERVED_TEST_IDENTITY_REFUSAL)
    return TelemetrySyncRuntime.reservedTestIdentitySync(settings, sessionTelemetryOutboxRepository(database))
  }

  private fun recordBackgroundSyncFailure(cause: Throwable?) {
    diagnostics.warning(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE, cause)
    catchingTelemetryFailure(interruptSignal) {
      if (!database.databaseExists()) return@catchingTelemetryFailure
      enqueueRuntimeException(
        sessionTelemetryOutboxRepository(database),
        "telemetry_background_sync",
        cause as? Exception ?: Exception(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE),
        telemetryLevelOrEmpty(),
      )
    }.exceptionOrNull()?.let { diagnostics.warning(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE, it) }
  }

  fun setLevel(level: String): TelemetryMutationResult {
    val result = levelMutationService.setLevel(level)
    val settings = result.settings
    val clearedEvents = result.clearedEvents
    return telemetryMutationResult(settings, clearedEvents)
  }

  fun setLevel(
    level: String,
    expected: ConfigFingerprint?,
    config: RuntimeConfigEditService,
  ): ConfigWriteResult {
    val document = config.readMachineConfig()
    configReadFailure(document, expected)?.let { return it }
    val path =
      when (document) {
        is ConfigDocumentRead.Present -> document.path
        is ConfigDocumentRead.Absent -> document.path
        is ConfigDocumentRead.Unreadable -> document.path
      }
    val currentLevel =
      when (val current = settingsProvider.loadOrUnavailable(materialize = false)) {
        is TelemetrySettingsLoad.Loaded -> current.settings.level
        is TelemetrySettingsLoad.Unavailable -> return ConfigWriteResult.Unreadable(path, current.reason)
      }
    val result = config.writeTelemetryLevel(level, expected)
    if (result !is ConfigWriteResult.Written) return result
    val clearOutbox = clearsPendingOutbox(currentLevel, level) && database.databaseExists()
    if (clearOutbox) database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.clear() }
    return result
  }

  fun setLevelFromSnapshot(
    level: String,
    observedPath: Path,
    expected: ConfigFingerprint?,
    previousLevel: String,
    config: RuntimeConfigEditService,
  ): ConfigWriteResult =
    setLevelFromSnapshot(
      level,
      observedPath,
      expected,
      TelemetrySnapshotPriorLevel.Known(previousLevel),
      config,
    )

  fun setLevelFromSnapshot(
    level: String,
    observedPath: Path,
    expected: ConfigFingerprint?,
    previousLevel: TelemetrySnapshotPriorLevel,
    config: RuntimeConfigEditService,
  ): ConfigWriteResult {
    val result = config.writeTelemetryLevelFromSnapshot(observedPath, level, expected)
    if (result !is ConfigWriteResult.Written) return result
    if (clearsPendingOutbox(previousLevel, level) && database.databaseExists()) {
      database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.clear() }
    }
    return result
  }

  private fun configReadFailure(
    document: ConfigDocumentRead<*>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult? =
    when (document) {
      is ConfigDocumentRead.Present ->
        if (document.fingerprint != expected) ConfigWriteResult.ChangedOnDisk(document.path) else null
      is ConfigDocumentRead.Absent ->
        if (expected != null) ConfigWriteResult.ChangedOnDisk(document.path) else null
      is ConfigDocumentRead.Unreadable -> ConfigWriteResult.Unreadable(document.path, document.reason, document.keyPath)
    }

  fun capabilities(): TelemetryProxyCapabilities =
    telemetryClient.fetchProxyCapabilities(
      loadTelemetrySettings(settingsProvider),
    )

  fun remoteStats(
    workflow: String,
    since: String,
    dateFrom: String,
    dateTo: String,
    groupBy: String,
  ): TelemetryRemoteStatsResult =
    remoteStats(
      RemoteStatsRequest(mapWorkflow(workflow), since, dateFrom, dateTo, groupBy),
    )

  fun remoteStats(request: RemoteStatsRequest): TelemetryRemoteStatsResult =
    telemetryClient.fetchRemoteStats(loadTelemetrySettings(settingsProvider), request)

  fun captureException(
    workflowPhase: String,
    error: Exception,
  ) {
    if (!database.databaseExists()) return
    catchingTelemetryFailure(interruptSignal) {
      enqueueRuntimeException(sessionTelemetryOutboxRepository(database), workflowPhase, error, telemetryLevelOrEmpty())
    }.onFailure { diagnostics.warning(TELEMETRY_CAPTURE_FAILURE_SIGNATURE, it) }
  }

  private fun telemetryLevelOrEmpty(): String =
    catchingTelemetryFailure(interruptSignal) { telemetrySettingsOrNull(settingsProvider, diagnostics)?.level }
      .getOrElse { error ->
        diagnostics.warning(TELEMETRY_LEVEL_UNREADABLE, error)
        null
      }
      .orEmpty()

  private fun reconcileBeforeSync(request: TelemetryReconciliationRequest) {
    if (!database.databaseExists()) return
    catchingTelemetryFailure(interruptSignal) {
      database.transaction { unitOfWork ->
        unitOfWork.telemetryReconciliation.reconcileStaleSessions(request)
      }
    }.onFailure { error ->
      if (error !is Exception) throw error
      captureException("telemetry_stale_session_reconciliation", error)
    }
  }
}

private fun sessionTelemetryOutboxRepository(database: DatabaseSessionFactory): TelemetryOutboxRepository =
  object : TelemetryOutboxRepository {
    override fun enqueue(
      event: TelemetryOutboxEvent,
      payloadJson: String,
    ): Long = database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.enqueue(event, payloadJson) }

    override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> =
      database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.claimPending(request) }

    override fun pendingCount(): Int = database.read { unitOfWork -> unitOfWork.telemetryOutbox.pendingCount() }

    override fun blockedCount(attemptBudget: Int): Int =
      database.read { unitOfWork -> unitOfWork.telemetryOutbox.blockedCount(attemptBudget) }

    override fun latestError(): String? = database.read { unitOfWork -> unitOfWork.telemetryOutbox.latestError() }

    override fun lastSyncedAt(): String? = database.read { unitOfWork -> unitOfWork.telemetryOutbox.lastSyncedAt() }

    override fun latestByCorrelation(
      events: Set<TelemetryOutboxEvent>,
      incidentToken: String,
    ): TelemetryCorrelationStatus? =
      database.read { unitOfWork -> unitOfWork.telemetryOutbox.latestByCorrelation(events, incidentToken) }

    override fun markSynced(
      eventIds: List<Long>,
      claimToken: String,
    ): TelemetryOutboxSettlementResult =
      database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.markSynced(eventIds, claimToken) }

    override fun markFailed(
      eventIds: List<Long>,
      claimToken: String,
      lastError: String,
    ): TelemetryOutboxSettlementResult =
      database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.markFailed(eventIds, claimToken, lastError) }

    override fun markUnconfirmed(
      eventIds: List<Long>,
      claimToken: String,
      lastError: String,
    ): TelemetryOutboxSettlementResult =
      database.transaction {
          unitOfWork ->
        unitOfWork.telemetryOutbox.markUnconfirmed(eventIds, claimToken, lastError)
      }

    override fun clear(): Int = database.transaction { unitOfWork -> unitOfWork.telemetryOutbox.clear() }
  }
