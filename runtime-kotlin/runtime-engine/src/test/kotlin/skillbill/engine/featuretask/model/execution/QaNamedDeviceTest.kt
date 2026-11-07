package skillbill.engine.featuretask.model.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QaNamedDeviceTest {
  @Test
  fun `serial and avd wire values retain their kind`() {
    listOf(
      QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M123"),
      QaNamedDevice(QaNamedDeviceKind.AVD, "Pixel_8_API_35"),
    ).forEach { device ->
      assertEquals(device, QaNamedDevice.fromWireValue(device.wireValue))
    }
  }

  @Test
  fun `invalid wire values cannot become named devices`() {
    listOf(
      "R58M123",
      "usb:x",
      "serial:",
      "serial: bad value",
      "serial:line\nbreak",
      "serial:${"x".repeat(257)}",
      "serial:-x",
    ).forEach { raw -> assertNull(QaNamedDevice.fromWireValue(raw), raw) }
  }

  @Test
  fun `launch arguments stay empty without a device and append one flag pair otherwise`() {
    val device = QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M123")

    assertEquals(emptyList(), null.launchArguments())
    assertEquals(listOf("--qa-device", "serial:R58M123"), device.launchArguments())
  }
}
