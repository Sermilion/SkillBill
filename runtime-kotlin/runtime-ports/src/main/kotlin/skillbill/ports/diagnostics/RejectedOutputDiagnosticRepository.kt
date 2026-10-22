package skillbill.ports.diagnostics

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import java.time.Instant

interface RejectedOutputDiagnosticRepository {
  fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticInsert

  fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic>

  fun read(identity: String): RejectedOutputDiagnosticRead

  fun markExpired(before: Instant): Int

  fun delete(selector: RejectedOutputDiagnosticSelector): Int

  fun retainProducerOutput(evidence: ProducerOutputEvidence)

  fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int = 0,
  ): ProducerOutputEvidence?

  fun deleteProducerOutputsBefore(before: Instant): Int
}

fun interface RejectedOutputDiagnosticMetadataValidator {
  fun validate(metadata: RejectedOutputDiagnostic)
}

fun interface ProducerOutputEvidenceValidator {
  fun validate(evidence: ProducerOutputEvidence)
}

fun interface RejectedOutputDiagnosticPermissions {
  fun applyRestrictivePermissions()
}
