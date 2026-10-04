package skillbill.cli.scaffold.commands

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

@Inject
class ScaffoldTopLevelCommands(
  authoringRead: ScaffoldAuthoringReadCliSubcommands,
  authoringWrite: ScaffoldAuthoringWriteCliSubcommands,
  newCommands: ScaffoldNewCliSubcommands,
) {
  val commands: List<CliktCommand> = authoringRead.commands + authoringWrite.commands + newCommands.commands
}
