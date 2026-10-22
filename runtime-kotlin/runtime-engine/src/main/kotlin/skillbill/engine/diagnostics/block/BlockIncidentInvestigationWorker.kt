package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Produces a diagnosis for one claimed block incident without changing its observation. */
fun interface BlockIncidentInvestigator {
  /** Returns the diagnosis to persist for [incident]. */
  fun investigate(incident: BlockIncident): BlockIncidentDiagnosis
}

object DefaultBlockIncidentInvestigator : BlockIncidentInvestigator {
  override fun investigate(incident: BlockIncident): BlockIncidentDiagnosis = BlockIncidentDiagnosis.PARTIAL
}

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
  fun runEligible(limit: Int, observedSince: Instant? = null) {
    val deadline = clock.instant().plus(BlockIncidentService.INVESTIGATION_DEADLINE)
    val candidates = try {
      database.transaction { unit ->
        val activatedAt = unit.blockIncidents.rolloutActivatedAt() ?: return@transaction emptyList()
        unit.blockIncidents.pending(limit).filter { it.occurrence.observedAt >= activatedAt }
      }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      diagnostics.warning("Block incident investigation scan failed.", failure)
      return
    }
    candidates.filter { observedSince == null || it.occurrence.observedAt >= observedSince }.forEach { candidate ->
      if (!clock.instant().isBefore(deadline)) return
      investigate(candidate)
    }
  }

  private fun investigate(candidate: BlockIncident) {
    val identity = processSupervisor.currentProcess()
    val ownerToken = identifiers.randomToken()
    val ownerGeneration = (candidate.state.claim?.ownerGeneration ?: 0L) + 1L
    val now = clock.instant()
    val expiresAt = now.plus(CLAIM_LEASE)
    val claimed = try {
      database.transaction { unit ->
        unit.blockIncidents.claim(
          candidate.occurrence.occurrenceToken,
          candidate.state.revision,
          ownerToken,
          ownerGeneration,
          identity.hostIdentity,
          identity.pid,
          expiresAt,
        )
      }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      diagnostics.warning("Block incident claim failed.", failure)
      return
    }
    if (!claimed) return
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
    val diagnosis = try {
      investigator.investigate(candidate)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      diagnostics.warning("Block incident investigator failed.", failure)
      BlockIncidentDiagnosis.CRASHED
    } finally {
      stopRenewals.set(true)
    }
    try {
      val settled = database.transaction { unit ->
        unit.blockIncidents.settle(candidate.occurrence.occurrenceToken, ownerToken, ownerGeneration, diagnosis, null)
      }
      if (!settled) diagnostics.warning("Block incident settlement was refused; its claim will expire.")
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      diagnostics.warning("Block incident settlement failed; its claim will expire.", failure)
    }
  }

  private fun renew(candidate: BlockIncident, ownerToken: String, ownerGeneration: Long) {
    try {
      database.transaction { unit ->
        unit.blockIncidents.renew(
          candidate.occurrence.occurrenceToken,
          ownerToken,
          ownerGeneration,
          clock.instant().plus(CLAIM_LEASE),
        )
      }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      diagnostics.warning("Block incident claim renewal failed.", failure)
    }
  }

  private companion object {
    val CLAIM_LEASE: Duration = Duration.ofSeconds(90)
    val RENEW_INTERVAL: Duration = Duration.ofSeconds(30)
  }
}
