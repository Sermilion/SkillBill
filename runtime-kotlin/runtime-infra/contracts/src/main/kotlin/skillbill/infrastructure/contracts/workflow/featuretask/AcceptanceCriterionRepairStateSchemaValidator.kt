package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairStateSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.workflow.taskruntime.artifact.historical.toHistoricalRepairState
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

object AcceptanceCriterionRepairStateSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    if (JsonCodec.valueToJsonString(
        payload,
      ).toByteArray(Charsets.UTF_8).size > AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES
    ) {
      throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Durable serial state exceeds its UTF-8 capacity.")
    }
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, formatReason(errors))
    }
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload).toAcceptanceCriterionRepairState(sourceLabel)
  }

  fun validatePrevious(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ): AcceptanceCriterionHistoricalRepairState {
    if (JsonCodec.valueToJsonString(
        payload,
      ).toByteArray(Charsets.UTF_8).size > AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES
    ) {
      throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Durable serial state exceeds its UTF-8 capacity.")
    }
    val original =
      payload[AcceptanceCriterionRepairPayloadKeys.CONTRACT_VERSION] ==
        ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
    val errors =
      ClasspathContractSchemaLoader.validate(
        schema(
          if (original) {
            AcceptanceCriterionRepairStateSchemaPaths.PREVIOUS_CLASSPATH_RESOURCE
          } else {
            AcceptanceCriterionRepairStateSchemaPaths.INTERACTION_CLASSPATH_RESOURCE
          },
          if (original) {
            ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
          } else {
            ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
          },
        ),
        ClasspathContractSchemaLoader.valueToTree(payload),
      )
    if (errors.isNotEmpty()) throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, formatReason(errors))
    return FeatureTaskRuntimeWorkflowArtifactMap.from(payload).toHistoricalRepairState(sourceLabel)
  }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  private fun schema(
    resource: String = AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
    version: String = ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
  ): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = resource,
        classLoader = AcceptanceCriterionRepairStateSchemaValidator::class.java.classLoader,
        classpathResource = resource,
        missingResource = {
          invalidAcceptanceCriterionRepairStateSchema(
            AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
            "Canonical repair-state schema resource is missing.",
          )
        },
        processingFailure = { cause ->
          invalidAcceptanceCriterionRepairStateSchema(
            AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
            cause.message ?: cause::class.simpleName.orEmpty(),
            cause,
          )
        },
        loadFailureLogger = {},
        expectedSchemaId = AcceptanceCriterionRepairStateSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = version,
        identityFailure = { reason ->
          invalidAcceptanceCriterionRepairStateSchema(
            AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
            reason,
          )
        },
      ),
    )
}
