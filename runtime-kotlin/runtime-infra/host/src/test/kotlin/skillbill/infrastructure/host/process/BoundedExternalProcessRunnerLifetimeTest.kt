package skillbill.infrastructure.host.process

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedExternalProcessRunnerLifetimeTest {
  @Test
  fun `zero-exit parent that leaves a descendant running publishes that zero and reaps the child`() {
    val root = Files.createTempDirectory("skill-bill-bounded-process-descendant-")
    val childPidFile = root.resolve("child.pid")
    try {
      val result =
        BoundedExternalProcessRunner.run(
          BoundedExternalProcessRequest(
            argv =
              listOf(
                "sh",
                "-c",
                "sleep 120 & echo \$! > '$childPidFile'; sleep 0.4; exit 0",
              ),
            deadlineSeconds = 5,
          ),
        )
      assertEquals(0, result.exitCode, result.output)
      assertEquals(null, result.readFailure)
      assertTrue(Files.exists(childPidFile) && Files.size(childPidFile) > 0L, "expected descendant pid file")
      assertTrue(awaitDead(processHandleFrom(childPidFile)))
    } finally {
      destroyProcessFrom(childPidFile)
      root.toFile().deleteRecursively()
    }
  }

  private fun processHandleFrom(pidFile: Path): ProcessHandle? =
    ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null)

  private fun destroyProcessFrom(pidFile: Path) {
    if (Files.exists(pidFile) && Files.size(pidFile) > 0L) {
      processHandleFrom(pidFile)?.destroyForcibly()
    }
  }

  private fun awaitDead(handle: ProcessHandle?): Boolean {
    if (handle == null) return true
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
    while (handle.isAlive && System.nanoTime() < deadline) {
      Thread.sleep(20)
    }
    return !handle.isAlive
  }
}
