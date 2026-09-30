package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.error.core.ShellContentContractException
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeBuildReceiptSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePersistenceSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseHandoffSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeProjectionMeasurementSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeReadinessEvidenceSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeValidationEvidenceSchemaPaths
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact

private const val MAX_REPORTED_SCHEMA_FAILURES = 3

private data class FeatureTaskRuntimeSchemaValidationRequest(
  val payload: Map<String, Any?>,
  val classpathResource: String,
  val expectedId: String,
  val expectedContractVersion: String,
  val contractVersionMatches: ((JsonNode, String) -> Boolean)? = null,
  val error: (String) -> ShellContentContractException,
)

object FeatureTaskRuntimePhaseHandoffSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimePhaseHandoffSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimePhaseHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
      error = { reason -> InvalidFeatureTaskRuntimePhaseHandoffSchemaError(sourceLabel, reason) },
    ),
  )
}

object FeatureTaskRuntimePersistenceSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimePersistenceSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimePersistenceSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      contractVersionMatches = ::persistenceContractVersionMatches,
      error = { reason -> InvalidFeatureTaskRuntimePersistenceSchemaError(sourceLabel, reason) },
    ),
  )
}

internal object FeatureTaskRuntimeProjectionMeasurementSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimeProjectionMeasurementSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimeProjectionMeasurementSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION,
      error = { reason -> InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError(sourceLabel, reason) },
    ),
  )
}

object FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
      error = { reason -> InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError(sourceLabel, reason) },
    ),
  )
}

object FeatureTaskRuntimeValidationEvidenceSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimeValidationEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimeValidationEvidenceSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
      error = { reason -> InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, reason) },
    ),
  )
}

object FeatureTaskRuntimeReadinessEvidenceSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validateAgainst(
    FeatureTaskRuntimeSchemaValidationRequest(
      payload = payload,
      classpathResource = FeatureTaskRuntimeReadinessEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      expectedId = FeatureTaskRuntimeReadinessEvidenceSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION,
      error = { reason -> InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError(sourceLabel, reason) },
    ),
  )
}

object FeatureTaskRuntimeBuildReceiptSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance = ClasspathContractSchemaLoader.valueToTree(payload)
    val failures = ClasspathContractSchemaLoader.validate(buildReceiptSchema(), instance)
    if (failures.isNotEmpty()) {
      val sorted = failures.sortedBy { it.instanceLocation.toString() }
      val reasons = formatBuildReceiptViolationReasons(sorted.take(MAX_REPORTED_SCHEMA_FAILURES), instance)
      throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = reasons.valueBearing,
        payloadFreeReason = reasons.payloadFree,
      )
    }
    try {
      decodeValidationGateExecutionEvidenceFromArtifact(
        payload.filterKeys { it != SharedPayloadKeys.CONTRACT_VERSION },
        sourceLabel,
      )
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = error.message.orEmpty(),
        payloadFreeReason = error.message.orEmpty(),
        cause = error,
      )
    }
  }
}

private fun buildReceiptSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = FeatureTaskRuntimeBuildReceiptSchemaValidator::class.java.classLoader,
      classpathResource = FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
          sourceLabel = FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE,
          reason =
            "Canonical feature-task-runtime build receipt schema is missing. Expected it on the JVM " +
              "classpath at '${FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE}'.",
        )
      },
      processingFailure = { cause ->
        InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
          sourceLabel = FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeBuildReceiptSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
      identityFailure = { reason ->
        InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
          sourceLabel = FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
    ),
  )

private data class BuildReceiptViolationReasons(val valueBearing: String, val payloadFree: String)

private fun formatBuildReceiptViolationReasons(
  sorted: List<ValidationMessage>,
  instance: JsonNode,
): BuildReceiptViolationReasons {
  val violations =
    sorted.map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
      val head = "$fieldPath: ${error.message}"
      head to extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, location)
    }

  fun render(includeOffendingValues: Boolean): String =
    violations.joinToString(separator = " | ") { (head, offendingValue) ->
      if (includeOffendingValues && offendingValue.isNotBlank()) {
        "$head — offending value: $offendingValue"
      } else {
        head
      }
    }
  return BuildReceiptViolationReasons(valueBearing = render(true), payloadFree = render(false))
}

private fun validateAgainst(request: FeatureTaskRuntimeSchemaValidationRequest) {
  val schema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = request.classpathResource,
        classLoader = FeatureTaskRuntimePhaseHandoffSchemaValidator::class.java.classLoader,
        classpathResource = request.classpathResource,
        missingResource = { request.error("Canonical runtime contract is missing at '${request.classpathResource}'.") },
        processingFailure = { cause -> request.error(cause.message ?: cause::class.simpleName.orEmpty()) },
        loadFailureLogger = {},
        expectedSchemaId = request.expectedId,
        expectedContractVersion = request.expectedContractVersion,
        contractVersionMatches = request.contractVersionMatches,
        identityFailure = request.error,
      ),
    )
  val failures =
    ClasspathContractSchemaLoader.validate(
      schema,
      ClasspathContractSchemaLoader.valueToTree(request.payload),
    )
  if (failures.isNotEmpty()) {
    val reason =
      failures
        .sortedBy { it.instanceLocation.toString() }
        .take(MAX_REPORTED_SCHEMA_FAILURES)
        .joinToString(" | ") { it.message }
    throw request.error(reason)
  }
}

private fun persistenceContractVersionMatches(
  node: JsonNode,
  expected: String,
): Boolean =
  listOf("private_phase_record", "delivered_projection").all { definitionName ->
    node.path("\$defs")
      .path(definitionName)
      .path("properties")
      .path("contract_version")
      .path("const")
      .asText("") == expected
  }
