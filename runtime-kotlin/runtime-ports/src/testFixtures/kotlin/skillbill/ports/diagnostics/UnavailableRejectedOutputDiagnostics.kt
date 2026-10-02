package skillbill.ports.diagnostics

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import java.time.Instant

object UnavailableRejectedOutputDiagnosticRepository : RejectedOutputDiagnosticRepository {
  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticInsert =
    error("Rejected-output diagnostic persistence is unavailable.")

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    error("Rejected-output diagnostic persistence is unavailable.")

  override fun read(identity: String): RejectedOutputDiagnosticRead =
    error("Rejected-output diagnostic persistence is unavailable.")

  override fun markExpired(before: Instant): Int = error("Rejected-output diagnostic persistence is unavailable.")

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int =
    error("Rejected-output diagnostic persistence is unavailable.")

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) =
    error("Rejected-output diagnostic persistence is unavailable.")

  override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? = error("Rejected-output diagnostic persistence is unavailable.")

  override fun deleteProducerOutputsBefore(before: Instant): Int =
    error("Rejected-output diagnostic persistence is unavailable.")
}

object UnavailableRejectedOutputDiagnosticPermissions : RejectedOutputDiagnosticPermissions {
  override fun applyRestrictivePermissions() = error("Rejected-output diagnostic permissions are unavailable.")
}
