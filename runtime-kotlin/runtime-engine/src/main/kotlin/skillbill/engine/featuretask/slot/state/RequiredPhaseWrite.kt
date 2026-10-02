package skillbill.engine.featuretask.slot.state

enum class RequiredPhaseWriteKind(val wireValue: String) {
  START("start"),
  BRIEFING("briefing"),
}

sealed interface RequiredPhaseWrite {
  data object Acknowledged : RequiredPhaseWrite

  data class Rejected(
    val writeKind: RequiredPhaseWriteKind,
    val workflowId: String,
    val phaseId: String,
    val attempt: Int,
  ) : RequiredPhaseWrite {
    val message: String =
      "Required ${writeKind.wireValue} write rejected for phase '$phaseId', attempt $attempt."
  }
}
