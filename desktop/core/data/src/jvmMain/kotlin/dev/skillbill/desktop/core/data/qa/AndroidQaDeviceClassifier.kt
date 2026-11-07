package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.qa.QaDeviceState

internal object AndroidQaDeviceClassifier {
  fun adbState(value: String): QaDeviceState = when (value) {
    "device" -> QaDeviceState.Ready
    "unauthorized" -> QaDeviceState.Unauthorized
    "offline" -> QaDeviceState.Offline
    else -> QaDeviceState.Unsupported
  }

  fun state(value: String, leased: Boolean): QaDeviceState = when (val state = adbState(value)) {
    QaDeviceState.Ready -> if (leased) QaDeviceState.Leased else state
    else -> state
  }
}
