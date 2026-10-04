package skillbill.infrastructure.contracts
import com.networknt.schema.JsonSchema
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.output.REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidRejectedOutputDiagnosticSchemaError
import skillbill.infrastructure.contracts.locator.RejectedOutputDiagnosticSchemaPaths
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic

@Inject
class RejectedOutputDiagnosticSchemaValidator : RejectedOutputDiagnosticMetadataValidator {
  override fun validate(metadata: RejectedOutputDiagnostic) {
    val mapper = ClasspathContractSchemaLoader.sharedObjectMapper()
    val instance =
      mapper.createObjectNode().apply {
        put(SharedPayloadKeys.CONTRACT_VERSION, REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION)
        put("identity", metadata.identity)
        put(SharedPayloadKeys.WORKFLOW_ID, metadata.workflowId)
        put(SharedPayloadKeys.PHASE_ID, metadata.phaseId)
        put("attempt", metadata.attempt)
        put("repair_turn", metadata.repairTurn)
        put("rule", metadata.rule)
        put("path", metadata.path)
        put("reason", metadata.reason)
        put("agent_id", metadata.agentId)
        put("model", metadata.model)
        put("recorded_at", metadata.recordedAt.toString())
        put("byte_size", metadata.byteSize)
        put("sha256", metadata.sha256)
        put("lifecycle", metadata.lifecycle.name.lowercase())
      }
    val violations = ClasspathContractSchemaLoader.validate(rejectedOutputDiagnosticSchema(), instance)
    if (violations.isNotEmpty()) {
      throw invalidRejectedOutputDiagnosticSchemaError(
        "Rejected output diagnostic '${metadata.identity.ifBlank { "<invalid>" }}' fails canonical " +
          "contract $REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION.",
      )
    }
  }

  companion object {
    private val canonical: RejectedOutputDiagnosticSchemaValidator by lazy(::RejectedOutputDiagnosticSchemaValidator)

    fun validate(metadata: RejectedOutputDiagnostic) = canonical.validate(metadata)
  }
}

private fun rejectedOutputDiagnosticSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = RejectedOutputDiagnosticSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = RejectedOutputDiagnosticSchemaValidator::class.java.classLoader,
      classpathResource = RejectedOutputDiagnosticSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidRejectedOutputDiagnosticSchemaError(
          "Canonical rejected-output diagnostic schema resource is missing.",
        )
      },
      processingFailure = { cause ->
        invalidRejectedOutputDiagnosticSchemaError(
          cause.message ?: cause::class.simpleName.orEmpty(),
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = RejectedOutputDiagnosticSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION,
      identityFailure = { reason -> invalidRejectedOutputDiagnosticSchemaError(reason) },
    ),
  )
