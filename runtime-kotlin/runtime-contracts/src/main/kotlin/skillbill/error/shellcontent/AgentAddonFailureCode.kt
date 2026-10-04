package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class AgentAddonFailureCode : RuntimeFailureCode {
  INVALID_SCHEMA,
  MISSING_DECLARATION,
  INVALID_SELECTION,
  SELECTION_DRIFT,
  INVALID_DELIVERY,
}

fun invalidAgentAddonSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AgentAddonFailureCode.INVALID_SCHEMA,
    "Agent add-on '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

fun missingAgentAddonDeclaration(
  slug: String,
  expectedRoot: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AgentAddonFailureCode.MISSING_DECLARATION,
    "Required agent add-on '$slug' was not found under '$expectedRoot'.",
  )

fun invalidAgentAddonDeliveryTarget(
  slug: String,
  target: String,
  reason: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AgentAddonFailureCode.INVALID_DELIVERY,
    "Agent add-on '$slug' has invalid delivery target '$target': $reason",
  )

fun agentAddonPointerCollision(pointerName: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AgentAddonFailureCode.INVALID_DELIVERY,
    "Agent add-on pointer '$pointerName' collides in the portable staging namespace.",
  )
