package skillbill.ports.diagnostics.model

import java.time.Instant

enum class RejectedOutputLifecycle { STORED, OVERSIZED, EXPIRED }

data class RejectedOutputDiagnostic(
  val identity: String,
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val rule: String,
  val path: String,
  val reason: String,
  val agentId: String,
  val model: String,
  val recordedAt: Instant,
  val byteSize: Long,
  val sha256: String,
  val lifecycle: RejectedOutputLifecycle,
  val repairTurn: Int = 0,
) {
  init {
    val reason = violation(repairTurn)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(repairTurn: Int): String? =
      if (repairTurn >= 0) null else "Rejected output diagnostic repair turn must not be negative."
  }
}

data class RejectedOutputDiagnosticSelector(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
  val repairTurn: Int? = null,
)

data class RejectedOutputDiagnosticRecord(
  val metadata: RejectedOutputDiagnostic,
  val payload: ByteArray?,
) {
  override fun toString(): String = "RejectedOutputDiagnosticRecord(metadata=$metadata, payload=<hidden>)"
}

data class ProducerOutputEvidence(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val agentId: String,
  val model: String,
  val recordedAt: Instant,
  val byteSize: Long,
  val sha256: String,
  val payload: ByteArray?,
  val generation: Int = 0,
  val repairTurn: Int = 0,
) {
  init {
    require(generation >= 0) { "Producer output evidence generation must not be negative." }
    require(repairTurn >= 0) { "Producer output evidence repair turn must not be negative." }
  }

  override fun toString(): String =
    "ProducerOutputEvidence(workflowId=$workflowId, phaseId=$phaseId, " +
      "generation=$generation, attempt=$attempt, repairTurn=$repairTurn, payload=<hidden>)"
}

fun ProducerOutputEvidence.evidenceKey(): String = "$workflowId:$phaseId:$generation:$attempt:$repairTurn:$agentId"
