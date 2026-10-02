package skillbill.application.scaffold

import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonCodec
import skillbill.scaffold.model.command.ScaffoldCommandRequest

const val SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE: String = "Invalid JSON payload: expected an object."

fun decodeScaffoldPayloadObject(payloadText: String): JsonObject? = JsonCodec.parseObjectOrNull(payloadText)

fun decodeScaffoldCommandRequest(payloadText: String): ScaffoldCommandRequest =
  decodeScaffoldCommandRequest(
    decodeScaffoldPayloadObject(payloadText) ?: throw IllegalArgumentException(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE),
  )

fun decodeScaffoldCommandRequest(payload: JsonObject): ScaffoldCommandRequest {
  val map =
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(payload))
      ?: throw IllegalArgumentException(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE)
  val normalized = map.toMutableMap()
  normalized["scaffold_payload_version"] = normalized["scaffold_payload_version"]?.toString()
  return parseScaffoldCommandRequest(normalized)
}
