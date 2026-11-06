package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import skillbill.error.core.catchingNonCancellation
import java.nio.file.Files
import java.nio.file.Path
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

object CodexRefusalSchema {
  fun inspect(directory: Path): String? =
    catchingNonCancellation {
      val permissions = read(directory.resolve("PermissionsRequestApprovalResponse.json"))
      val elicitation = read(directory.resolve("McpServerElicitationRequestResponse.json"))
      if (!permissionsCompatible(permissions) || !elicitationCompatible(elicitation)) {
        "Codex does not expose the supported empty-grant and elicitation-refusal " +
          "response shapes."
      } else {
        null
      }
    }.getOrElse { "Could not verify Codex refusal response schemas: ${it.message.orEmpty()}" }

  private fun permissionsCompatible(schema: JsonObject): Boolean {
    val properties = schema[S.PROPERTIES] as? JsonObject
    val shape = required(schema).contains(K.PERMISSIONS) && setOf(K.PERMISSIONS, K.SCOPE).containsAll(required(schema))
    val grants = emptyObject(properties?.get(K.PERMISSIONS), schema)
    return shape && grants && "turn" in tokens(properties?.get(K.SCOPE), schema)
  }

  private fun elicitationCompatible(schema: JsonObject): Boolean {
    val properties = schema[S.PROPERTIES] as? JsonObject
    return setOf(K.ACTION, K.CONTENT).containsAll(required(schema)) &&
      K.DECLINE in tokens(properties?.get(K.ACTION), schema) && properties?.containsKey(K.CONTENT) == true
  }

  private fun read(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)) as JsonObject

  private fun required(node: JsonObject): Set<String> =
    (node[S.REQUIRED] as? JsonArray).orEmpty()
      .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()

  private fun resolve(
    value: JsonElement?,
    root: JsonObject,
  ): JsonObject? {
    val node = value as? JsonObject ?: return null
    val ref = (node[S.REF] as? JsonPrimitive)?.content ?: return node
    return (root[S.DEFINITIONS] as? JsonObject)?.get(ref.substringAfterLast('/')) as? JsonObject
  }

  private fun emptyObject(
    value: JsonElement?,
    root: JsonObject,
  ): Boolean {
    val node = resolve(value, root) ?: return false
    return (node[S.TYPE] as? JsonPrimitive)?.content == "object" && required(node).isEmpty()
  }

  private fun tokens(
    value: JsonElement?,
    root: JsonObject,
  ): Set<String> {
    val node = resolve(value, root) ?: return emptySet()
    val choices = (node[S.ENUM] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val nested = (node[S.ALL_OF] as? JsonArray).orEmpty().flatMap { tokens(it, root) }
    return (choices + nested).toSet()
  }

  private object S {
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val REF = "\$ref"
    const val DEFINITIONS = "definitions"
    const val TYPE = "type"
    const val ENUM = "enum"
    const val ALL_OF = "allOf"
  }
}
