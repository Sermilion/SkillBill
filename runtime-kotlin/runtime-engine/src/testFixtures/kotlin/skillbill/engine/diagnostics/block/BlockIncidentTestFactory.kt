package skillbill.engine.diagnostics.block

import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.time.Clock

fun testBlockIncidentCapture(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): BlockIncidentCapture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), diagnostics)

fun testBlockIncidentEntryReconciler(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): BlockIncidentEntryReconciler {
  val spool = InMemoryBlockIncidentSpool()
  return BlockIncidentEntryReconciler(
    database,
    clock,
    diagnostics,
    spool,
    BlockIncidentInvestigationWorker(
      database,
      clock,
      DefaultBlockIncidentInvestigator,
      diagnostics,
      IdentifierGeneratorPort { "test-investigator" },
      NoopFeatureTaskRuntimeWorkerSupervisor,
      DaemonThreadPort { _, _ -> },
    ),
    BlockIncidentCapture(database, clock, spool, diagnostics),
  )
}
