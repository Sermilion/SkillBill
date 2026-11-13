package skillbill.infrastructure.host.jvm

import kotlin.test.Test
import kotlin.test.assertEquals

class JdkHostPlatformPortTest {
  @Test
  fun `resolves the current process user name when available`() {
    val expected = ProcessHandle.current().info().user().orElse(null)?.takeIf(String::isNotBlank)

    assertEquals(expected, JdkHostPlatformPort.resolveUserName())
  }
}
