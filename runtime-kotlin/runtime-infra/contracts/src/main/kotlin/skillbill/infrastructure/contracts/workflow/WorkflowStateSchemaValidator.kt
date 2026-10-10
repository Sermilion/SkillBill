package skillbill.infrastructure.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.WorkflowStateSnapshotWireMapper
import skillbill.infrastructure.contracts.locator.WorkflowStateSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.contracts.workflow.WorkflowStateSchemaValidator")

@Inject
class WorkflowStateSchemaValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) {
    validate(WorkflowStateSnapshotWireMapper.wireMap(snapshot), slug)
  }

  fun validate(
    parsedYaml: Map<String, Any?>,
    slug: String,
  ) {
    val normalized = parsedYaml.toMutableMap()
    val storedVersion = normalized[SharedPayloadKeys.CONTRACT_VERSION] as? String
    if (storedVersion != null && storedVersion in WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS) {
      normalized[SharedPayloadKeys.CONTRACT_VERSION] = WORKFLOW_STATE_CONTRACT_VERSION
    }
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(normalized)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(workflowStateSchema(), instance)
    if (errors.isEmpty()) {
      return
    }

    log.log(Level.FINE, buildWorkflowStateSchemaDriftLog(slug, errors, instance))
    throw invalidWorkflowStateSchemaError(formatWorkflowStateValidationMessage(slug, errors, instance))
  }
}

internal const val WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE: String =
  WorkflowStateSchemaPaths.CLASSPATH_RESOURCE

internal const val WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH: String =
  WorkflowStateSchemaPaths.REPO_RELATIVE_PATH

fun extractOffendingValueFromInstance(
  instance: JsonNode,
  instanceLocation: String,
): String {
  val dotted = workflowStateSchemaDottedFieldPath(instanceLocation)
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

internal fun workflowStateSchemaDottedFieldPath(instanceLocation: String): String =
  when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }

private fun workflowStateSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = WorkflowStateSchemaValidator::class.java.classLoader,
      classpathResource = WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidWorkflowStateSchemaError(
          "Canonical workflow-state schema is missing. Expected to find it on the JVM classpath at " +
            "'$WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidWorkflowStateSchemaError(cause.message ?: cause::class.simpleName.orEmpty(), cause)
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          log,
          "workflow-state",
          WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE,
          WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = WORKFLOW_STATE_CONTRACT_VERSION,
      identityFailure = { reason -> invalidWorkflowStateSchemaError(reason) },
    ),
  )
