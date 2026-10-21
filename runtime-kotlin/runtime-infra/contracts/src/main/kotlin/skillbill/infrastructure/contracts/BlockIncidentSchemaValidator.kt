package skillbill.infrastructure.contracts

import com.fasterxml.jackson.core.JsonProcessingException
import com.networknt.schema.JsonSchema
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.blockincident.BLOCK_INCIDENT_CONTRACT_VERSION
import skillbill.error.core.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.BlockIncidentSchemaPaths
import skillbill.ports.blockincident.BlockIncidentContractValidator

@Inject
class BlockIncidentSchemaValidator : BlockIncidentContractValidator {
  override fun validate(document: String) {
    val instance =
      try {
        ClasspathContractSchemaLoader.sharedObjectMapper().readTree(document)
      } catch (failure: JsonProcessingException) {
        throw invalidBlockIncidentSchema("Block incident document is not valid JSON.", failure)
      }
    if (instance == null || !instance.isObject) {
      throw invalidBlockIncidentSchema("Block incident document is not a JSON object.")
    }
    val violations = ClasspathContractSchemaLoader.validate(blockIncidentSchema(), instance)
    if (violations.isNotEmpty()) {
      throw invalidBlockIncidentSchema(
        "Block incident fails canonical contract $BLOCK_INCIDENT_CONTRACT_VERSION.",
      )
    }
  }
}

private fun invalidBlockIncidentSchema(message: String, cause: Throwable? = null) =
  SkillBillRuntimeException(BlockIncidentFailureCode.INVALID_SCHEMA, message, cause)

private fun blockIncidentSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = BlockIncidentSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = BlockIncidentSchemaValidator::class.java.classLoader,
      classpathResource = BlockIncidentSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = { invalidBlockIncidentSchema("Canonical block incident schema resource is missing.") },
      processingFailure = { cause ->
        invalidBlockIncidentSchema(cause.message ?: cause::class.simpleName.orEmpty(), cause)
      },
      loadFailureLogger = {},
      expectedSchemaId = BlockIncidentSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = BLOCK_INCIDENT_CONTRACT_VERSION,
      identityFailure = { reason -> invalidBlockIncidentSchema(reason) },
    ),
  )
