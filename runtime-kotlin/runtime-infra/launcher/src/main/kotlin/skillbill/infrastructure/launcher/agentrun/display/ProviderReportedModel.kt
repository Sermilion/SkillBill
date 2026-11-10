package skillbill.infrastructure.launcher.agentrun.display

import com.fasterxml.jackson.databind.JsonNode
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.LAUNCH_MODEL_TOKEN_PATTERN

internal fun providerReportedModel(
  agent: String,
  event: JsonNode,
  diagnostics: RuntimeDiagnostics,
): String? {
  if (agent != "claude" && agent != "cursor") return null
  val model =
    when (event.path(ProviderModelReportKeys.TYPE).asText()) {
      "system" -> event.path(ProviderModelReportKeys.MODEL)
      "assistant", "message_start" -> event.path(ProviderModelReportKeys.MESSAGE).path(ProviderModelReportKeys.MODEL)
      else -> return null
    }
  if (model.isMissingNode || model.isNull) return null
  val identity = model.takeIf { it.isTextual }?.asText()?.takeIf(LAUNCH_MODEL_TOKEN_PATTERN::matches)
  if (identity == null) {
    diagnostics.warning("seam=provider_reported_model value_expected=model_identity value_used=malformed")
  }
  return identity
}

private object ProviderModelReportKeys {
  const val TYPE = "type"
  const val MODEL = "model"
  const val MESSAGE = "message"
}
