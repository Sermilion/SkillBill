package skillbill.infrastructure.skills.nativeagent.composition

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode

internal fun parseCompositionDirective(
  rawValue: String?,
  label: String,
): NativeAgentCompositionDirective? =
  rawValue?.let { value ->
    if (value.isBlank()) {
      invalidCompositionInput("$label: native agent compose directive is required when the compose key is present")
    }
    val kind =
      NativeAgentCompositionKind.entries.firstOrNull { it.wireValue == value }
        ?: invalidCompositionInput("$label: unsupported native agent compose directive '$value'")
    NativeAgentCompositionDirective(kind)
  }

internal fun invalidCompositionInput(message: String): Nothing =
  throw SkillBillRuntimeException(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, message)
