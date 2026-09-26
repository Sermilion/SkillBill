package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.util.logging.Level

object FeatureTaskRuntimePhaseOutputWireSchema {
  internal val schema: JsonSchema
    get() = loadFeatureTaskRuntimePhaseOutputSchema()
  internal val legacyReadSchema: JsonSchema
    get() = loadFeatureTaskRuntimePhaseOutputLegacyReadSchema()
  internal val mapper: ObjectMapper
    get() = ClasspathContractSchemaLoader.sharedObjectMapper()
  internal val yamlMapper: YAMLMapper =
    YAMLMapper(YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
  internal val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(
    phaseOutput: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = mapper.valueToTree(phaseOutput)
    val errors: Set<ValidationMessage> = schemaFor(instance).validate(instance)
    if (errors.isNotEmpty()) {
      featureTaskRuntimePhaseOutputLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors))
      val reasons = formatViolationReasons(errors.sortedWith(featureTaskRuntimePhaseOutputViolationOrdering), instance)
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = reasons.valueBearing,
        payloadFreeReason = reasons.payloadFree,
      )
    }
    val phaseId = phaseOutput[SharedPayloadKeys.PHASE_ID] as? String
    if (phaseId != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "phase_id must match the executing phase '$sourceLabel' but was '${phaseId.orEmpty()}'.",
        payloadFreeReason = "phase_id must match the executing phase '$sourceLabel'.",
        failureCode = "phase_id_mismatch",
      )
    }
  }

  fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
  }

  fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel).toMutableMap()
    dropNullProducedOutputsPrompt(parsed, sourceLabel)
    validate(parsed, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = mapper.writeValueAsString(parsed),
      envelope = parsed,
    )
  }

  fun normalizeVerifyingPhaseOutputLenient(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val node = readPhaseOutputObjectNodeLenient(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validateVerifyingEnvelopeShell(parsed, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = mapper.writeValueAsString(parsed),
      envelope = parsed,
    )
  }

  internal fun schemaFor(instance: JsonNode): JsonSchema {
    val contractVersion = instance.path(SharedPayloadKeys.CONTRACT_VERSION).takeIf(JsonNode::isTextual)?.asText()
    return if (isLegacyReadableContractVersion(contractVersion)) legacyReadSchema else schema
  }
}
