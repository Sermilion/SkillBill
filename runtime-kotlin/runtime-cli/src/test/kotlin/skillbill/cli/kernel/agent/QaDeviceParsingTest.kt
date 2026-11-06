package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QaDeviceParsingTest {
  @Test
  fun `device parsing accepts zero or one valid value and rejects malformed or duplicate values`() {
    assertNull(resolveRequestedQaDevice(emptyList()))
    assertEquals("serial:R58M123", resolveRequestedQaDevice(listOf("serial:R58M123"))?.wireValue)

    listOf(listOf("usb:x"), listOf("serial:R58M123", "avd:Pixel_8_API_35")).forEach { values ->
      val error = assertFailsWith<UsageError> { resolveRequestedQaDevice(values) }
      assertContains(error.message.orEmpty(), "--qa-device")
    }
  }
}
