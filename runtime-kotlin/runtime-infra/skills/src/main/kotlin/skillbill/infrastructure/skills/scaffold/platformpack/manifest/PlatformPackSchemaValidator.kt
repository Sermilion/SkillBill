package skillbill.infrastructure.skills.scaffold.platformpack.manifest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import java.util.logging.Logger
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION

internal val platformPackSchemaLog: Logger =
  Logger.getLogger("skillbill.scaffold.platformpack.PlatformPackSchemaValidator")

internal class PlatformPackSchemaValidator {
  private val mapper: ObjectMapper
    get() = ClasspathContractSchemaLoader.sharedObjectMapper()

  fun validate(
    parsedYaml: Map<String, Any?>,
    slug: String,
    enforceContractVersion: Boolean = true,
  ) {
    val instance: JsonNode = mapper.valueToTree(parsedYaml)
    val errors: Set<ValidationMessage> = loadSchema().validate(instance)

    val contractVersionConst = errors.firstOrNull { it.isContractVersionConstMismatch() }
    if (contractVersionConst != null && enforceContractVersion) {
      throw SkillBillRuntimeException(InstallFailureCode.CONTRACT_VERSION_MISMATCH,
        buildContractVersionMismatchMessage(slug, instance, contractVersionConst),
      )
    }
    val remainingErrors =
      if (contractVersionConst != null) {
        recordToleratedContractVersion(slug, instance, contractVersionConst)
        errors - contractVersionConst
      } else {
        errors
      }
    if (remainingErrors.isEmpty()) {
      return
    }
    throw invalidManifestSchema(formatValidationMessage(slug, remainingErrors, instance))
  }

  private fun recordToleratedContractVersion(
    slug: String,
    instance: JsonNode,
    error: ValidationMessage,
  ) {
    val actual = extractOffendingValue(instance, error.instanceLocation?.toString().orEmpty())
    platformPackSchemaLog.warning(
      "platform pack contract_version enforcement degraded: " +
        "seam=PlatformPackSchemaValidator.validate pack=$slug " +
        "used=${actual.ifBlank { "<unreadable>" }} expected=$SHELL_CONTRACT_VERSION " +
        "cause=caller enumerated with enforceContractVersion=false, so a preserved local " +
        "manifest pinned to an unsupported contract stays enumerable and is replaced by " +
        "upstream on adopt",
    )
  }

  private fun ValidationMessage.isContractVersionConstMismatch(): Boolean {
    val dotted = dottedFieldPath(instanceLocation?.toString().orEmpty())
    return dotted == "contract_version" && type == "const"
  }

  private fun buildContractVersionMismatchMessage(
    slug: String,
    instance: JsonNode,
    error: ValidationMessage,
  ): String {
    val actual = extractOffendingValue(instance, error.instanceLocation?.toString().orEmpty())
    return buildString {
      append("Platform pack '")
      append(slug)
      append("': declares contract_version")
      if (actual.isNotBlank()) {
        append(" '")
        append(actual)
        append("'")
      }
      append(" but the shell expects '")
      append(SHELL_CONTRACT_VERSION)
      append("'.")
    }
  }

  private fun formatValidationMessage(
    slug: String,
    errors: Set<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val sorted = errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = dottedFieldPath(instanceLocation)
    val detail = firstError.message
    val hint = humanReadableHintFor(firstError, fieldPath)
    val offendingValue = extractOffendingValue(instance, instanceLocation)
    val others = if (errors.size > 1) " (+ ${errors.size - 1} more)" else ""
    return buildString {
      append("Platform pack '")
      append(slug)
      append("': manifest fails schema validation at '")
      append(fieldPath.ifBlank { "<root>" })
      append("': ")
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      if (hint.isNotBlank()) {
        append(" — ")
        append(hint)
      }
      append(others)
    }
  }

  private fun extractOffendingValue(
    instance: JsonNode,
    instanceLocation: String,
  ): String {
    val dotted = dottedFieldPath(instanceLocation)
    if (dotted.isBlank()) return ""
    var node: JsonNode = instance

    dotted.split('.').forEach { rawSegment ->
      if (rawSegment.isBlank()) return@forEach
      val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
      if (arrayMatch != null) {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      } else {
        node = node.path(rawSegment)
      }
    }
    return when {
      node.isMissingNode -> ""
      node.isValueNode -> node.asText()
      else -> ""
    }
  }

  private fun humanReadableHintFor(
    error: ValidationMessage,
    fieldPath: String,
  ): String {
    val keyword = error.type.orEmpty()
    val isPointerName = fieldPath.startsWith("pointers") && fieldPath.endsWith(".name")
    val isPointerTarget = fieldPath.startsWith("pointers") && fieldPath.endsWith(".target")
    return when {
      keyword == "pattern" && fieldPath.startsWith("declared_files") ->
        "schema requires this field to point directly at 'content.md'."
      keyword == "pattern" && fieldPath == "declared_quality_check_file" ->
        "schema requires this field to point directly at 'content.md'."
      keyword == "pattern" && isPointerName ->
        "schema requires pointer 'name' to be a bare '.md' filename (no path separators)."
      keyword == "minLength" && isPointerTarget ->
        "schema requires a non-empty 'target' on every pointer entry."
      keyword == "not" && isPointerName ->
        "schema forbids '..' in pointer 'name'."
      else -> ""
    }
  }

  private fun dottedFieldPath(instanceLocation: String): String =
    when {
      instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
      instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
      instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')

      else -> instanceLocation.trimStart('/').replace('/', '.')
    }
}

object PlatformPackSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/platform-pack-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/infrastructure/contracts/platform-pack-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/platform-pack-schema.yaml"
}

internal const val PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE: String =
  PlatformPackSchemaPaths.CLASSPATH_RESOURCE

internal const val PLATFORM_PACK_SCHEMA_REPO_RELATIVE_PATH: String =
  PlatformPackSchemaPaths.REPO_RELATIVE_PATH

private fun loadSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = PlatformPackSchemaValidator::class.java.classLoader,
      classpathResource = PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidManifestSchema(
          "Canonical platform-pack schema is missing. Expected to find it on the JVM classpath at " +
            "'$PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidManifestSchema(cause.message ?: cause::class.simpleName.orEmpty())
      },
      loadFailureLogger = {},
      expectedSchemaId = PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = SHELL_CONTRACT_VERSION,
      identityFailure = { reason -> invalidManifestSchema(reason) },
    ),
  )

internal fun anchoredTopLevelFieldNames(): Set<String> = ANCHORED_TOP_LEVEL_FIELD_NAMES

private val ANCHORED_TOP_LEVEL_FIELD_NAMES: Set<String>
  get() {
    val yamlText =
      ClasspathContractSchemaLoader.readClasspathYamlText(
        classLoader = PlatformPackSchemaValidator::class.java.classLoader,
        resource = PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE,
        missingError = {
          invalidManifestSchema(
            "Canonical platform-pack schema is missing. Expected to find it on the JVM classpath at " +
              "'$PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE'.",
          )
        },
      )
    val yamlNode: JsonNode = ClasspathContractSchemaLoader.sharedYamlMapper().readTree(yamlText)
    val properties = yamlNode.path("properties")
    if (properties.isMissingNode || !properties.isObject) {
      throw invalidManifestSchema(
        "Canonical platform-pack schema is missing a top-level 'properties' object; cannot derive " +
          "the anchored top-level field set.",
      )
    }
    val anchored = linkedSetOf<String>()
    properties.fields().forEach { (name, definition) ->
      if (definition.path("x-runtime-anchored").asBoolean(false)) {
        anchored += name
      }
    }
    return anchored
  }
