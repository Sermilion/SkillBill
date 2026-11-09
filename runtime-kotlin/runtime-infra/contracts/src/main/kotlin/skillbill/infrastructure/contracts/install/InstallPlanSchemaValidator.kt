package skillbill.infrastructure.contracts.install

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.contracts.install.INSTALL_PLAN_PREVIOUS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidInstallPlanSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.InstallPlanSchemaPaths
import skillbill.infrastructure.contracts.locator.PreviousInstallPlanSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.install.model.InstallPlanPayloadKeys
import skillbill.install.model.InstallPlanWireMap
import skillbill.install.model.InstallationProfile
import skillbill.ports.install.InstallPlanWireValidator
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.contracts.install.InstallPlanSchemaValidator")

@Inject
class InstallPlanSchemaValidator : InstallPlanWireValidator {
  override fun validate(plan: InstallPlanWireMap) {
    validate(plan as Map<String, Any?>)
  }

  fun validate(plan: Map<String, Any?>) {
    val version = plan[SharedPayloadKeys.CONTRACT_VERSION]
    val candidate =
      when (version) {
        INSTALL_PLAN_PREVIOUS_CONTRACT_VERSION -> {
          validatePayload(previousInstallPlanSchema(), plan)
          plan.toMutableMap().apply {
            put(SharedPayloadKeys.CONTRACT_VERSION, INSTALL_PLAN_CONTRACT_VERSION)
            put(InstallPlanPayloadKeys.INSTALLATION_PROFILE, InstallationProfile.ORDINARY.wireValue)
          }
        }
        INSTALL_PLAN_CONTRACT_VERSION -> plan
        else -> throw invalidInstallPlanSchemaError(
          fieldPath = SharedPayloadKeys.CONTRACT_VERSION,
          reason = "Unsupported install-plan contract version: ${version ?: "<missing>"}.",
        )
      }
    validatePayload(installPlanSchema(), candidate)
  }

  private fun validatePayload(
    schema: JsonSchema,
    payload: Map<String, Any?>,
  ) {
    val instance = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema, instance)
    if (errors.isEmpty()) {
      return
    }

    log.log(Level.WARNING, buildSchemaDriftLog(errors, instance))
    val sorted = errors.sortedWith(violationOrdering)
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = installPlanSchemaDottedFieldPath(instanceLocation)
    val reason = formatValidationReason(sorted, instance)
    throw invalidInstallPlanSchemaError(fieldPath = fieldPath, reason = reason)
  }

  private fun buildSchemaDriftLog(
    errors: Set<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val sorted = errors.sortedWith(violationOrdering)
    val topTwo = sorted.take(2)
    val parts =
      topTwo.map { error ->
        val location = error.instanceLocation?.toString().orEmpty()
        val fieldPath = installPlanSchemaDottedFieldPath(location).ifBlank { "<root>" }
        val offendingValue = extractOffendingValueFromInstance(instance, location)
        if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
      }
    return "Install plan failed schema validation: violations=${parts.joinToString(", ")} " +
      "totalViolations=${errors.size}"
  }

  private fun formatValidationReason(
    sorted: List<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val detail = firstError.message
    val offendingValue = extractOffendingValueFromInstance(instance, instanceLocation)
    return buildString {
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = installPlanSchemaDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractOffendingValueFromInstance(instance, otherLocation)
        append(" | ")
        append(otherPath)
        append(": ")
        append(other.message)
        if (otherValue.isNotBlank()) {
          append(" — offending value: ")
          append(otherValue)
        }
      }
    }
  }

  private val violationOrdering: Comparator<ValidationMessage> =
    compareBy(
      { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
      { it.instanceLocation?.toString().orEmpty() },
      { it.message.orEmpty() },
    )

  companion object {
    private val canonical: InstallPlanSchemaValidator by lazy(::InstallPlanSchemaValidator)

    fun validate(plan: Map<String, Any?>) = canonical.validate(plan)
  }
}

internal const val INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE: String =
  InstallPlanSchemaPaths.CLASSPATH_RESOURCE

internal const val INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH: String =
  InstallPlanSchemaPaths.REPO_RELATIVE_PATH

private fun installPlanSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = InstallPlanSchemaValidator::class.java.classLoader,
      classpathResource = INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidInstallPlanSchemaError(
          fieldPath = "",
          reason =
            "Canonical install-plan schema is missing. Expected to find it on the JVM classpath at " +
              "'$INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidInstallPlanSchemaError(
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          log,
          "install-plan",
          INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE,
          INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = INSTALL_PLAN_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidInstallPlanSchemaError(
          fieldPath = "<schema>",
          reason = reason,
        )
      },
    ),
  )

private fun previousInstallPlanSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = PreviousInstallPlanSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = InstallPlanSchemaValidator::class.java.classLoader,
      classpathResource = PreviousInstallPlanSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidInstallPlanSchemaError(
          fieldPath = "",
          reason = "Previous install-plan schema is missing at '${PreviousInstallPlanSchemaPaths.CLASSPATH_RESOURCE}'.",
        )
      },
      processingFailure = { cause ->
        invalidInstallPlanSchemaError(
          fieldPath = "<schema>",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          log,
          "install-plan",
          PreviousInstallPlanSchemaPaths.CLASSPATH_RESOURCE,
          PreviousInstallPlanSchemaPaths.REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = PreviousInstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = INSTALL_PLAN_PREVIOUS_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidInstallPlanSchemaError(fieldPath = "<schema>", reason = reason)
      },
    ),
  )

internal fun extractOffendingValueFromInstance(
  instance: JsonNode,
  instanceLocation: String,
): String {
  val dotted = installPlanSchemaDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

internal fun installPlanSchemaDottedFieldPath(instanceLocation: String): String =
  when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }
