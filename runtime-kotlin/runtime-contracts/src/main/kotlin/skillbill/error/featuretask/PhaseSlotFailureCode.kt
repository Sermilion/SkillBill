package skillbill.error.featuretask

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class PhaseSlotFailureCode : RuntimeFailureCode {
  VALIDATION_SCOPE,
  UNKNOWN_SKELETON_DEFINITION,
  UNKNOWN_PHASE_REVIEW_TARGET,
  INTAKE_REQUIRED,
  PULL_REQUEST_BRANCH_REFUSED,
  UNKNOWN_PHASE_STRATEGY,
  INVALID_STRATEGY_COMPOSITION,
}

fun invalidPhaseStrategyCompositionFailure(reason: String) =
  SkillBillRuntimeException(
    PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION,
    "Invalid phase strategy composition: $reason",
  )
