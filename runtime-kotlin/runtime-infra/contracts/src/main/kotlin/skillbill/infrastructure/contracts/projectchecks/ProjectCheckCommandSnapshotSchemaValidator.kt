package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserFindings
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserMetadata
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshotKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.ProjectCheckCommandSnapshotSchemaPaths
import skillbill.ports.config.projectchecks.ProjectCheckCommandSnapshotValidator
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateFindingsFormat

@Inject
class ProjectCheckCommandSnapshotSchemaValidator : ProjectCheckCommandSnapshotValidator {
  override fun validate(
    document: String,
    sourceLabel: String,
  ): ProjectCheckCommandSnapshot {
    val root =
      try {
        STRICT_JSON.readTree(document)
      } catch (_: JsonProcessingException) {
        invalid(sourceLabel, "stored JSON is malformed or ambiguous")
      }
    if (root == null || !root.isObject) {
      invalid(sourceLabel, "stored JSON is not an object")
    }
    val version = root.get(ProjectCheckCommandSnapshotKeys.CONTRACT_VERSION)?.takeIf(JsonNode::isTextual)?.asText()
    if (version != PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION) {
      val code =
        if (version == null) {
          ProjectCheckConfigFailureCode.PROJECT_CHECK_SNAPSHOT_INVALID
        } else {
          ProjectCheckConfigFailureCode.PROJECT_CHECK_SNAPSHOT_UNSUPPORTED_VERSION
        }
      throw SkillBillRuntimeException(
        code,
        "Project check command snapshot from '$sourceLabel' has contract version ${version ?: "<missing>"}. " +
          "Preserve the original bytes and repair with a compatible runtime.",
      )
    }
    val schema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE,
          classLoader = ProjectCheckCommandSnapshotSchemaValidator::class.java.classLoader,
          classpathResource = ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE,
          missingResource = { invalid(sourceLabel, "canonical schema resource is missing") },
          processingFailure = { invalid(sourceLabel, "canonical schema cannot be loaded") },
          loadFailureLogger = {},
          expectedSchemaId = ProjectCheckCommandSnapshotSchemaPaths.EXPECTED_SCHEMA_ID,
          expectedContractVersion = PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION,
          identityFailure = { reason -> invalid(sourceLabel, reason) },
        ),
      )
    val violations = ClasspathContractSchemaLoader.validate(schema, root)
    if (violations.isNotEmpty()) {
      invalid(sourceLabel, violations.joinToString("; "))
    }
    return ProjectCheckCommandSnapshot(
      projectId = root.get(ProjectCheckCommandSnapshotKeys.PROJECT_ID).asText(),
      buildCommands = category(root, ProjectCheckCommandSnapshotKeys.BUILD_COMMANDS, sourceLabel),
      validationCommands = category(root, ProjectCheckCommandSnapshotKeys.VALIDATION_COMMANDS, sourceLabel),
      parserMetadata = parserMetadata(root, sourceLabel),
      gradleWrapper = root.get(ProjectCheckCommandSnapshotKeys.GRADLE_WRAPPER)?.takeUnless(JsonNode::isNull)?.asText(),
    ).frozen()
  }

  private fun parserMetadata(
    root: JsonNode,
    sourceLabel: String,
  ): ProjectCheckCommandParserMetadata? {
    val node = root.get(ProjectCheckCommandSnapshotKeys.PARSER_METADATA)
    if (node == null || node.isNull) return null
    val findings = node.get(ProjectCheckCommandSnapshotKeys.FINDINGS)
    val format =
      ValidationGateFindingsFormat.fromWire(findings.get(ProjectCheckCommandSnapshotKeys.FORMAT).asText())
        ?: invalid(sourceLabel, "parser findings format is unsupported")
    val artifactGlobs = findings.get(ProjectCheckCommandSnapshotKeys.ARTIFACT_GLOBS).map(JsonNode::asText)
    val compilerNode = findings.get(ProjectCheckCommandSnapshotKeys.COMPILER_DIAGNOSTICS)
    val compilerFormat =
      compilerNode?.takeUnless(JsonNode::isMissingNode)?.let {
        ValidationGateCompilerDiagnosticsFormat.fromWire(it.get(ProjectCheckCommandSnapshotKeys.FORMAT).asText())
          ?: invalid(sourceLabel, "compiler format")
      } ?: invalid(sourceLabel, "parser findings.compiler_diagnostics is required")
    val executedNode = findings.get(ProjectCheckCommandSnapshotKeys.EXECUTED_WORK)
    val executedWorkFormat =
      executedNode?.takeUnless { node -> node.isMissingNode || node.isNull }?.let { node ->
        ValidationGateExecutedWorkFormat.fromWire(node.get(ProjectCheckCommandSnapshotKeys.FORMAT).asText())
          ?: invalid(sourceLabel, "executed work format")
      }
    val suppression = node.get(ProjectCheckCommandSnapshotKeys.SUPPRESSION_MARKERS).map(JsonNode::asText)
    return ProjectCheckCommandParserMetadata(
      node.get(ProjectCheckCommandSnapshotKeys.PACK_SLUG).asText(),
      ProjectCheckCommandParserFindings(
        format.wireValue,
        artifactGlobs,
        compilerFormat.wireValue,
        executedWorkFormat?.wireValue,
      ),
      suppression,
    )
  }

  private fun category(
    node: JsonNode,
    key: String,
    sourceLabel: String,
  ): ProjectCheckCategoryConfig? {
    val category = node.get(key)
    if (category == null || category.isNull) return null
    val commands =
      category.get(ProjectCheckConfigKeys.COMMANDS)?.map { commandNode ->
        ProjectCheckCommand(
          commandNode.get(ProjectCheckConfigKeys.ARGV).map(JsonNode::asText),
          commandNode.get(ProjectCheckConfigKeys.WORKING_DIRECTORY).asText(),
        )
      } ?: invalid(sourceLabel, "$key.commands is missing")
    return ProjectCheckCategoryConfig(commands)
  }

  private fun invalid(
    sourceLabel: String,
    detail: String,
  ): Nothing {
    throw SkillBillRuntimeException(
      ProjectCheckConfigFailureCode.PROJECT_CHECK_SNAPSHOT_INVALID,
      "Project check command snapshot from '$sourceLabel' is invalid: $detail",
    )
  }

  private companion object {
    private val STRICT_JSON =
      ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
  }
}
