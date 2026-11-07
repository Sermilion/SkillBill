package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.qa.QaDeviceState
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidQaDeviceClassifierTest {
  @Test
  fun `authorization and transport state take precedence over lease occupancy`() {
    assertEquals(QaDeviceState.Unauthorized, AndroidQaDeviceClassifier.state("unauthorized", true))
    assertEquals(QaDeviceState.Offline, AndroidQaDeviceClassifier.state("offline", true))
    assertEquals(QaDeviceState.Unsupported, AndroidQaDeviceClassifier.state("unknown", true))
    assertEquals(QaDeviceState.Leased, AndroidQaDeviceClassifier.state("device", true))
    assertEquals(QaDeviceState.Ready, AndroidQaDeviceClassifier.state("device", false))
  }
}
