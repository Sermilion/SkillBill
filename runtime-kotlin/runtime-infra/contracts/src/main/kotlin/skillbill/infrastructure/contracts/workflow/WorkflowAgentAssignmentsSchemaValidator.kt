package skillbill.infrastructure.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import me.tatarka.inject.annotations.Inject
import skillbill.config.model.WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.config.model.WorkflowAgentAssignmentSource
import skillbill.contracts.workflow.identity.task.WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.workflow.WorkflowAgentAssignmentsFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.taskruntime.assignment.WorkflowAgentAssignmentsValidator
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import skillbill.contracts.workflow.identity.task.WorkflowAgentAssignmentKeys as Keys

@Inject
class WorkflowAgentAssignmentsSchemaValidator : WorkflowAgentAssignmentsValidator {
  override fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray {
    if (sourceLabel.isBlank()) invalid("source label must be non-blank")
    val boundedSource = sourceLabel.take(MAXIMUM_SOURCE_LABEL_LENGTH)
    if (encoded.size > MAXIMUM_ENCODED_BYTES) invalid("payload exceeds $MAXIMUM_ENCODED_BYTES UTF-8 bytes")
    val raw = decodeUtf8(encoded)
    val instance = readJson(raw)
    if (!instance.isObject) invalid("payload must be a JSON object")
    val version = instance.path(Keys.CONTRACT_VERSION).takeIf { it.isTextual }?.asText()
    if (version != WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        WorkflowAgentAssignmentsFailureCode.UNSUPPORTED_VERSION,
        "Workflow agent assignments from '$boundedSource' use unsupported contract version " +
          "'${version?.take(MAXIMUM_VERSION_LABEL_LENGTH) ?: "missing"}'. " +
          "Restore the original record or migrate it with a supported runtime.",
      )
    }
    validateSchema(instance, boundedSource)
    validateVocabulary(instance, boundedSource)
    return canonical(instance).toString().toByteArray(Charsets.UTF_8)
  }

  private fun decodeUtf8(encoded: ByteArray): String =
    try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(encoded)).toString()
    } catch (_: CharacterCodingException) {
      invalid("payload is not valid UTF-8")
    }

  private fun readJson(raw: String): JsonNode =
    try {
      STRICT_JSON.readTree(raw) ?: invalid("payload is empty")
    } catch (_: JsonProcessingException) {
      invalid("payload is malformed or contains duplicate keys")
    }

  private fun validateSchema(
    instance: JsonNode,
    sourceLabel: String,
  ) {
    val errors = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) invalid("record from '$sourceLabel' violates the assignment schema")
  }

  private fun validateVocabulary(
    instance: JsonNode,
    sourceLabel: String,
  ) {
    if (instance.path(Keys.ADMISSION_WORKFLOW_ID).asText().isBlank()) {
      invalid("record from '$sourceLabel' has a blank admission identity")
    }
    val assignments = instance.path(Keys.PHASE_ASSIGNMENTS)
    val phaseNames = assignments.fieldNames().asSequence().toSet()
    if (!WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.containsAll(phaseNames)) {
      invalid("record from '$sourceLabel' contains an unknown phase")
    }
    val supportedAgents = SupportedAgent.entries.map(SupportedAgent::wireValue).toSet()
    val frozen = instance.path(Keys.POLICY_MODE).asText() == WorkflowAgentAssignmentPolicyMode.FROZEN.id
    if (frozen && phaseNames != WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.toSet()) {
      invalid("record from '$sourceLabel' has incomplete frozen phase coverage")
    }
    assignments.fields().asSequence().forEach { (_, assignment) ->
      val agentId = assignment.path(Keys.AGENT_ID).asText()
      if (agentId !in supportedAgents) invalid("record from '$sourceLabel' contains an unknown provider")
      val source = assignment.path(Keys.SOURCE).asText()
      val legacySource =
        source == WorkflowAgentAssignmentSource.LEGACY_RECORDED.id ||
          source == WorkflowAgentAssignmentSource.LEGACY_INVOKING.id
      if (frozen == legacySource) invalid("record from '$sourceLabel' mixes routing policy and provenance")
    }
  }

  private fun schema() =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = WorkflowAgentAssignmentsSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = WorkflowAgentAssignmentsSchemaValidator::class.java.classLoader,
        classpathResource = WorkflowAgentAssignmentsSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = { invalid("canonical workflow agent assignment schema is missing") },
        processingFailure = { invalid("canonical workflow agent assignment schema could not be loaded") },
        loadFailureLogger = {},
        expectedSchemaId = WorkflowAgentAssignmentsSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION,
        identityFailure = { invalid("canonical workflow agent assignment schema identity is invalid") },
      ),
    )

  private fun canonical(node: JsonNode): JsonNode =
    if (node.isObject) {
      val result = JsonNodeFactory.instance.objectNode()
      node.fieldNames().asSequence().sorted().forEach { key -> result.set<JsonNode>(key, canonical(node.get(key))) }
      result
    } else {
      node
    }

  private fun invalid(message: String): Nothing =
    throw SkillBillRuntimeException(
      WorkflowAgentAssignmentsFailureCode.INVALID_RECORD,
      "$message. Keep the source record and restore or repair it before resuming.",
    )

  private companion object {
    const val MAXIMUM_ENCODED_BYTES: Int = 65536
    const val MAXIMUM_SOURCE_LABEL_LENGTH: Int = 128
    const val MAXIMUM_VERSION_LABEL_LENGTH: Int = 32
    val STRICT_JSON: ObjectMapper =
      ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
  }
}
