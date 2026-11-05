package skillbill.infrastructure.launcher.process.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.AgentRunScratchSpace
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

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
    assertEquals(listOf(root.resolve(AgentRunScratchSpace.JVM_DIRECTORY)), root.listDirectoryEntries())
  }
}
