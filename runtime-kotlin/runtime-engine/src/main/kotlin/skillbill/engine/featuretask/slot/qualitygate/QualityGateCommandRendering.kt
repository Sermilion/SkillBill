package skillbill.engine.featuretask.slot.qualitygate

import skillbill.contracts.config.projectchecks.ProjectCheckCommand

internal object QualityGateCommandRendering {
  fun renderArgv(argv: List<String>): String = argv.joinToString(" ", transform = ::quotePosixArgument)

  fun renderCommands(commands: List<ProjectCheckCommand>): String =
    commands.mapIndexed { index, command ->
      renderCommandRecord(index + 1, command.workingDirectory, command.argv)
    }.joinToString("\n\n")

  fun renderCommandRecord(
    number: Int,
    workingDirectory: String,
    argv: List<String>,
  ): String =
    "command $number\nworking directory: ${quotePosixArgument(workingDirectory)}\nargv: ${renderArgv(argv)}"

  fun markdownCodeBlock(value: String): String {
    val longestBacktickRun = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestBacktickRun + 1))
    return "$fence\n$value\n$fence"
  }

  private fun quotePosixArgument(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
