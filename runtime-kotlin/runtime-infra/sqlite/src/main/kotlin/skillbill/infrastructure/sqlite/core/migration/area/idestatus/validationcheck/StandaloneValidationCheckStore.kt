package skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck

import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandaloneValidationCheckRepository
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import java.sql.Connection

internal class StandaloneValidationCheckStore(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean,
) : StandaloneValidationCheckRepository {
  override fun appendValidationCheckEvent(record: ValidationCheckEventRecord): Unit =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      connection.insertValidationCheckEvent(record)
    }

  override fun readValidationCheckEvents(invocationId: String): List<ValidationCheckEventRecord> =
    connection.readValidationCheckEvents(invocationId)

  override fun writeCurrentActivity(
    invocationId: String,
    activity: String,
  ): StandaloneNarrowWriteResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      connection.writeStandaloneCurrentActivity(invocationId, activity)
    }

  override fun writeLiveChecks(
    invocationId: String,
    mode: LiveChecksWriteMode,
  ): StandaloneNarrowWriteResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      connection.writeStandaloneLiveChecks(invocationId, mode)
    }
}
