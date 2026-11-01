package skillbill.engine.featuretask.slot.state

import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext

public enum class RequiredPhaseWriteKind(internal val wireValue: String) {
  START("start"),
  BRIEFING("briefing"),
  COMPLETION("completion"),
  IMPLEMENTATION_PROGRESS("implementation-progress"),
}

public sealed interface RequiredPhaseWrite {
  public data object Acknowledged : RequiredPhaseWrite

  public data class Rejected(
    val writeKind: RequiredPhaseWriteKind,
    val workflowId: String,
    val phaseId: String,
    val attempt: Int,
    val handoffRejection: InvalidFeatureTaskRuntimeHandoffProjectionContext? = null,
    val detail: String? = null,
  ) : RequiredPhaseWrite {
    public val message: String =
      "Required ${writeKind.wireValue} write rejected for phase '$phaseId', attempt $attempt." +
        detail?.let { " $it" }.orEmpty()
  }
}
