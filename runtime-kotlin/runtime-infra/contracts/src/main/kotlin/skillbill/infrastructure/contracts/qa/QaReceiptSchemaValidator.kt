package skillbill.infrastructure.contracts.qa

import com.networknt.schema.JsonSchema
import skillbill.contracts.qa.QA_RECEIPT_CONTRACT_VERSION
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.QaReceiptSchemaPaths
import skillbill.ports.qa.QaReceiptSchemaPort

private const val MAX_REPORTED_VIOLATIONS = 3

class QaReceiptSchemaValidator : QaReceiptSchemaPort {
  override fun violation(receiptJson: String): String? =
    ClasspathContractSchemaLoader.validate(
      schema(),
      ClasspathContractSchemaLoader.sharedObjectMapper().readTree(receiptJson),
    )
      .takeIf { it.isNotEmpty() }
      ?.sortedBy { it.instanceLocation.toString() }
      ?.take(MAX_REPORTED_VIOLATIONS)
      ?.joinToString(" | ") { it.message }

  private fun schema(): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = QaReceiptSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = QaReceiptSchemaValidator::class.java.classLoader,
        classpathResource = QaReceiptSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = {
          invalidQaReceipt(
            "the canonical QA receipt schema is missing from the classpath at " +
              "'${QaReceiptSchemaPaths.CLASSPATH_RESOURCE}'.",
          )
        },
        processingFailure = { cause ->
          invalidQaReceipt(cause.message ?: cause::class.simpleName.orEmpty(), cause)
        },
        loadFailureLogger = {},
        expectedSchemaId = QaReceiptSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = QA_RECEIPT_CONTRACT_VERSION,
        identityFailure = { reason -> invalidQaReceipt(reason) },
      ),
    )
}
