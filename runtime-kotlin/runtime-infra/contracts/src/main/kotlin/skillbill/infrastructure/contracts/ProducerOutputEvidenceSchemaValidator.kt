package skillbill.infrastructure.contracts
import com.networknt.schema.JsonSchema
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.output.PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidProducerOutputEvidenceSchemaError
import skillbill.infrastructure.contracts.locator.ProducerOutputEvidenceSchemaPaths
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence

@Inject
class ProducerOutputEvidenceSchemaValidator : ProducerOutputEvidenceValidator {
  override fun validate(evidence: ProducerOutputEvidence) {
    val mapper = ClasspathContractSchemaLoader.sharedObjectMapper()
    val instance =
      mapper.createObjectNode().apply {
        put(SharedPayloadKeys.CONTRACT_VERSION, PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION)
        put(SharedPayloadKeys.WORKFLOW_ID, evidence.workflowId)
        put(SharedPayloadKeys.PHASE_ID, evidence.phaseId)
        put("generation", evidence.generation)
        put("attempt", evidence.attempt)
        put("repair_turn", evidence.repairTurn)
        put("agent_id", evidence.agentId)
        put("model", evidence.model)
        put("recorded_at", evidence.recordedAt.toString())
        put("byte_size", evidence.byteSize)
        put("sha256", evidence.sha256)
      }
    val violations = ClasspathContractSchemaLoader.validate(producerOutputEvidenceSchema(), instance)
    if (violations.isNotEmpty()) {
      throw invalidProducerOutputEvidenceSchemaError(
        "Producer output evidence '${evidence.workflowId}:${evidence.phaseId}:" +
          "${evidence.generation}:${evidence.attempt}:${evidence.repairTurn}' fails canonical contract " +
          "$PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION.",
      )
    }
  }

  companion object {
    private val canonical: ProducerOutputEvidenceSchemaValidator by lazy(::ProducerOutputEvidenceSchemaValidator)

    fun validate(evidence: ProducerOutputEvidence) = canonical.validate(evidence)
  }
}

private fun producerOutputEvidenceSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = ProducerOutputEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = ProducerOutputEvidenceSchemaValidator::class.java.classLoader,
      classpathResource = ProducerOutputEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidProducerOutputEvidenceSchemaError("Canonical producer output evidence schema resource is missing.")
      },
      processingFailure = { cause ->
        invalidProducerOutputEvidenceSchemaError(
          cause.message ?: cause::class.simpleName.orEmpty(),
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = ProducerOutputEvidenceSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION,
      identityFailure = { reason -> invalidProducerOutputEvidenceSchemaError(reason) },
    ),
  )
