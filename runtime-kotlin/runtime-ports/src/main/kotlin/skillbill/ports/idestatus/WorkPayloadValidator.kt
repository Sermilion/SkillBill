package skillbill.ports.idestatus

import kotlinx.serialization.json.JsonObject

interface WorkPayloadValidator {
  fun validateBoard(payload: JsonObject, sourceLabel: String)

  fun validateDetail(payload: JsonObject, sourceLabel: String)
}
