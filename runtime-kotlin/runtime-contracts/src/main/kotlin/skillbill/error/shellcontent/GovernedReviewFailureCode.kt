package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class GovernedReviewFailureCode : RuntimeFailureCode {
  UNADDRESSED_FINDINGS_LEDGER_ABSENT,
  INVALID_LEDGER_SCHEMA,
  EVIDENCE_TRANSPORT,
  INLINE_PARALLEL_UNSUPPORTED,
  LAUNCH_CAPABILITY,
  INVALID_EVIDENCE_REQUEST,
}

fun invalidGovernedReviewEvidenceRequest(
  operation: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GovernedReviewFailureCode.INVALID_EVIDENCE_REQUEST,
    "Governed review evidence request '${operation.ifBlank { "<unknown>" }}' is invalid: $reason",
    cause,
  )

fun governedReviewLaunchCapability(
  provider: String,
  capability: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GovernedReviewFailureCode.LAUNCH_CAPABILITY,
    "Agent '$provider' cannot launch a governed review: missing capability '$capability'.",
  )
