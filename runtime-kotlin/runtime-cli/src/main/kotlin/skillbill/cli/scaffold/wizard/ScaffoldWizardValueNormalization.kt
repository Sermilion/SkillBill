package skillbill.cli.scaffold.wizard

import skillbill.error.shellcontent.invalidScaffoldInputError
import skillbill.scaffold.model.SkillKind
import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

internal fun normalizeWizardKind(value: String): String =
  when (value.trim().lowercase()) {
    "1", "horizontal", "skill" -> SkillKind.HORIZONTAL.wireValue
    "2", "platform", "platform-pack", "pack" -> SkillKind.PLATFORM_PACK.wireValue
    "3", "add-on", "addon" -> SkillKind.ADD_ON.wireValue
    "4", "agent-addon", "agent-addon-skill" -> SkillKind.AGENT_ADDON.wireValue
    else ->
      if (isRetiredPartialScaffoldCommandKindAlias(value)) {
        rejectRetiredPartialScaffoldCommandKind(value)
      } else {
        value
      }
  }

internal fun normalizePlatformPackSourceMode(value: String): String =
  when (value.trim().lowercase()) {
    "1", "native", "in-repo" -> "native"
    "2", "external" -> "external"
    else -> throw invalidScaffoldInputError("Unsupported pack source '$value'. Use native or external.")
  }

internal fun normalizePlatformPackRegistration(value: String): String =
  when (value.trim().lowercase()) {
    "", "create", "1" -> "create"
    "register", "2" -> "register"
    else -> throw invalidScaffoldInputError(
      "Unsupported pack registration '$value'. Use create or register.",
    )
  }

internal fun normalizeAddOnLocationMode(value: String): String =
  when (value.trim().lowercase()) {
    "1", "native", "pack", "pack-owned" -> "native"
    "2", "external" -> "external"
    else -> throw invalidScaffoldInputError("Unsupported add-on source '$value'. Use native or external.")
  }

internal fun normalizeBillSkillName(name: String): String = if (name.startsWith("bill-")) name else "bill-$name"
