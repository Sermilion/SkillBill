package skillbill.infrastructure.launcher.process.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class AgentRunScratchSpaceTest {
  @Test
  fun `a failed agent run still deletes everything it wrote to its TMPDIR`(
    @TempDir stateDir: Path,
  ) {
    val root = stateDir.resolve("scratch")
    val runner = JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot(stateDir))

    val result =
      runner.run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "mkdir \"\$TMPDIR/copy\" && touch \"\$TMPDIR/copy/file\" && echo \"\$TMPDIR\"; exit 3"),
          Path.of("."),
        ),
      )

    val scratch = Path.of(result.stdout.trim())
    assertEquals(3, result.exitStatus)
    assertEquals(root, scratch.parent)
    assertFalse(Files.exists(scratch))
    assertEquals(emptyList<Path>(), root.listDirectoryEntries())
  }

  @Test
  fun `a new run sweeps scratch left by a dead owner and keeps a live owner's`(
    @TempDir root: Path,
  ) {
    val abandoned = Files.createDirectories(root.resolve("${AgentRunScratchSpace.DIRECTORY_PREFIX}abandoned"))
    abandoned.resolve(AgentRunScratchSpace.OWNER_FILE).writeText("101")
    val live = Files.createDirectories(root.resolve("${AgentRunScratchSpace.DIRECTORY_PREFIX}live"))
    live.resolve(AgentRunScratchSpace.OWNER_FILE).writeText("202")
    val space = AgentRunScratchSpace(root, ownerPid = 1, isProcessAlive = { pid -> pid == 202L })

    space.withScratch { }

    assertFalse(Files.exists(abandoned))
    assertTrue(Files.exists(live))
  }
}
