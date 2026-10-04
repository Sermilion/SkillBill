package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys

data class FeatureTaskRuntimeHandoffProjection(
  val projectionName: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  val producerIteration: FeatureTaskRuntimeProducerIteration =
    FeatureTaskRuntimeProducerIteration(
      phaseId =
        (sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)?.producingPhaseId
          ?: "runtime",
      iteration = 1,
    ),
) {
  val utf8ByteSize: Int
    get() = canonicalDeliveredRendering.toByteArray(Charsets.UTF_8).size
  val itemCount: Int get() = fields.sumOf { it.value.itemCount }
  val canonicalDeliveredRendering: String
    get() =
      if (promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE) {
        buildString {
          if (sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput) {
            appendLine("### from: ${sourceRef.producingPhaseId}")
          } else {
            appendLine("### $projectionName (${sourceRef.wireValue})")
          }
          fields.forEach { field ->
            val singleReceipt =
              fields.size == 1 &&
                field.name == "phase_output_receipt"
            when (val value = field.value) {
              is FeatureTaskRuntimeHandoffProjectionValue.Text ->
                when {
                  !value.text.isMultiLine() ->
                    if (singleReceipt) appendLine(value.text) else appendLine("${field.name}: ${value.text}")
                  singleReceipt -> appendFenced(value.text)
                  else -> {
                    appendLine("${field.name}:")
                    appendFenced(value.text)
                  }
                }
              is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
                appendLine("${field.name}:")
                value.items.forEach { item ->
                  if (item.isMultiLine()) appendFenced(item) else appendLine("  - $item")
                }
              }
              is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ->
                appendLine("${field.name}: ${value.kind.wireValue}=${value.value}")
            }
          }
        }
      } else {
        JsonCodec.mapToJsonString(toEnvelopeMap())
      }

  internal fun toEnvelopeMap(): Map<String, Any?> =
    linkedMapOf(
      "projection_name" to projectionName,
      "source_ref" to sourceRef.wireValue,
      "projection_contract_id" to projectionContractId,
      "projection_contract_version" to projectionContractVersion,
      "prompt_visibility" to promptVisibility.wireValue,
      "producer_iteration" to
        mapOf(
          SharedPayloadKeys.PHASE_ID to producerIteration.phaseId,
          "iteration" to producerIteration.iteration,
        ),
      "fields" to
        fields.map { field ->
          linkedMapOf<String, Any?>(DecompositionPlanningPayloadKeys.NAME to field.name).apply {
            when (val value = field.value) {
              is FeatureTaskRuntimeHandoffProjectionValue.Text -> {
                put("kind", "text")
                put("text", value.text)
              }
              is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
                put("kind", "text_list")
                put("items", value.items)
              }
              is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> {
                put("kind", "compact_reference")
                put("reference_kind", value.kind.wireValue)
                put("reference_value", value.value)
              }
            }
          }
        },
    )
}

private const val MINIMUM_FENCE_LENGTH = 3

private val BACKTICK_RUN = Regex("`+")

private fun String.isMultiLine(): Boolean = contains('\n') || contains('\r')

private fun StringBuilder.appendFenced(text: String) {
  val longestRun = BACKTICK_RUN.findAll(text).maxOfOrNull { it.value.length } ?: 0
  val fence = "`".repeat(maxOf(MINIMUM_FENCE_LENGTH, longestRun + 1))
  appendLine(fence)
  appendLine(text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n'))
  appendLine(fence)
}
