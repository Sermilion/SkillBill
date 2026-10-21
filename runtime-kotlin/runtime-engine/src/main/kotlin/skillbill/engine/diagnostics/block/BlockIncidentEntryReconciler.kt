package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock

/**
 * Runs one bounded block-incident reconciliation pass when a run entry starts, so incidents left pending or
 * investigating by an earlier process reach their deadline state without a separate command. A failed pass is
 * reported and the run continues.
 */
@Inject
class BlockIncidentEntryReconciler(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun reconcileOnEntry() {
    if (!database.databaseExists()) return
    try {
      val reconciled = database.transaction { unitOfWork ->
        BlockIncidentService.reconcilePending(unitOfWork.blockIncidents, clock, ENTRY_BATCH)
      }
      if (reconciled.isNotEmpty()) diagnostics.info("Reconciled ${reconciled.size} pending block incident(s).")
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident reconciliation failed; continuing the run.", failure)
    }
  }

  private companion object {
    const val ENTRY_BATCH = 16
  }
}
