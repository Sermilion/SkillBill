package skillbill.scaffold.policy

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope

fun parseBaselineLayerPayload(
  index: Int,
  raw: Any?,
): CodeReviewBaselineLayer {
  val layer =
    raw as? Map<*, *>
      ?: failBaselineLayerPayload("Scaffold payload field 'baseline_layers[$index]' must be an object.")
  val fieldPrefix = "baseline_layers[$index]"
  val scopeValue = requireStringInPayloadMap(layer, "$fieldPrefix.scope", "scope")
  val modeValue = requireStringInPayloadMap(layer, "$fieldPrefix.mode", "mode")
  val required =
    layer["required"] as? Boolean
      ?: failBaselineLayerPayload(
        "Scaffold payload field '$fieldPrefix.required' must be an explicit boolean.",
      )
  return CodeReviewBaselineLayer(
    platform = requireStringInPayloadMap(layer, "$fieldPrefix.platform", "platform"),
    skill = requireStringInPayloadMap(layer, "$fieldPrefix.skill", "skill"),
    scope =
      CodeReviewCompositionScope.fromWireValue(scopeValue)
        ?: failBaselineLayerPayload(
          "Scaffold payload field '$fieldPrefix.scope' has unsupported value '$scopeValue'. " +
            "Supported values: ${CodeReviewCompositionScope.entries.map { it.wireValue }}.",
        ),
    required = required,
    mode =
      CodeReviewCompositionMode.fromWireValue(modeValue)
        ?: failBaselineLayerPayload(
          "Scaffold payload field '$fieldPrefix.mode' has unsupported value '$modeValue'. " +
            "Supported values: ${CodeReviewCompositionMode.entries.map { it.wireValue }}.",
        ),
  )
}

private fun failBaselineLayerPayload(message: String): Nothing = throw invalidScaffoldPayloadError(message)
