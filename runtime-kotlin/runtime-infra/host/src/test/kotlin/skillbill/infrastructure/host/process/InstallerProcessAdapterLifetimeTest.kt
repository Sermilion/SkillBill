package skillbill.infrastructure.host.process

import skillbill.ports.process.DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS
import skillbill.ports.process.model.InstallerProcessRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

  @Test
  fun `deadline expiry settles with timeout exit code and no live owned child`() {
    val pidFile = Files.createTempFile("skillbill-installer-timeout", ".pid")
    pidFile.toFile().deleteOnExit()
    val result =
      adapter.run(
        InstallerProcessRequest(
          executable = "/bin/bash",
          arguments = listOf("-c", "echo $$ > '$pidFile'; exec sleep 120"),
          environment = shellEnvironment(),
          deadlineSeconds = 2L,
        ),
      )
    assertTrue(result.timedOut)
    assertEquals(124, result.exitCode)
    if (Files.exists(pidFile)) {
      val child = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null)
      if (child != null) {
        assertFalse(awaitDead(child), "owned child still alive after timeout")
      }
    }
  }

  @Test
  fun `interruption destroys owned child and leaves sibling alive`() {
    val sibling = ProcessBuilder("sleep", "60").start()
    val siblingPid = sibling.pid()
    val ownedPidFile = Files.createTempFile("skillbill-installer-owned", ".pid")
    ownedPidFile.toFile().deleteOnExit()
    val failure = AtomicReference<Throwable?>()
    val finished = CountDownLatch(1)
    val worker =
      thread(start = true, isDaemon = true, name = "skillbill-installer-interrupt") {
        try {
          adapter.run(
            InstallerProcessRequest(
              executable = "/bin/bash",
              arguments = listOf("-c", "echo $$ > '$ownedPidFile'; exec 1>&- 2>&-; exec sleep 120"),
              environment = shellEnvironment(),
              deadlineSeconds = DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS,
            ),
          )
        } catch (error: Throwable) {
          failure.set(error)
        } finally {
          finished.countDown()
        }
      }
    awaitOwnedPid(ownedPidFile)
    Thread.sleep(100)
    worker.interrupt()
    finished.await(10, TimeUnit.SECONDS)
    assertTrue(failure.get() is InterruptedException, "expected interrupt, got ${failure.get()}")
    val owned = ProcessHandle.of(Files.readString(ownedPidFile).trim().toLong()).orElse(null)
    if (owned != null) {
      assertFalse(awaitDead(owned, seconds = 8), "owned installer child still alive")
    }
    val siblingHandle = ProcessHandle.of(siblingPid).orElseThrow()
    assertTrue(siblingHandle.isAlive, "unrelated sibling process was destroyed")
    sibling.destroyForcibly()
    sibling.waitFor(2, TimeUnit.SECONDS)
  }

  private fun shellEnvironment(): Map<String, String> =
    mapOf("PATH" to "/usr/bin:/bin", "HOME" to System.getProperty("user.home").orEmpty())

  private fun awaitOwnedPid(pidFile: Path) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while ((!Files.exists(pidFile) || Files.size(pidFile) == 0L) && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
  }

  private fun awaitDead(
    handle: ProcessHandle,
    seconds: Long = 5,
  ): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (handle.isAlive && System.nanoTime() < deadline) {
      Thread.sleep(50)
    }
    return !handle.isAlive
  }
}
