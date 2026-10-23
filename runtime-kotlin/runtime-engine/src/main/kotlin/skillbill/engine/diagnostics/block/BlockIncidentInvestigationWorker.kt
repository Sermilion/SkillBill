package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.report.ProductReportDelivery
import skillbill.engine.diagnostics.block.report.ProductReportScheduler
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Produces a diagnosis for one claimed block incident without changing its observation. */
fun interface BlockIncidentInvestigator {
  /**
   * Returns the terminal diagnosis state and the diagnosis record to persist for [claimed]. [workerDegradations]
   * carries what the worker observed before the investigation started, and the record must include them.
   */
  fun investigate(
    claimed: BlockIncident,
    workerDegradations: Set<BlockIncidentDegradation>,
  ): BlockIncidentInvestigationResult
}

data class BlockIncidentInvestigationResult(
  val diagnosis: BlockIncidentDiagnosis,
  val record: BlockIncidentDiagnosisRecord,
)

@Inject
class BlockIncidentInvestigationWorker(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val investigator: BlockIncidentInvestigator,
  private val diagnostics: RuntimeDiagnostics,
  private val identifiers: IdentifierGeneratorPort,
  private val processSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val daemonThread: DaemonThreadPort,
  private val reportScheduler: ProductReportScheduler?,
  private val reportDelivery: ProductReportDelivery?,
  private val telemetry: BlockIncidentTelemetryPort?,
) {
  fun runEligible(
    limit: Int,
    observedSince: Instant? = null,
    dispatchReports: Boolean = true,
  ) {
    val deadline = clock.instant().plus(BlockIncidentService.INVESTIGATION_DEADLINE)
    val candidates =
      diagnostics.bestEffort("Block incident investigation scan failed.", null) {
        database.transaction { unit ->
          val activatedAt = unit.blockIncidents.rolloutActivatedAt() ?: return@transaction emptyList()
          unit.blockIncidents.pending(limit).filter { it.occurrence.observedAt >= activatedAt }
        }
      } ?: return
    candidates.filter { observedSince == null || it.occurrence.observedAt >= observedSince }.forEach { candidate ->
      if (!clock.instant().isBefore(deadline)) return
      investigate(candidate, workerDegradations(candidate, observedSince))
    }
    if (dispatchReports) reportDelivery?.deliver()
  }

  private fun workerDegradations(
    candidate: BlockIncident,
    observedSince: Instant?,
  ): Set<BlockIncidentDegradation> =
    buildSet {
      if (candidate.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING) {
        add(BlockIncidentDegradation.CLAIM_TAKEN_OVER)
      }
      if (candidate.occurrence.evidenceAvailability == BlockIncidentEvidenceAvailability.FALLBACK_SPOOL) {
        add(BlockIncidentDegradation.SPOOL_IMPORTED)
      }
      if (observedSince == null && candidate.state.diagnosis == BlockIncidentDiagnosis.PENDING) {
        add(BlockIncidentDegradation.NOT_PICKED_UP)
      }
    }

  private fun investigate(
    candidate: BlockIncident,
    workerDegradations: Set<BlockIncidentDegradation>,
  ) {
    val identity = processSupervisor.currentProcess()
    val ownerToken = identifiers.randomToken()
    val ownerGeneration = (candidate.state.claim?.ownerGeneration ?: 0L) + 1L
    val now = clock.instant()
    val expiresAt = now.plus(CLAIM_LEASE)
    val claimed =
      diagnostics.bestEffort("Block incident claim failed.", false) {
        database.transaction { unit ->
          unit.blockIncidents.claim(
            candidate.occurrence.occurrenceToken,
            candidate.state.revision,
            BlockIncidentClaim(ownerToken, ownerGeneration, identity.hostIdentity, identity.pid, expiresAt),
          )
        }
      }
    if (!claimed) return
    val takeoverExhausted =
      candidate.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING &&
        candidate.state.claim?.expiresAt?.let { it <= now } == true &&
        candidate.state.retryCount >= BlockIncidentTransitions.MAX_RETRIES
    if (takeoverExhausted) {
      settle(candidate, ownerToken, ownerGeneration, BlockIncidentDiagnosis.CRASHED)
      return
    }
    val stopRenewals = AtomicBoolean(false)
    daemonThread.runWithJoinBudget(
      action = {
        while (!stopRenewals.get()) {
          processSupervisor.pause(RENEW_INTERVAL.toMillis())
          if (!stopRenewals.get()) renew(candidate, ownerToken, ownerGeneration)
        }
      },
      joinBudgetMillis = 1,
    )
    val result =
      try {
        diagnostics.bestEffort("Block incident investigator failed.", null) {
          investigator.investigate(candidate, workerDegradations)
        }
      } finally {
        stopRenewals.set(true)
      }
    if (result == null) {
      settle(candidate, ownerToken, ownerGeneration, BlockIncidentDiagnosis.CRASHED)
      return
    }
    val recorded = recordDiagnosis(candidate, ownerToken, ownerGeneration, result)
    when (recorded) {
      null -> settle(candidate, ownerToken, ownerGeneration, BlockIncidentDiagnosis.PERSIST_FAILED)
      DiagnosisSettlement.Refused ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident diagnosis settlement was refused; its claim will expire.",
        )
      is DiagnosisSettlement.Settled -> publishSettlement(recorded.value, scheduleReport = true)
    }
  }

  private fun recordDiagnosis(
    candidate: BlockIncident,
    ownerToken: String,
    ownerGeneration: Long,
    result: BlockIncidentInvestigationResult,
  ): DiagnosisSettlement? =
    diagnostics.bestEffort("Block incident diagnosis could not be saved.", null) {
      database.transaction { unit ->
        val accepted =
          unit.blockIncidents.settleWithDiagnosis(
            ownerToken,
            ownerGeneration,
            result.diagnosis,
            result.record,
            null,
          )
        if (!accepted) {
          DiagnosisSettlement.Refused
        } else {
          val committed = checkNotNull(unit.blockIncidents.find(candidate.occurrence.occurrenceToken))
          val committedDiagnosis =
            checkNotNull(
              unit.blockIncidents.diagnoses(candidate.occurrence.occurrenceToken)
                .firstOrNull { it.revision == committed.state.revision },
            )
          DiagnosisSettlement.Settled(
            InvestigationSettlement(
              committed.occurrence,
              committed.state.diagnosis,
              committedDiagnosis,
              unit.blockIncidentRecoveries.history(candidate.occurrence.occurrenceToken),
            ),
          )
        }
      }
    }

  private fun settle(
    candidate: BlockIncident,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
  ) {
    val result =
      diagnostics.bestEffort("Block incident settlement failed; its claim will expire.", null) {
        database.transaction { unit ->
          val settled =
            unit.blockIncidents.settle(
              candidate.occurrence.occurrenceToken,
              ownerToken,
              ownerGeneration,
              diagnosis,
              null,
            )
          val committed = if (settled) unit.blockIncidents.find(candidate.occurrence.occurrenceToken) else null
          committed?.let {
            InvestigationSettlement(
              it.occurrence,
              it.state.diagnosis,
              null,
              unit.blockIncidentRecoveries.history(candidate.occurrence.occurrenceToken),
            )
          }
        }
      }
    if (result == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Block incident settlement was refused; its claim will expire.",
      )
    } else {
      publishSettlement(result, scheduleReport = false)
    }
  }

  private fun publishSettlement(
    settlement: InvestigationSettlement,
    scheduleReport: Boolean,
  ) {
    if (scheduleReport) {
      val diagnosis = settlement.diagnosis
      if (diagnosis == null) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Settled block incident diagnosis could not be read; report scheduling was skipped.",
        )
      } else {
        runCatching {
          reportScheduler?.schedule(settlement.occurrence, diagnosis, settlement.recoveryHistory)
        }.getOrElse { error ->
          error.rethrowIfInterrupted()
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Product report scheduling failed after diagnosis settlement.",
            error,
          )
        }
      }
    }
    runCatching {
      telemetry?.diagnosed(settlement.occurrence, settlement.status, settlement.diagnosis)
    }.getOrElse { error ->
      error.rethrowIfInterrupted()
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Block incident telemetry failed after diagnosis settlement.",
        error,
      )
    }
  }

  private fun Throwable.rethrowIfInterrupted() {
    if (this !is Exception) throw this
    if (this is CancellationException || this is InterruptedException ||
      Thread.currentThread().isInterrupted
    ) {
      throw this
    }
  }

  private fun renew(
    candidate: BlockIncident,
    ownerToken: String,
    ownerGeneration: Long,
  ) {
    diagnostics.bestEffort("Block incident claim renewal failed.", Unit) {
      database.transaction { unit ->
        unit.blockIncidents.renew(
          candidate.occurrence.occurrenceToken,
          ownerToken,
          ownerGeneration,
          clock.instant().plus(CLAIM_LEASE),
        )
      }
    }
  }

  private companion object {
    val CLAIM_LEASE: Duration = Duration.ofSeconds(90)
    val RENEW_INTERVAL: Duration = Duration.ofSeconds(30)
  }

  private data class InvestigationSettlement(
    val occurrence: BlockIncidentOccurrence,
    val status: BlockIncidentDiagnosis,
    val diagnosis: BlockIncidentDiagnosisRecord?,
    val recoveryHistory: List<BlockIncidentRecoveryAttempt>,
  )

  private sealed interface DiagnosisSettlement {
    data object Refused : DiagnosisSettlement

    data class Settled(val value: InvestigationSettlement) : DiagnosisSettlement
  }
}
