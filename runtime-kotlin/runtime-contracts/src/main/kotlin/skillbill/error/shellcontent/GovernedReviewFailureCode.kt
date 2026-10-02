package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class GovernedReviewFailureCode : RuntimeFailureCode {
  UNADDRESSED_FINDINGS_LEDGER_ABSENT,
  INVALID_LEDGER_SCHEMA,
  EVIDENCE_TRANSPORT,
  INLINE_PARALLEL_UNSUPPORTED,
  LAUNCH_CAPABILITY,
}

fun governedReviewLaunchCapability(
  provider: String,
  capability: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GovernedReviewFailureCode.LAUNCH_CAPABILITY,
    "Agent '$provider' cannot launch a governed review: missing capability '$capability'.",
  )
