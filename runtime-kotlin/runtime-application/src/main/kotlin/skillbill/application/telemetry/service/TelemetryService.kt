package skillbill.application.telemetry.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.TelemetryMutationResult
import skillbill.application.telemetry.model.TelemetryOutboxStatusSnapshot
import skillbill.application.telemetry.model.TelemetryStatusResult
import skillbill.application.telemetry.model.TelemetrySyncPayload
import skillbill.application.telemetry.settings.loadTelemetrySettings
import skillbill.application.telemetry.settings.mapWorkflow
import skillbill.application.telemetry.settings.telemetryMutationResult
import skillbill.application.telemetry.settings.telemetrySettingsOrNull
import skillbill.application.telemetry.sync.TelemetrySyncRuntime
import skillbill.application.telemetry.sync.syncResult
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TELEMETRY_DELIVERY_ATTEMPT_BUDGET
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
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
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

internal const val TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE = "telemetry background sync failed"
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
      rethrowTelemetryFailure(cancelled)
    } catch (interrupted: InterruptedException) {
      rethrowTelemetryInterrupted(interrupted, interruptSignal)
    }
  }

  private fun refuseReservedTestIdentitySync(settings: TelemetrySettings): SyncResult {
    diagnostics.warning(TELEMETRY_RESERVED_TEST_IDENTITY_REFUSAL)
    return TelemetrySyncRuntime.reservedTestIdentitySync(settings, sessionTelemetryOutboxRepository(database))
  }

  private fun recordBackgroundSyncFailure(cause: Throwable?) {
    diagnostics.warning(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE, cause)
    val enqueueResult =
      runCatching {
        if (!database.databaseExists()) {
          return@runCatching
        }
        val level =
          runCatching { telemetrySettingsOrNull(settingsProvider, diagnostics)?.level }
            .getOrElse { thrown ->
              rethrowIfCooperative(thrown)
              null
            }
            .orEmpty()
        enqueueRuntimeException(
          sessionTelemetryOutboxRepository(database),
          "telemetry_background_sync",
          cause as? Exception ?: Exception(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE),
          level,
        )
      }
    enqueueResult.exceptionOrNull()?.let { thrown ->
      rethrowIfCooperative(thrown)
      diagnostics.warning(TELEMETRY_BACKGROUND_SYNC_FAILURE_SIGNATURE, thrown)
    }
  }

  fun setLevel(level: String): TelemetryMutationResult {
    val result = levelMutationService.setLevel(level)
    val settings = result.settings
    val clearedEvents = result.clearedEvents
    return telemetryMutationResult(settings, clearedEvents)
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
    val level =
      runCatching { telemetrySettingsOrNull(settingsProvider, diagnostics)?.level }
        .getOrElse { thrown ->
          rethrowIfCooperative(thrown)
          null
        }
        .orEmpty()
    val enqueueResult =
      runCatching {
        enqueueRuntimeException(sessionTelemetryOutboxRepository(database), workflowPhase, error, level)
      }
    enqueueResult.exceptionOrNull()?.let(::rethrowIfCooperative)
  }

  private fun rethrowIfCooperative(error: Throwable): Nothing? =
    when (error) {
      is CancellationException -> throw error
      is InterruptedException -> rethrowTelemetryInterrupted(error, interruptSignal)
      else -> null
    }

  private fun reconcileBeforeSync(request: TelemetryReconciliationRequest) {
    if (!database.databaseExists()) return
    runCatching {
      database.transaction { unitOfWork ->
        unitOfWork.telemetryReconciliation.reconcileStaleSessions(request)
      }
    }.onFailure { error ->
      when (error) {
        is CancellationException -> rethrowTelemetryFailure(error)
        is InterruptedException -> rethrowTelemetryInterrupted(error, interruptSignal)
        is Exception -> captureException("telemetry_stale_session_reconciliation", error)
        else -> rethrowTelemetryFailure(error)
      }
    }
  }
}

private fun rethrowTelemetryFailure(error: Throwable): Nothing = throw error

private fun rethrowTelemetryInterrupted(
  error: InterruptedException,
  interruptSignal: InterruptSignalPort,
): Nothing {
  runCatching { interruptSignal.restore() }.exceptionOrNull()?.let { restorationFailure ->
    if (restorationFailure !== error) {
      error.addSuppressed(restorationFailure)
    }
  }
  throw error
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
