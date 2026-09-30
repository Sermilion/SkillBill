package skillbill.engine.featuretask.slot.state

enum class RequiredPhaseWriteKind(val wireValue: String) {
  START("start"),
  BRIEFING("briefing"),
}

class RequiredPhaseWriteRejected(
  val writeKind: RequiredPhaseWriteKind,
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
) : IllegalStateException(
    "Required ${writeKind.wireValue} write rejected for phase '$phaseId', attempt $attempt.",
  )
