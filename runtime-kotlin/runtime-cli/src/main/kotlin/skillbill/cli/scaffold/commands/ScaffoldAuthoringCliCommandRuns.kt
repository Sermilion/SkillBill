package skillbill.cli.scaffold.commands

import com.github.ajalt.clikt.core.UsageError

internal fun resolveRenderSkillName(
  positionalSkillName: String?,
  optionSkillName: String?,
): String =
  when {
    positionalSkillName != null && optionSkillName != null ->
      throw UsageError("Provide the skill name either as an argument or with --skill-name, not both.")
    positionalSkillName != null -> requireNotNull(positionalSkillName)
    optionSkillName != null -> requireNotNull(optionSkillName)
    else -> throw UsageError("Provide a skill name as an argument or with --skill-name.")
  }
