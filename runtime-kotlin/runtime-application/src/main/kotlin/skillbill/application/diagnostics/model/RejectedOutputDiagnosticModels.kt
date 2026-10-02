package skillbill.application.diagnostics.model

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticInvalidConfigurationMessage
import java.time.Duration

private const val DEFAULT_MAXIMUM_PAYLOAD_BYTES: Long = 1_048_576
private const val DEFAULT_RETENTION_DAYS: Long = 14

data class RejectedOutputDiagnosticConfig(
  val maximumPayloadBytes: Long = DEFAULT_MAXIMUM_PAYLOAD_BYTES,
  val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
) {
  init {
    if (maximumPayloadBytes < 0) {
      throw SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION,
        rejectedOutputDiagnosticInvalidConfigurationMessage("maximumPayloadBytes must be non-negative"),
      )
    }
    if (retention.isNegative) {
      throw SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION,
        rejectedOutputDiagnosticInvalidConfigurationMessage("retention must be non-negative"),
      )
    }
  }
}

data class RejectedOutputDiagnosticRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val rule: String,
  val path: String,
  val reason: String,
  val agentId: String,
  val model: String,
  val rawResponse: ByteArray,
  val observedByteSize: Long = rawResponse.size.toLong(),
  val observedSha256: String = RejectedOutputDiagnosticService.sha256(rawResponse),
  val truncated: Boolean = false,
  val repairTurn: Int = 0,
  val exhaustedFixLoop: Boolean? = null,
)
