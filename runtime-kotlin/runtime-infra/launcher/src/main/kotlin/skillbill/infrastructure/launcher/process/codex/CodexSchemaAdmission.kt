package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

sealed interface CodexSteeringAdmission {
  data object Available : CodexSteeringAdmission

  data class Unavailable(val diagnostic: String) : CodexSteeringAdmission
}

object CodexSchemaAdmission {
  private const val MAX_SCHEMA_BYTES = 300_000

  fun inspectPinnedSteering(): CodexSteeringAdmission {
    val params = readSchema(K.SCHEMA_STEERING_PARAMS_RESOURCE)
    if (params.value == null) return unavailable(params.diagnostic)
    val response = readSchema(K.SCHEMA_STEERING_RESPONSE_RESOURCE)
    if (response.value == null) return unavailable(response.diagnostic)
    val clientRequest = readSchema(K.SCHEMA_CLIENT_REQUEST_RESOURCE)
    if (clientRequest.value == null) return unavailable(clientRequest.diagnostic)
    return inspectSteering(params.value, response.value, clientRequest.value)
  }

  internal fun inspectSteering(
    params: JsonObject?,
    response: JsonObject?,
    clientRequest: JsonObject?,
  ): CodexSteeringAdmission {
    val reason: String? =
      when {
        params == null -> "TurnSteerParams schema is missing"
        response == null -> "TurnSteerResponse schema is missing"
        clientRequest == null -> "ClientRequest schema is missing"
        response[K.TYPE].stringValue() != K.SCHEMA_OBJECT -> "TurnSteerResponse must be an object"
        !response.hasRequiredString(K.TURN_ID) -> "TurnSteerResponse must require a string turnId"
        else -> {
          val boundParams = clientRequest.steeringParams(params)
          when {
            boundParams == null -> "ClientRequest does not bind turn/steer to this TurnSteerParams definition"
            !boundParams.hasSteeringShape(clientRequest) ->
              "Bound TurnSteerParams does not accept the required text and localImage inputs"
            else -> null
          }
        }
      }
    return if (reason == null) CodexSteeringAdmission.Available else unavailable(reason)
  }

  private fun JsonObject.steeringParams(exportedParams: JsonObject): JsonObject? {
    val alternatives = this[K.SCHEMA_ONE_OF] as? JsonArray ?: return null
    val request =
      alternatives.mapNotNull { it as? JsonObject }
        .singleOrNull { it.acceptsMethod(K.TURN_STEER_METHOD) } ?: return null
    val paramsReference = request.schemaProperties()?.get(K.PARAMS).schemaReference()
    return paramsReference
      ?.takeIf { it == "#/${K.SCHEMA_DEFINITIONS}/${K.TURN_STEER_PARAMS}" }
      ?.takeIf { request.requiredFields().containsAll(listOf(K.ID, K.METHOD, K.PARAMS)) }
      ?.let { resolveDefinition(it) }
      ?.takeIf { it.matchesExportedParams(exportedParams, this) }
  }

  private fun JsonObject.acceptsMethod(method: String): Boolean {
    val methodSchema = schemaProperties()?.get(K.METHOD) as? JsonObject ?: return false
    return methodSchema.acceptsSingleString(K.SCHEMA_CONST, method) ||
      methodSchema.acceptsSingleString(K.SCHEMA_ENUM, method)
  }

  private fun JsonObject.matchesExportedParams(
    exported: JsonObject,
    clientRequest: JsonObject,
  ): Boolean {
    val boundProperties = schemaProperties() ?: return false
    val exportedProperties = exported.schemaProperties() ?: return false
    return this[K.TYPE].stringValue() == K.SCHEMA_OBJECT &&
      this[K.SCHEMA_REQUIRED] == exported[K.SCHEMA_REQUIRED] &&
      matchingInputDefinitions(exported, clientRequest) &&
      listOf(K.THREAD_ID, K.EXPECTED_TURN_ID, K.INPUT).all { key ->
        boundProperties[key] == exportedProperties[key]
      }
  }

  private fun JsonObject.inputReference(): String? =
    schemaProperties()?.get(K.INPUT).asJsonObject()?.get(K.SCHEMA_ITEMS).schemaReference()

  private fun JsonObject.matchingInputDefinitions(
    exported: JsonObject,
    clientRequest: JsonObject,
  ): Boolean {
    val boundReference = inputReference() ?: return false
    val exportedReference = exported.inputReference() ?: return false
    val boundDefinition = clientRequest.resolveDefinition(boundReference) ?: return false
    return boundReference == exportedReference && boundDefinition == exported.resolveDefinition(exportedReference)
  }

  private fun JsonObject.hasSteeringShape(clientRequest: JsonObject): Boolean {
    val input = schemaProperties()?.get(K.INPUT) as? JsonObject
    val reference = inputReference() ?: return false
    val userInput = clientRequest.resolveDefinition(reference) ?: return false
    return this[K.TYPE].stringValue() == K.SCHEMA_OBJECT &&
      hasRequiredString(K.THREAD_ID) && hasRequiredString(K.EXPECTED_TURN_ID) &&
      K.INPUT in requiredFields() && input.schemaType() == K.SCHEMA_ARRAY &&
      userInput.hasInputVariant(CodexAppServerProtocol.TEXT_TYPE) &&
      userInput.hasInputVariant(CodexAppServerProtocol.LOCAL_IMAGE_TYPE)
  }

