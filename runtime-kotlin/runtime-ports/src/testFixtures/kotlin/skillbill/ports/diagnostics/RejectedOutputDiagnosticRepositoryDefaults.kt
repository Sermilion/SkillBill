package skillbill.ports.diagnostics

import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticPersistenceMessage
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import java.time.Instant

abstract class RejectedOutputDiagnosticRepositoryDefaults : RejectedOutputDiagnosticRepository {
  open override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    throw SkillBillRuntimeException(
      RejectedOutputDiagnosticFailureCode.PERSISTENCE,
      rejectedOutputDiagnosticPersistenceMessage("producer-evidence-unavailable"),
    )
  }

  open override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? = null

  open override fun deleteProducerOutputsBefore(before: Instant): Int = 0
}
