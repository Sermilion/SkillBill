package skillbill.infrastructure.contracts

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal data class ValidatedClasspathYamlNodeRequest(
  val classLoader: ClassLoader,
  val resource: String,
  val missingResource: () -> SkillBillRuntimeException,
  val processingFailure: (Throwable) -> SkillBillRuntimeException,
  val expectedSchemaId: String,
  val expectedContractVersion: String,
  val contractVersionPath: List<String> = listOf("properties", "contract_version", "const"),
  val contractVersionMatches: ((JsonNode, String) -> Boolean)? = null,
  val identityFailure: (String) -> SkillBillRuntimeException,
)

data class CompiledSchemaRequest(
  val cacheKey: String,
  val classLoader: ClassLoader,
  val classpathResource: String,
  val missingResource: () -> SkillBillRuntimeException,
  val processingFailure: (Throwable) -> SkillBillRuntimeException,
  val loadFailureLogger: (Throwable) -> Unit,
  val expectedSchemaId: String,
  val expectedContractVersion: String,
  val contractVersionPath: List<String> = listOf("properties", "contract_version", "const"),
  val contractVersionMatches: ((JsonNode, String) -> Boolean)? = null,
  val identityFailure: (String) -> SkillBillRuntimeException,
  val prepareSchemaDocument: (JsonNode) -> Unit = {},
)

data class SchemaIdentityRequest(
  val yamlNode: JsonNode,
  val classpathResource: String,
  val expectedSchemaId: String,
  val expectedContractVersion: String,
  val identityFailure: (String) -> SkillBillRuntimeException,
  val contractVersionPath: List<String> = listOf("properties", "contract_version", "const"),
  val contractVersionMatches: ((JsonNode, String) -> Boolean)? = null,
)

object ClasspathContractSchemaLoader {
  private val jsonSchemaFactory: JsonSchemaFactory =
    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  private val objectMapper: ObjectMapper = ObjectMapper()
  private val yamlMapper: YAMLMapper = YAMLMapper()
  private val compiledSchemas: ConcurrentHashMap<String, JsonSchema> = ConcurrentHashMap()

  fun sharedObjectMapper(): ObjectMapper = objectMapper

  fun sharedYamlMapper(): YAMLMapper = yamlMapper

  fun readClasspathYamlText(
    classLoader: ClassLoader,
    resource: String,
    missingError: () -> SkillBillRuntimeException,
  ): String {
    val stream = classLoader.getResourceAsStream(resource.removePrefix("/")) ?: throw missingError()
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  fun readClasspathYamlNode(
    classLoader: ClassLoader,
    resource: String,
    missingError: () -> SkillBillRuntimeException,
  ): JsonNode {
    val text = readClasspathYamlText(classLoader, resource, missingError)
    return yamlMapper.readTree(text)
  }

  internal fun readValidatedClasspathYamlNode(request: ValidatedClasspathYamlNodeRequest): JsonNode =
    try {
      val node = readClasspathYamlNode(request.classLoader, request.resource, request.missingResource)
      validateIdentity(
        SchemaIdentityRequest(
          yamlNode = node,
          classpathResource = request.resource,
          expectedSchemaId = request.expectedSchemaId,
          expectedContractVersion = request.expectedContractVersion,
          contractVersionPath = request.contractVersionPath,
          contractVersionMatches = request.contractVersionMatches,
          identityFailure = request.identityFailure,
        ),
      )
      node
    } catch (cancellation: CancellationException) {
      rethrow(cancellation)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      rethrow(error)
    } catch (error: IOException) {
      throw request.processingFailure(error)
    } catch (error: IllegalArgumentException) {
      throw request.processingFailure(error)
    }

  fun compiledSchema(request: CompiledSchemaRequest): JsonSchema =
    compiledSchemas.computeIfAbsent(request.cacheKey) {
      compileSchemaDocument(request)
    }

  fun compiledSchemaFromYamlNode(
    cacheKey: String,
    yamlNode: JsonNode,
    processingFailure: (Throwable) -> SkillBillRuntimeException,
  ): JsonSchema =
    compiledSchemas.computeIfAbsent(cacheKey) {
      try {
        jsonSchemaFactory.getSchema(objectMapper.writeValueAsString(yamlNode), LOCALE_STABLE_SCHEMA_CONFIG)
      } catch (cancellation: CancellationException) {
        rethrow(cancellation)
      } catch (error: JsonProcessingException) {
        throw processingFailure(error)
      } catch (error: IllegalArgumentException) {
        throw processingFailure(error)
      }
    }

  fun compileUncachedYamlNode(yamlNode: JsonNode): JsonSchema =
    jsonSchemaFactory.getSchema(objectMapper.writeValueAsString(yamlNode), LOCALE_STABLE_SCHEMA_CONFIG)

  fun validate(
    schema: JsonSchema,
    instance: JsonNode,
  ): Set<ValidationMessage> = schema.validate(instance)

  fun valueToTree(map: Map<String, Any?>): JsonNode = objectMapper.valueToTree(map)

  fun validateSchemaIdentity(request: SchemaIdentityRequest) = validateIdentity(request)

  private fun compileSchemaDocument(request: CompiledSchemaRequest): JsonSchema {
    try {
      val yamlText = readClasspathYamlText(request.classLoader, request.classpathResource, request.missingResource)
      val yamlNode = yamlMapper.readTree(yamlText)
      validateIdentity(
        SchemaIdentityRequest(
          yamlNode = yamlNode,
          classpathResource = request.classpathResource,
          expectedSchemaId = request.expectedSchemaId,
          expectedContractVersion = request.expectedContractVersion,
          contractVersionPath = request.contractVersionPath,
          contractVersionMatches = request.contractVersionMatches,
          identityFailure = request.identityFailure,
        ),
      )
      request.prepareSchemaDocument(yamlNode)
      return jsonSchemaFactory.getSchema(objectMapper.writeValueAsString(yamlNode), LOCALE_STABLE_SCHEMA_CONFIG)
    } catch (cancellation: CancellationException) {
      rethrow(cancellation)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      request.loadFailureLogger(error)
      rethrow(error)
    } catch (error: JsonProcessingException) {
      throwCompiledSchemaFailure(request, error)
    } catch (error: IOException) {
      throwCompiledSchemaFailure(request, error)
    } catch (error: IllegalArgumentException) {
      throwCompiledSchemaFailure(request, error)
    }
  }

  private fun validateIdentity(request: SchemaIdentityRequest) {
    val loadedId = request.yamlNode.path("\$id").asText("")
    if (loadedId != request.expectedSchemaId) {
      throw request.identityFailure(
        "Canonical schema identity mismatch for '${request.classpathResource}': loaded '\$id' is '$loadedId' " +
          "but expected '${request.expectedSchemaId}'.",
      )
    }
    val loadedVersion =
      request.contractVersionPath
        .fold(request.yamlNode) { node, segment -> node.path(segment) }
        .asText("")
    val versionMatches =
      request.contractVersionMatches?.invoke(request.yamlNode, request.expectedContractVersion)
        ?: (loadedVersion == request.expectedContractVersion)
    if (!versionMatches) {
      throw request.identityFailure(
        "Canonical schema contract version mismatch for '${request.classpathResource}': loaded '$loadedVersion' " +
          "but expected '${request.expectedContractVersion}'.",
      )
    }
  }

  private fun throwCompiledSchemaFailure(
    request: CompiledSchemaRequest,
    error: Throwable,
  ): Nothing {
    val wrapped = request.processingFailure(error)
    request.loadFailureLogger(wrapped)
    throw wrapped
  }

  private fun rethrow(error: Throwable): Nothing = throw error
}
