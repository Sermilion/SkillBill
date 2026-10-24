package skillbill.infrastructure.host.process

import skillbill.ports.process.model.InstallerProcessRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerProcessAdapterLifetimeTest {
  private val adapter = InstallerProcessAdapter()

  @Test
  fun `stdin is closed so a child waiting for input exits`() {
    val result =
      adapter.run(
        InstallerProcessRequest(
          executable = "/bin/bash",
          arguments = listOf("-c", "read -r line; echo read:\$line"),
          environment = shellEnvironment(),
          deadlineSeconds = 5L,
        ),
      )
    assertEquals(0, result.exitCode, result.output)
    assertContains(result.output, "read:")
  }

  @Test
  fun `output beyond cap includes truncation sentinel`() {
    val bytes = INSTALLER_PROCESS_OUTPUT_CAP_BYTES + 1024
    val result =
      adapter.run(
        InstallerProcessRequest(
          executable = "/bin/bash",
          arguments = listOf("-c", "head -c $bytes /dev/zero"),
          environment = shellEnvironment(),
          deadlineSeconds = 30L,
        ),
      )
    assertEquals(0, result.exitCode, result.output.take(200))
    assertFalse(result.timedOut)
    assertContains(result.output, INSTALLER_OUTPUT_TRUNCATION_SENTINEL)
  }

  @Test
  fun `launch failure settles as a nonzero launch result`() {
    val result =
      adapter.run(
        InstallerProcessRequest(
          executable = "/path/that/does/not/exist",
          arguments = emptyList(),
          environment = shellEnvironment(),
          deadlineSeconds = 5L,
        ),
      )
    assertEquals(1, result.exitCode)
    assertTrue(result.launchFailure)
    assertContains(result.output, "Failed to launch installer")
  }

  private fun shellEnvironment(): Map<String, String> =
    mapOf("PATH" to "/usr/bin:/bin", "HOME" to System.getProperty("user.home").orEmpty())
}
