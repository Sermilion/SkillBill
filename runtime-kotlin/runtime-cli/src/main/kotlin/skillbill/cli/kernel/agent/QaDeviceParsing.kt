package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.featuretask.model.execution.QaNamedDevice

internal fun resolveRequestedQaDevice(cliValues: List<String>): QaNamedDevice? =
  when (cliValues.size) {
    0 -> null
    1 ->
      QaNamedDevice.fromWireValue(cliValues.single())
        ?: throw UsageError(
          "Invalid $QA_DEVICE_FLAG value '${cliValues.single()}'. Use serial:<serial> or avd:<name>.",
        )
    else -> throw UsageError("Pass $QA_DEVICE_FLAG at most once.")
  }