  private fun JsonObject.hasInputVariant(type: String): Boolean {
    val alternatives = this[K.SCHEMA_ONE_OF] as? JsonArray ?: return false
    return alternatives.mapNotNull { it as? JsonObject }.any { alternative ->
      val properties = alternative.schemaProperties() ?: return@any false
      val tag = properties[K.TYPE] as? JsonObject ?: return@any false
      if (tag[K.TYPE].stringValue() != K.SCHEMA_STRING) return@any false
      if (!(tag.acceptsSingleString(K.SCHEMA_CONST, type) || tag.acceptsSingleString(K.SCHEMA_ENUM, type))) {
        return@any false
      }
      val emitted =
        when (type) {
          CodexAppServerProtocol.TEXT_TYPE -> setOf(K.TYPE, K.TEXT, K.TEXT_ELEMENTS)
          CodexAppServerProtocol.LOCAL_IMAGE_TYPE -> setOf(K.TYPE, K.PATH)
          else -> return@any false
        }
      if (!alternative.requiredFields().all { it in emitted }) return@any false
      if (K.TYPE !in alternative.requiredFields()) return@any false
      properties.acceptsInputPayload(type)
    }
  }

  private fun JsonObject.acceptsInputPayload(type: String): Boolean =
    when (type) {
      CodexAppServerProtocol.TEXT_TYPE -> {
        val textElements = this[K.TEXT_ELEMENTS] as? JsonObject
        this[K.TEXT].schemaType() == K.SCHEMA_STRING &&
          textElements != null && textElements.schemaType() == K.SCHEMA_ARRAY && textElements.acceptsEmptyArray()
      }
      CodexAppServerProtocol.LOCAL_IMAGE_TYPE -> this[K.PATH].schemaType() == K.SCHEMA_STRING
      else -> false
    }

  private fun JsonObject.hasRequiredString(name: String): Boolean =
    name in requiredFields() && schemaProperties()?.get(name).schemaType() == K.SCHEMA_STRING

  private fun JsonObject.schemaProperties(): JsonObject? = this[K.SCHEMA_PROPERTIES].asJsonObject()

  private fun JsonObject.requiredFields(): Set<String> =
    (this[K.SCHEMA_REQUIRED] as? JsonArray).orEmpty().mapNotNull { it.stringValue() }.toSet()

  private fun JsonElement?.schemaType(): String? = (this as? JsonObject)?.get(K.TYPE).stringValue()

  private fun JsonElement?.schemaReference(): String? = (this as? JsonObject)?.get(K.SCHEMA_REF).stringValue()

  private fun JsonObject.acceptsSingleString(
    key: String,
    expected: String,
  ): Boolean {
    val value = this[key]
    return if (key == K.SCHEMA_CONST) {
      value.stringValue() == expected
    } else {
      (value as? JsonArray)?.singleOrNull()?.stringValue() == expected
    }
  }

  private fun JsonObject.acceptsEmptyArray(): Boolean {
    val minimum = this[K.SCHEMA_MIN_ITEMS] ?: return true
    return (minimum as? JsonPrimitive)?.intOrNull == 0
  }

  private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject

  private fun JsonObject.resolveDefinition(reference: String): JsonObject? {
    val prefix = "#/${K.SCHEMA_DEFINITIONS}/"
    if (!reference.startsWith(prefix)) return null
    val name = reference.removePrefix(prefix).replace("~1", "/").replace("~0", "~")
    return (this[K.SCHEMA_DEFINITIONS] as? JsonObject)?.get(name) as? JsonObject
  }

  private fun readSchema(resource: String): SchemaRead {
    val stream =
      CodexSchemaAdmission::class.java.getResourceAsStream(resource)
        ?: return SchemaRead(null, "Pinned Codex $CODEX_PROTOCOL_VERSION schema resource is missing: $resource")
    return try {
      val bytes = stream.use { it.readNBytes(MAX_SCHEMA_BYTES + 1) }
      if (bytes.size > MAX_SCHEMA_BYTES) {
        SchemaRead(null, "Pinned Codex schema resource exceeds the $MAX_SCHEMA_BYTES byte limit: $resource")
      } else {
        val document = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        if (document == null) {
          SchemaRead(null, "Pinned Codex schema resource is not a JSON object: $resource")
        } else {
          SchemaRead(document, "")
        }
      }
    } catch (error: IOException) {
      SchemaRead(null, "Could not read pinned Codex schema resource $resource: ${error.message}")
    } catch (error: SerializationException) {
      SchemaRead(null, "Pinned Codex schema resource is malformed: $resource: ${error.message}")
    }
  }

  private fun unavailable(reason: String) = CodexSteeringAdmission.Unavailable(reason)

  private data class SchemaRead(val value: JsonObject?, val diagnostic: String)
}
