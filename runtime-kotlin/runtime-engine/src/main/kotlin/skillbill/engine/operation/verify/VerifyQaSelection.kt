package skillbill.engine.operation.verify

import skillbill.engine.operation.core.OperationOutcome
import skillbill.workflow.verify.model.VerifyQaSelection

internal inline fun confirmedQaSelection(
  workflowId: String,
  inputContext: Any?,
  requested: String?,
  refuse: (OperationOutcome) -> Nothing,
): VerifyQaSelection? {
  val raw = VerifyWorkflow.string(inputContext, VerifyWorkflow.QA_SELECTION)
  val stored =
    raw?.let {
      VerifyQaSelection.fromWire(it)
        ?: refuse(
          OperationOutcome.Failed(
            "Verify workflow '$workflowId' stores qa_selection '$raw', expected ${VerifyQaSelection.EXPECTED}; " +
              "rerun operation verify.",
          ),
        )
    }
  val effective = stored ?: VerifyQaSelection.OFF
  val asked = requested?.trim()?.let(VerifyQaSelection::fromWire)
  if (asked != null && asked != effective) {
    refuse(
      OperationOutcome.Blocked(
        "Verify workflow '$workflowId' was proposed with qa:${effective.wireValue}; confirm cannot change it to " +
          "qa:${asked.wireValue}. Confirm without qa: or with qa:${effective.wireValue}, or start a new " +
          "operation verify with qa:${asked.wireValue}.",
      ),
    )
  }
  return stored
}
