package skillbill.engine.operation.verify

import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.operation.core.OperationArguments
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

internal data class ConfirmedQa(
  val selection: VerifyQaSelection?,
  val device: QaNamedDevice?,
)

internal inline fun confirmedQa(
  workflowId: String,
  inputContext: Any?,
  arguments: OperationArguments,
  refuse: (OperationOutcome) -> Nothing,
): ConfirmedQa =
  ConfirmedQa(
    confirmedQaSelection(workflowId, inputContext, arguments.qa, refuse),
    confirmedQaDevice(workflowId, inputContext, arguments.qaDevice, refuse),
  )

internal inline fun confirmedQaDevice(
  workflowId: String,
  inputContext: Any?,
  requested: QaNamedDevice?,
  refuse: (OperationOutcome) -> Nothing,
): QaNamedDevice? {
  val raw = VerifyWorkflow.string(inputContext, VerifyWorkflow.QA_DEVICE)
  val stored =
    raw?.let {
      QaNamedDevice.fromWireValue(it)
        ?: refuse(
          OperationOutcome.Failed(
            "Verify workflow '$workflowId' stores qa_device '$raw', expected serial:<serial> or avd:<name>; " +
              "rerun operation verify.",
          ),
        )
    }
  if (requested != null && requested != stored) {
    val proposed = stored?.let { "QA device ${it.wireValue}" } ?: "no QA device"
    refuse(
      OperationOutcome.Blocked(
        "Verify workflow '$workflowId' was proposed with $proposed; confirm cannot change it to " +
          "${requested.wireValue}. Confirm without $QA_DEVICE_FLAG or start a new operation verify with that device.",
      ),
    )
  }
  return stored
}
