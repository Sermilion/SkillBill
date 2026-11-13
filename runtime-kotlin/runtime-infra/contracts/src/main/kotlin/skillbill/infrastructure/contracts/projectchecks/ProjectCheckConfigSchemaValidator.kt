package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_CONFIG_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.ProjectCheckConfigSchemaPaths
import skillbill.ports.config.projectchecks.ProjectCheckConfigValidator
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class ProjectCheckConfigSchemaValidator : ProjectCheckConfigValidator {
  override fun validate(
    document: String,
    projectId: String,
    databaseLocation: String,
  ): ProjectCheckConfig {
    val root =
      try {
        STRICT_JSON.readTree(document)
      } catch (_: JsonProcessingException) {
        invalid(projectId, "row", databaseLocation, "stored JSON is malformed or ambiguous")
      }
    if (root == null || !root.isObject) {
      invalid(projectId, "row", databaseLocation, "stored JSON is not an object")
    }
    val version = root.get(ProjectCheckConfigKeys.CONTRACT_VERSION)?.takeIf(JsonNode::isTextual)?.asText()
    if (version != PROJECT_CHECK_CONFIG_CONTRACT_VERSION) {
      val code =
        if (version == null) {
          ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID
        } else {
          ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_UNSUPPORTED_VERSION
        }
      throw SkillBillRuntimeException(
        code,
        "Project check configuration for project '$projectId', category 'row' in $databaseLocation has contract " +
          "version ${version ?: "<missing>"}. Preserve a copy and repair the offending row with a compatible runtime.",
      )
    }
    val schema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = ProjectCheckConfigSchemaPaths.CLASSPATH_RESOURCE,
          classLoader = ProjectCheckConfigSchemaValidator::class.java.classLoader,
          classpathResource = ProjectCheckConfigSchemaPaths.CLASSPATH_RESOURCE,
          missingResource = { invalid(projectId, "row", databaseLocation, "canonical schema resource is missing") },
          processingFailure = { invalid(projectId, "row", databaseLocation, "canonical schema cannot be loaded") },
          loadFailureLogger = {},
          expectedSchemaId = ProjectCheckConfigSchemaPaths.EXPECTED_SCHEMA_ID,
          expectedContractVersion = PROJECT_CHECK_CONFIG_CONTRACT_VERSION,
          identityFailure = { reason -> invalid(projectId, "row", databaseLocation, reason) },
        ),
      )
    val violations = ClasspathContractSchemaLoader.validate(schema, root)
    if (violations.isNotEmpty()) {
      invalid(projectId, "row", databaseLocation, violations.joinToString("; "))
    }
    if (root.get(ProjectCheckConfigKeys.PROJECT_ID).asText() != projectId) {
      invalid(projectId, "row", databaseLocation, "project identity is mismatched")
    }
    return ProjectCheckConfig(
      projectId,
      category(root, ProjectCheckConfigKeys.BUILD_COMMANDS, projectId, databaseLocation),
      category(root, ProjectCheckConfigKeys.LINT_COMMANDS, projectId, databaseLocation),
      category(root, ProjectCheckConfigKeys.TEST_COMMANDS, projectId, databaseLocation),
      category(root, ProjectCheckConfigKeys.FORMATTING_COMMANDS, projectId, databaseLocation),
      category(root, ProjectCheckConfigKeys.VALIDATION_COMMANDS, projectId, databaseLocation),
    )
  }

  private fun category(
    root: JsonNode,
    key: String,
    projectId: String,
    location: String,
  ): ProjectCheckCategoryConfig? {
    val value = root.get(key)
    if (value.isNull) return null
    val commands = value.get(ProjectCheckConfigKeys.COMMANDS)
    return ProjectCheckCategoryConfig(
      commands.map { command ->
        val argv = command.get(ProjectCheckConfigKeys.ARGV)
        val directory = command.get(ProjectCheckConfigKeys.WORKING_DIRECTORY)
        if (argv[0].asText().isBlank() || !validDirectory(directory.asText())) {
          invalid(projectId, key, location, "command has invalid argv or working_directory")
        }
        ProjectCheckCommand(argv.map(JsonNode::asText), directory.asText())
      },
    )
  }

  private fun validDirectory(value: String): Boolean =
    if (value == ".") {
      true
    } else {
      try {
        val path = Path.of(value)
        !path.isAbsolute && path.normalize().toString() == value
      } catch (_: InvalidPathException) {
        false
      }
    }

  private fun invalid(
    projectId: String,
    category: String,
    location: String,
    reason: String,
  ): Nothing =
    throw SkillBillRuntimeException(
      ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID,
      "Project check configuration for project '$projectId', category '$category' in $location is invalid: " +
        "$reason. Preserve a copy and repair the offending row with a compatible runtime.",
    )
}

private val STRICT_JSON =
  ClasspathContractSchemaLoader.sharedObjectMapper().copy()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
