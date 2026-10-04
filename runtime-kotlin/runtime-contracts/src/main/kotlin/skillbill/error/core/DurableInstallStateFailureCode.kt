package skillbill.error.core

enum class DurableInstallStateFailureCode : RuntimeFailureCode {
  NATIVE_AGENT_LINK_INVENTORY_DECODE,
  NATIVE_AGENT_LINK_INVENTORY_WRITE,
  NATIVE_AGENT_LINK_INVENTORY_RECONCILE,
  INSTALL_STAGING,
}

fun invalidNativeAgentLinkInventoryDecode(
  path: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    DurableInstallStateFailureCode.NATIVE_AGENT_LINK_INVENTORY_DECODE,
    "Native-agent link inventory '${path.ifBlank { "<unknown>" }}' cannot be decoded: $reason",
    cause,
  )

fun invalidNativeAgentLinkInventoryWrite(
  path: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    DurableInstallStateFailureCode.NATIVE_AGENT_LINK_INVENTORY_WRITE,
    "Native-agent link inventory '${path.ifBlank { "<unknown>" }}' cannot be written: $reason",
    cause,
  )

fun invalidNativeAgentLinkInventoryReconcile(
  path: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    DurableInstallStateFailureCode.NATIVE_AGENT_LINK_INVENTORY_RECONCILE,
    "Native-agent link inventory '${path.ifBlank { "<unknown>" }}' cannot be reconciled: $reason",
    cause,
  )

fun invalidInstallStaging(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    DurableInstallStateFailureCode.INSTALL_STAGING,
    "Install staging '${sourceLabel.ifBlank { "<unknown>" }}' failed: $reason",
    cause,
  )
