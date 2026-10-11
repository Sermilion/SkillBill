package dev.skillbill.tray.infrastructure.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration

class ProcessRunnerTest {
  @Test
  fun `timeout leaves the child draining and holding its slot until natural exit`() = runBlocking {
    assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
    val temp = Files.createTempDirectory("skillbill-runner")
    val script = temp.resolve("slow")
    Files.writeString(script, "#!/bin/sh\nsleep 0.4\nyes x | head -c 5000\necho private >&2\n")
    script.toFile().setExecutable(true)
    val runner = ProcessRunner(maxStdoutBytes = 1024, timeout = Duration.ofMillis(100))
    assertEquals(ProcessResult.Timeout, runner.run(listOf(script.toString())))
    assertEquals(ProcessResult.Busy, runner.run(listOf(script.toString())))
    delay(600)
    assertEquals(ProcessResult.OutputLimit, runner.run(listOf("/bin/sh", "-c", "yes x | head -c 5000")))
  }

  @Test
  fun `deadline covers streams that remain open after process exit`() = runBlocking {
    val release = CountDownLatch(1)
    val process = object : Process() {
      override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
          release.await(2, TimeUnit.SECONDS)
          return -1
        }
      }
      override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
      override fun getOutputStream() = ByteArrayOutputStream()
      override fun waitFor() = 0
      override fun waitFor(timeout: Long, unit: TimeUnit) = true
      override fun exitValue() = 0
      override fun destroy() { error("The runner must not destroy a child.") }
    }
    val runner = ProcessRunner(timeout = Duration.ofMillis(100))
    try {
      assertEquals(ProcessResult.Timeout, runner.run(listOf("unused"), processGate = { process }))
      assertEquals(ProcessResult.Busy, runner.run(listOf("unused"), processGate = { process }))
    } finally {
      release.countDown()
    }
  }
}
