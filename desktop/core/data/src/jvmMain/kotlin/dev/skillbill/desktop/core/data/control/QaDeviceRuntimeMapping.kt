package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind

internal fun QaDevicePick.toRuntimeDevice(): QaNamedDevice = QaNamedDevice(
  if (kind == QaDevicePickKind.Serial) QaNamedDeviceKind.SERIAL else QaNamedDeviceKind.AVD,
  value,
)
