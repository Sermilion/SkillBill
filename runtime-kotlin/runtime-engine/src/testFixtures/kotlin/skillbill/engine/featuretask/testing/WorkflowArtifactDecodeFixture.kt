package skillbill.engine.featuretask.testing

import skillbill.contracts.JsonCodec

fun decodeWorkflowArtifactsForTest(artifactsJson: String): Map<String, Any?> {
  val element = JsonCodec.json.parseToJsonElement(artifactsJson)
  return requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element)))
}
