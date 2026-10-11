package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WORK_BOARD_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.WORK_DETAIL_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.WorkBoardPayloadKeys
import skillbill.error.shellcontent.invalidWorkBoardSchemaError
import skillbill.error.shellcontent.invalidWorkDetailSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.IdeStatusSchemaPaths
import skillbill.infrastructure.contracts.locator.WorkBoardSchemaPaths
import skillbill.infrastructure.contracts.locator.WorkDetailSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.ports.idestatus.WorkPayloadValidator
import java.util.logging.Logger

@Inject
class WorkPayloadSchemaValidator : WorkPayloadValidator {
  override fun validateBoard(payload: JsonObject, sourceLabel: String) =
    validate(payload, sourceLabel, boardSchema(), true)

  override fun validateDetail(payload: JsonObject, sourceLabel: String) =
    validate(payload, sourceLabel, detailSchema(), false)

  private fun validate(
    payload: JsonObject,
    sourceLabel: String,
    schema: JsonSchema,
    board: Boolean,
  ) {
    val instance = ClasspathContractSchemaLoader.sharedObjectMapper().readTree(payload.toString())
    val errors = ClasspathContractSchemaLoader.validate(schema, instance)
    if (errors.isEmpty()) return
    val orderedErrors = errors.sortedWith(violationOrdering)
    val first = orderedErrors.first()
    val reason = formatValidationReason(orderedErrors, instance)
    if (board) {
      throw invalidWorkBoardSchemaError(sourceLabel, first.instanceLocation?.toString().orEmpty(), reason)
    }
    throw invalidWorkDetailSchemaError(sourceLabel, first.instanceLocation?.toString().orEmpty(), reason)
  }

  private fun boardSchema() =
    ClasspathContractSchemaLoader.compiledSchema(
      schemaRequest(
        WorkBoardSchemaPaths.CLASSPATH_RESOURCE,
        WorkBoardSchemaPaths.REPO_RELATIVE_PATH,
        WorkBoardSchemaPaths.EXPECTED_SCHEMA_ID,
        WORK_BOARD_CONTRACT_VERSION,
        true,
      ),
    )

  private fun detailSchema() =
    ClasspathContractSchemaLoader.compiledSchema(
      schemaRequest(
        WorkDetailSchemaPaths.CLASSPATH_RESOURCE,
        WorkDetailSchemaPaths.REPO_RELATIVE_PATH,
        WorkDetailSchemaPaths.EXPECTED_SCHEMA_ID,
        WORK_DETAIL_CONTRACT_VERSION,
        false,
      ),
    )

  private fun schemaRequest(
    resource: String,
    repoPath: String,
    schemaId: String,
    version: String,
    board: Boolean,
  ): CompiledSchemaRequest =
    CompiledSchemaRequest(
      cacheKey = resource,
      classLoader = WorkPayloadSchemaValidator::class.java.classLoader,
      classpathResource = resource,
      missingResource = { failure(resource, board, "Canonical schema is missing from the classpath.") },
      processingFailure = { cause ->
        failure(resource, board, cause.message ?: cause::class.simpleName.orEmpty(), cause)
      },
      loadFailureLogger = { error -> logSchemaLoadFailure(logger, "Work payload", resource, repoPath, error) },
      expectedSchemaId = schemaId,
      expectedContractVersion = version,
      prepareSchemaDocument = { schema ->
        val ideSchema =
          ClasspathContractSchemaLoader.readClasspathYamlNode(
            WorkPayloadSchemaValidator::class.java.classLoader,
            IdeStatusSchemaPaths.CLASSPATH_RESOURCE,
            { failure(resource, board, "Canonical IDE status schema is missing.") },
          )
        val schemaObject = schema as? ObjectNode ?: throw failure(resource, board, "Schema root must be an object.")
        val definitions = schemaObject.requireObject("\$defs", "\$defs", resource, board)
        val item = definitions.requireObject("item", "\$defs.item", resource, board)
        val properties = item.requireObject("properties", "\$defs.item.properties", resource, board)
        properties.set<JsonNode>(WorkBoardPayloadKeys.SNAPSHOT, ideSchema)
      },
      identityFailure = { reason -> failure(resource, board, reason) },
    )

  private fun failure(
    source: String,
    board: Boolean,
    reason: String,
    cause: Throwable? = null,
  ) =
    if (board) invalidWorkBoardSchemaError(source, "<schema>", reason, cause)
    else invalidWorkDetailSchemaError(source, "<schema>", reason, cause)

  private fun ObjectNode.requireObject(
    name: String,
    path: String,
    resource: String,
    board: Boolean,
  ): ObjectNode =
    get(name) as? ObjectNode ?: throw failure(resource, board, "Schema path '$path' must be an object.")

  companion object {
    private val logger = Logger.getLogger("skillbill.contracts.workflow.WorkPayloadSchemaValidator")
  }
}
