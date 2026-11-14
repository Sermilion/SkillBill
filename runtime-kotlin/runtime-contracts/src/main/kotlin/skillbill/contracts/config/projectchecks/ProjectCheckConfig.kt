package skillbill.contracts.config.projectchecks

const val PROJECT_CHECK_CONFIG_CONTRACT_VERSION = "0.1"

object ProjectCheckConfigKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val PROJECT_ID = "project_id"
  const val BUILD_COMMANDS = "build_commands"
  const val LINT_COMMANDS = "lint_commands"
  const val TEST_COMMANDS = "test_commands"
  const val FORMATTING_COMMANDS = "formatting_commands"
  const val VALIDATION_COMMANDS = "validation_commands"
  const val COMMANDS = "commands"
  const val ARGV = "argv"
  const val WORKING_DIRECTORY = "working_directory"
}

enum class ProjectCheckCategory {
  BUILD,
  LINT,
  TEST,
  FORMATTING,
  VALIDATION,
}

data class ProjectCheckCommand(
  val argv: List<String>,
  val workingDirectory: String,
)

data class ProjectCheckCategoryConfig(val commands: List<ProjectCheckCommand>)

data class ProjectCheckConfig(
  val projectId: String,
  val buildCommands: ProjectCheckCategoryConfig?,
  val lintCommands: ProjectCheckCategoryConfig?,
  val testCommands: ProjectCheckCategoryConfig?,
  val formattingCommands: ProjectCheckCategoryConfig?,
  val validationCommands: ProjectCheckCategoryConfig?,
) {
  fun category(category: ProjectCheckCategory): ProjectCheckCategoryConfig? =
    when (category) {
      ProjectCheckCategory.BUILD -> buildCommands
      ProjectCheckCategory.LINT -> lintCommands
      ProjectCheckCategory.TEST -> testCommands
      ProjectCheckCategory.FORMATTING -> formattingCommands
      ProjectCheckCategory.VALIDATION -> validationCommands
    }
}

data class ProjectCheckConfigDiscovery(
  val buildCommands: ProjectCheckCategoryConfig?,
  val lintCommands: ProjectCheckCategoryConfig?,
  val testCommands: ProjectCheckCategoryConfig?,
  val formattingCommands: ProjectCheckCategoryConfig?,
  val validationCommands: ProjectCheckCategoryConfig?,
)
