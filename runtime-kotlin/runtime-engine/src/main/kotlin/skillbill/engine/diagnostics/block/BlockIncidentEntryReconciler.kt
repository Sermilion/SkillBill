package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryInterruptedReconciliation
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.ports.blockincident.model.BlockIncidentSpoolEntry
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.model.WorkflowStatus
import java.time.Clock
import java.time.Instant

@Inject
class BlockIncidentEntryReconciler(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val spool: BlockIncidentSpool,
  private val worker: BlockIncidentInvestigationWorker,
  private val capture: BlockIncidentCapture,
  private val interruptedRecoveries: BlockIncidentRecoveryInterruptedReconciliation,
) {
  fun reconcileOnEntry() {
    val databaseAvailable =
      diagnostics.bestEffort("Block incident database availability check failed; continuing the run.", false) {
        database.databaseExists()
      }
    if (!databaseAvailable) return
    val spoolDrained = importSpool()
    diagnostics.bestEffort("Block incident reconciliation failed; continuing the run.", Unit) {
      val reconciled =
        database.transaction { unitOfWork ->
          BlockIncidentService.reconcilePending(unitOfWork.blockIncidents, clock, ENTRY_BATCH)
        }
      if (reconciled.isNotEmpty()) diagnostics.info("Reconciled ${reconciled.size} pending block incident(s).")
    }
    diagnostics.bestEffort("Interrupted block incident recovery reconciliation failed; continuing the run.", Unit) {
      val settled = interruptedRecoveries.reconcile(ENTRY_BATCH)
      if (settled > 0) diagnostics.info("Settled $settled interrupted block incident recovery attempt(s).")
    }
    if (spoolDrained) {
      reconcileOrphanedBlocks()
    } else {
      diagnostics.warning("Block incident spool still holds records; deferring uncaptured block recovery.")
    }
    diagnostics.bestEffort("Block incident investigation failed; continuing the run.", Unit) {
      worker.runEligible(ENTRY_BATCH)
    }
  }

  fun <T> guardEntry(
    blocked: (T) -> Boolean,
    run: () -> T,
  ): T {
    val entryStartedAt = clock.instant()
    reconcileOnEntry()
    val result = run()
    if (blocked(result)) investigateAfterEntry(entryStartedAt)
    return result
  }

  fun investigateAfterEntry(entryStartedAt: Instant) {
    diagnostics.bestEffort("New block incident investigation failed; preserving the completed block.", Unit) {
      worker.runEligible(ENTRY_BATCH, entryStartedAt)
    }
  }

  private fun importSpool(): Boolean {
    val entries = diagnostics.bestEffort(SPOOL_SCAN_FAILED, null) { spool.pending(ENTRY_BATCH) } ?: return false
    for (entry in entries) {
      if (!importEntry(entry)) return false
      diagnostics.bestEffort("Imported block incident spool record ${entry.recordId} could not be removed.", Unit) {
        spool.remove(entry.recordId)
      }
    }
    return diagnostics.bestEffort(SPOOL_SCAN_FAILED, false) { spool.pending(1).isEmpty() }
  }

  private fun importEntry(entry: BlockIncidentSpoolEntry): Boolean {
    var entered = false
    val failure =
      recoverableFailureOf {
        database.transaction { unit ->
          entered = true
          BlockIncidentService.importSpooled(unit.blockIncidents, clock, entry.record)
        }
      } ?: return true
    val record = entry.record
    if (entered && failure.isPermanentImportRejection()) {
      diagnostics.warning(
        "Block incident evidence availability UNSAVED. Spool record ${entry.recordId} can never import " +
          "(token ${record.occurrenceToken}, alias ${record.alias.value}, workflow ${record.workflowId}, " +
          "code ${(failure as? SkillBillRuntimeException)?.code}); discarding it.",
        failure,
      )
      return true
    }
    diagnostics.warning("Block incident spool import failed; records remain for retry.", failure)
    return false
  }

  private fun Exception.isPermanentImportRejection(): Boolean =
    this is IllegalArgumentException ||
      this is SkillBillRuntimeException && code in PERMANENT_IMPORT_REJECTIONS

  private fun reconcileOrphanedBlocks() {
    val activatedAt =
      diagnostics.bestEffort("Block incident rollout marker could not be read.", null) {
        database.read { it.blockIncidents.rolloutActivatedAt() }
      } ?: return
    val observations =
      diagnostics.bestEffort("Uncaptured blocked workflow scan failed.", null) {
        database.read { unit ->
          unit.blockIncidents.blockedWithoutIncident(activatedAt, ENTRY_BATCH).mapNotNull { candidate ->
            recoveredObservation(unit.workflowStates, candidate)
          }
        }
      } ?: return
    observations.forEach(capture::capture)
  }

  private fun recoveredObservation(
    states: WorkflowStateRepository,
    candidate: BlockedWorkflowWithoutIncident,
  ): BlockIncidentObservation? =
    diagnostics.bestEffort("Uncaptured block of workflow ${candidate.workflowId} could not be read.", null) {
      states.get(candidate.family, candidate.workflowId)
        ?.takeIf { it.workflowStatus == WorkflowStatus.BLOCKED }
        ?.let { record ->
          val identity =
            diagnostics.bestEffort(
              "Blocked workflow ${candidate.workflowId} has an unreadable block identity; recovering without it.",
              null,
            ) {
              BlockIncidentIdentity.fromArtifacts(record.artifacts)
            }
          BlockIncidentRecoveredObservations.build(candidate, record, identity)
        }
    }

  private companion object {
    const val ENTRY_BATCH = 16
    const val SPOOL_SCAN_FAILED = "Block incident spool scan failed; continuing the run."
    val PERMANENT_IMPORT_REJECTIONS =
      setOf(
        BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
        BlockIncidentFailureCode.INVALID_SCHEMA,
        BlockIncidentFailureCode.QUARANTINE,
      )
  }
}
