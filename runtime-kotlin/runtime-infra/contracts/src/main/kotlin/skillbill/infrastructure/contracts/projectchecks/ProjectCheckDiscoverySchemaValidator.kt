package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryKeys
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryResponse
import skillbill.contracts.config.projectchecks.wireValue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.ProjectCheckDiscoverySchemaPaths
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryValidator
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class ProjectCheckDiscoverySchemaValidator : ProjectCheckDiscoveryValidator {
  override fun validate(
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ): ProjectCheckDiscoveryResponse {
    val root = parsedRoot(document, projectId, requestedCategories, responseLabel)
    requireSupportedVersion(root, document, projectId, requestedCategories, responseLabel)
    requireCanonicalSchema(root, document, projectId, requestedCategories, responseLabel)
    if (root.get(ProjectCheckDiscoveryKeys.PROJECT_ID).asText() != projectId) {
      invalid(projectId, requestedCategories, responseLabel, document, "project identity is mismatched")
    }
    return ProjectCheckDiscoveryResponse(
      projectId,
      parsedCategories(root, document, projectId, requestedCategories, responseLabel),
    )
  }

  private fun parsedRoot(
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ): JsonNode {
    val root =
      try {
        STRICT_JSON.readTree(document)
      } catch (_: JsonProcessingException) {
        invalid(projectId, requestedCategories, responseLabel, document, "discovery output is malformed or ambiguous")
      }
    if (root == null || !root.isObject) {
      invalid(projectId, requestedCategories, responseLabel, document, "discovery output is not an object")
    }
    return root
  }

  private fun requireSupportedVersion(
    root: JsonNode,
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ) {
    val version = root.get(ProjectCheckDiscoveryKeys.CONTRACT_VERSION)?.takeIf(JsonNode::isTextual)?.asText()
    if (version == PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION) {
      return
    }
    val code =
      if (version == null) {
        ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID
      } else {
        ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_UNSUPPORTED_VERSION
      }
    throw SkillBillRuntimeException(
      code,
      discoveryMessage(
        projectId,
        requestedCategories,
        responseLabel,
        document,
        "contract version ${version ?: "<missing>"} is unsupported",
      ),
    )
  }

  private fun requireCanonicalSchema(
    root: JsonNode,
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ) {
    val schema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = ProjectCheckDiscoverySchemaPaths.CLASSPATH_RESOURCE,
          classLoader = ProjectCheckDiscoverySchemaValidator::class.java.classLoader,
          classpathResource = ProjectCheckDiscoverySchemaPaths.CLASSPATH_RESOURCE,
          missingResource = {
            invalid(projectId, requestedCategories, responseLabel, document, "canonical schema resource is missing")
          },
          processingFailure = {
            invalid(projectId, requestedCategories, responseLabel, document, "canonical schema cannot be loaded")
          },
          loadFailureLogger = {},
          expectedSchemaId = ProjectCheckDiscoverySchemaPaths.EXPECTED_SCHEMA_ID,
          expectedContractVersion = PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION,
          identityFailure = { reason ->
            invalid(projectId, requestedCategories, responseLabel, document, reason)
          },
        ),
      )
    val violations = ClasspathContractSchemaLoader.validate(schema, root)
    if (violations.isNotEmpty()) {
      invalid(projectId, requestedCategories, responseLabel, document, violations.joinToString("; "))
    }
  }

  private fun parsedCategories(
    root: JsonNode,
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ): Map<ProjectCheckCategory, ProjectCheckCategoryConfig> {
    val categoriesNode = root.get(ProjectCheckDiscoveryKeys.CATEGORIES)
    if (categoriesNode == null || !categoriesNode.isObject) {
      invalid(projectId, requestedCategories, responseLabel, document, "categories are missing or not an object")
    }
    val actualKeys = categoriesNode.fieldNames().asSequence().toSet()
    val expectedKeys = requestedCategories.map { it.wireValue }.toSet()
    if (actualKeys != expectedKeys) {
      invalid(
        projectId,
        requestedCategories,
        responseLabel,
        document,
        "category set must match the requested categories exactly",
      )
    }
    return buildMap {
      requestedCategories.forEach { category ->
        val value = categoriesNode.get(category.wireValue)
        if (value == null || value.isNull) {
          invalid(
            projectId,
            setOf(category),
            responseLabel,
            document,
            "category result must not be null",
          )
        }
        put(category, categoryValue(value, projectId, category, responseLabel, document))
      }
    }
  }

  private fun categoryValue(
    value: JsonNode,
    projectId: String,
    category: ProjectCheckCategory,
    responseLabel: String,
    raw: String,
  ): ProjectCheckCategoryConfig {
    val commands = value.get(ProjectCheckConfigKeys.COMMANDS)
    return ProjectCheckCategoryConfig(
      commands.map { command ->
        val argv = command.get(ProjectCheckConfigKeys.ARGV)
        val directory = command.get(ProjectCheckConfigKeys.WORKING_DIRECTORY)
        if (argv[0].asText().isBlank() || !validDirectory(directory.asText())) {
          invalid(
            projectId,
            setOf(category),
            responseLabel,
            raw,
            "command has invalid argv or working_directory",
          )
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
    categories: Set<ProjectCheckCategory>,
    responseLabel: String,
    raw: String,
    reason: String,
  ): Nothing =
    throw SkillBillRuntimeException(
      ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID,
      discoveryMessage(projectId, categories, responseLabel, raw, reason),
    )

  private fun discoveryMessage(
    projectId: String,
    categories: Set<ProjectCheckCategory>,
    responseLabel: String,
    raw: String,
    reason: String,
  ): String {
    val categoryList = categories.joinToString(", ") { it.wireValue }.ifBlank { "row" }
    val preview = raw.take(DISCOVERY_RAW_PREVIEW)
    return "Project check discovery for project '$projectId', categories [$categoryList] in " +
      "$responseLabel is invalid: $reason. Preserved output preview: $preview. " +
      "Retry discovery after repairing inputs or host tooling."
  }
}

private const val DISCOVERY_RAW_PREVIEW = 240

private val STRICT_JSON =
  ClasspathContractSchemaLoader.sharedObjectMapper().copy()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
