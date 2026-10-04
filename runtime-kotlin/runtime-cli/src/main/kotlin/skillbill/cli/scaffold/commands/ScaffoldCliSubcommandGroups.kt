package skillbill.cli.scaffold.commands

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

@Inject
class ScaffoldAuthoringReadCliSubcommands(
  list: ListSkillsCommand,
  show: ShowSkillCommand,
  explain: ExplainSkillCommand,
  validate: ValidateSkillCommand,
) {
  val commands: List<CliktCommand> = listOf(list, show, explain, validate)
}

@Inject
class ScaffoldAuthoringWriteCliSubcommands(
  upgrade: UpgradeSkillsCommand,
  render: RenderSkillsCommand,
  edit: EditSkillCommand,
  fill: FillSkillCommand,
) {
  val commands: List<CliktCommand> = listOf(upgrade, render, edit, fill)
}

@Inject
class ScaffoldNewCliSubcommands(
  newSkill: NewSkillCommand,
  newAlias: NewCommand,
  createAndFill: CreateAndFillCommand,
  newAddon: NewAddonCommand,
) {
  val commands: List<CliktCommand> = listOf(newSkill, newAlias, createAndFill, newAddon)
}
