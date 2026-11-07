package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidQaDeviceParsingTest {
  @Test
  fun `device listing skips chatter and keeps unknown states and model fallback inputs`() {
    val devices = AndroidQaDeviceParsing.devices(
      """
      List of devices attached

      * daemon not running; starting now at tcp:5037
      emulator-5554 device product:api model:Pixel_8 device:emu
      192.168.1.4:5555 unauthorized
      adb-ABC._adb-tls-connect._tcp offline
      usb-1 mystery
      """.trimIndent(),
    )

    assertEquals(
      listOf(
        ParsedAdbDevice("emulator-5554", "device", "Pixel_8"),
        ParsedAdbDevice("192.168.1.4:5555", "unauthorized", null),
        ParsedAdbDevice("adb-ABC._adb-tls-connect._tcp", "offline", null),
        ParsedAdbDevice("usb-1", "mystery", null),
      ),
      devices,
    )
    assertEquals(QaDeviceTransport.Emulator, AndroidQaDeviceParsing.transport(devices[0].serial))
    assertEquals(QaDeviceTransport.Wifi, AndroidQaDeviceParsing.transport(devices[1].serial))
    assertEquals(QaDeviceTransport.Wifi, AndroidQaDeviceParsing.transport(devices[2].serial))
    assertEquals(QaDeviceTransport.Usb, AndroidQaDeviceParsing.transport(devices[3].serial))
  }

  @Test
  fun `AVD listing ignores diagnostic lines and separators`() {
    assertEquals(
      listOf("Pixel_8_API_35", "Tablet_API_35"),
      AndroidQaDeviceParsing.avds("Pixel_8_API_35\nINFO | startup\nWARNING waiting\nERROR missing\n| separator\nTablet_API_35\n"),
    )
  }
}
