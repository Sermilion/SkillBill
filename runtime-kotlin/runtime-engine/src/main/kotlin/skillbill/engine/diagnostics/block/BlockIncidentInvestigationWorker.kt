package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
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
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
) {
  fun runEligible(
    limit: Int,
    observedSince: Instant? = null,
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
    val recorded =
      diagnostics.bestEffort("Block incident diagnosis could not be saved.", null) {
        database.transaction { unit ->
          unit.blockIncidents.settleWithDiagnosis(
            ownerToken,
            ownerGeneration,
            result.diagnosis,
            result.record,
            null,
          )
        }
      }
    when (recorded) {
      null -> settle(candidate, ownerToken, ownerGeneration, BlockIncidentDiagnosis.PERSIST_FAILED)
      false -> diagnostics.warning("Block incident diagnosis settlement was refused; its claim will expire.")
      true -> Unit
    }
  }

  private fun settle(
    candidate: BlockIncident,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
  ) {
    diagnostics.bestEffort("Block incident settlement failed; its claim will expire.", Unit) {
      val settled =
        database.transaction { unit ->
          unit.blockIncidents.settle(candidate.occurrence.occurrenceToken, ownerToken, ownerGeneration, diagnosis, null)
        }
      if (!settled) diagnostics.warning("Block incident settlement was refused; its claim will expire.")
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
}
