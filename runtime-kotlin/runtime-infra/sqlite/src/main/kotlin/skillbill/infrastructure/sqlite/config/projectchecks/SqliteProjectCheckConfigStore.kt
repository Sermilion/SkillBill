package skillbill.infrastructure.sqlite.config.projectchecks

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_CONFIG_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.ports.config.projectchecks.ProjectCheckConfigRepository
import skillbill.ports.config.projectchecks.ProjectCheckConfigValidator
import java.nio.file.Path
import java.sql.Connection

internal class SqliteProjectCheckConfigStore(
  private val connection: Connection,
  private val databasePath: Path,
  private val validator: ProjectCheckConfigValidator,
) : ProjectCheckConfigRepository {
  override fun fillMissing(
    projectId: String,
    discovery: ProjectCheckConfigDiscovery,
  ): ProjectCheckConfig {
    validator.validate(encode(discovery.asConfig(projectId)), projectId, databasePath.toString())
    val existing = load(projectId)
    if (existing == null) insertUnknown(projectId)
    val current =
      existing ?: load(projectId) ?: throw SkillBillRuntimeException(
        ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID,
        "Project check configuration row for project '$projectId' disappeared in $databasePath. " +
          "Preserve a copy and repair the offending row with a compatible runtime.",
      )
    val merged =
      ProjectCheckConfig(
        projectId,
        current.buildCommands ?: discovery.buildCommands,
        current.lintCommands ?: discovery.lintCommands,
        current.testCommands ?: discovery.testCommands,
        current.formattingCommands ?: discovery.formattingCommands,
        current.validationCommands ?: discovery.validationCommands,
      )
    val encoded = encode(merged)
    validator.validate(encoded, projectId, databasePath.toString())
    connection.prepareStatement(SqliteProjectCheckConfigSql.UPDATE).use { statement ->
      ProjectCheckCategory.entries.forEachIndexed { index, category ->
        statement.setString(index + 1, categoryJson(merged.category(category)))
      }
      statement.setString(ProjectCheckCategory.entries.size + 1, projectId)
      statement.executeUpdate()
    }
    return load(projectId) ?: throw SkillBillRuntimeException(
      ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID,
      "Project check configuration row for project '$projectId' disappeared in $databasePath.",
    )
  }

  private fun insertUnknown(projectId: String) {
    connection.prepareStatement(SqliteProjectCheckConfigSql.INSERT_UNKNOWN).use { statement ->
      statement.setString(1, projectId)
      statement.setString(2, PROJECT_CHECK_CONFIG_CONTRACT_VERSION)
      statement.executeUpdate()
    }
  }

  override fun load(projectId: String): ProjectCheckConfig? =
    connection.prepareStatement(SqliteProjectCheckConfigSql.SELECT).use { statement ->
      statement.setString(1, projectId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        val raw =
          JsonCodec.mapToJsonObject(
            linkedMapOf(
              ProjectCheckConfigKeys.CONTRACT_VERSION to rows.getString(SqliteProjectCheckConfigSql.VERSION),
              ProjectCheckConfigKeys.PROJECT_ID to rows.getString(SqliteProjectCheckConfigSql.PROJECT_ID),
              ProjectCheckConfigKeys.BUILD_COMMANDS to
                categoryNode(
                  rows.getString(SqliteProjectCheckConfigSql.BUILD),
                  projectId,
                  ProjectCheckConfigKeys.BUILD_COMMANDS,
                ),
              ProjectCheckConfigKeys.LINT_COMMANDS to
                categoryNode(
                  rows.getString(SqliteProjectCheckConfigSql.LINT),
                  projectId,
                  ProjectCheckConfigKeys.LINT_COMMANDS,
                ),
              ProjectCheckConfigKeys.TEST_COMMANDS to
                categoryNode(
                  rows.getString(SqliteProjectCheckConfigSql.TEST),
                  projectId,
                  ProjectCheckConfigKeys.TEST_COMMANDS,
                ),
              ProjectCheckConfigKeys.FORMATTING_COMMANDS to
                categoryNode(
                  rows.getString(SqliteProjectCheckConfigSql.FORMATTING),
                  projectId,
                  ProjectCheckConfigKeys.FORMATTING_COMMANDS,
                ),
              ProjectCheckConfigKeys.VALIDATION_COMMANDS to
                categoryNode(
                  rows.getString(SqliteProjectCheckConfigSql.VALIDATION),
                  projectId,
                  ProjectCheckConfigKeys.VALIDATION_COMMANDS,
                ),
            ),
          )
        validator.validate(raw.toString(), projectId, databasePath.toString())
      }
    }

  private fun categoryNode(
    raw: String?,
    projectId: String,
    category: String,
  ): JsonElement? =
    raw?.let {
      try {
        val categoryValue = JsonCodec.json.parseToJsonElement(it)
        if (JsonCodec.duplicateObjectKeyPath(it) != null) {
          invalidCategory(projectId, category, "ambiguous")
        }
        categoryValue
      } catch (failure: SerializationException) {
        invalidCategory(projectId, category, "malformed", failure)
      }
    }

  private fun invalidCategory(
    projectId: String,
    category: String,
    reason: String,
    cause: Throwable? = null,
  ): Nothing =
    throw SkillBillRuntimeException(
      ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID,
      "Project check configuration for project '$projectId', category '$category' in $databasePath " +
        "contains $reason stored JSON. Stored bytes are unchanged. Preserve a copy and repair the " +
        "offending row with a compatible runtime.",
      cause,
    )

  private fun ProjectCheckConfigDiscovery.asConfig(projectId: String) =
    ProjectCheckConfig(
      projectId,
      buildCommands,
      lintCommands,
      testCommands,
      formattingCommands,
      validationCommands,
    )

  private fun encode(config: ProjectCheckConfig): String =
    JsonCodec.mapToJsonString(
      linkedMapOf(
        ProjectCheckConfigKeys.CONTRACT_VERSION to PROJECT_CHECK_CONFIG_CONTRACT_VERSION,
        ProjectCheckConfigKeys.PROJECT_ID to config.projectId,
        ProjectCheckConfigKeys.BUILD_COMMANDS to categoryMap(config.buildCommands),
        ProjectCheckConfigKeys.LINT_COMMANDS to categoryMap(config.lintCommands),
        ProjectCheckConfigKeys.TEST_COMMANDS to categoryMap(config.testCommands),
        ProjectCheckConfigKeys.FORMATTING_COMMANDS to categoryMap(config.formattingCommands),
        ProjectCheckConfigKeys.VALIDATION_COMMANDS to categoryMap(config.validationCommands),
      ),
    )

  private fun categoryJson(value: ProjectCheckCategoryConfig?): String? =
    categoryMap(value)?.let(JsonCodec::mapToJsonString)

  private fun categoryMap(value: ProjectCheckCategoryConfig?): Map<String, Any?>? =
    value?.let {
      mapOf(
        ProjectCheckConfigKeys.COMMANDS to
          it.commands.map { command ->
            mapOf(
              ProjectCheckConfigKeys.ARGV to command.argv,
              ProjectCheckConfigKeys.WORKING_DIRECTORY to command.workingDirectory,
            )
          },
      )
    }
}

private object SqliteProjectCheckConfigSql {
  const val PROJECT_ID = "project_id"
  const val VERSION = "contract_version"
  const val BUILD = "build_commands"
  const val LINT = "lint_commands"
  const val TEST = "test_commands"
  const val FORMATTING = "formatting_commands"
  const val VALIDATION = "validation_commands"
  const val INSERT_UNKNOWN =
    "INSERT OR IGNORE INTO project_config " +
      "(project_id, contract_version, build_commands, lint_commands, test_commands, " +
      "formatting_commands, validation_commands) " +
      "VALUES (?, ?, NULL, NULL, NULL, NULL, NULL)"
  const val SELECT =
    "SELECT project_id, contract_version, build_commands, lint_commands, test_commands, " +
      "formatting_commands, validation_commands FROM project_config WHERE project_id = ?"
  const val UPDATE =
    "UPDATE project_config SET build_commands = COALESCE(build_commands, ?), " +
      "lint_commands = COALESCE(lint_commands, ?), test_commands = COALESCE(test_commands, ?), " +
      "formatting_commands = COALESCE(formatting_commands, ?), " +
      "validation_commands = COALESCE(validation_commands, ?) WHERE project_id = ?"
}
