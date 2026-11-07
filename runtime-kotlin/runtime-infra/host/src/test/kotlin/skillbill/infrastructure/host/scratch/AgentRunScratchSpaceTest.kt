package skillbill.infrastructure.host.scratch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.host.jvm.RuntimeTempEnvironmentKeys
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

private val NOW: Instant = Instant.parse("2026-11-05T00:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

class AgentRunScratchSpaceTest {
  @Test
  fun `a new run sweeps scratch left by a dead owner and keeps a live owner's`(
    @TempDir root: Path,
  ) {
    val abandoned = Files.createDirectories(root.resolve("${AgentRunScratchSpace.DIRECTORY_PREFIX}abandoned"))
    abandoned.resolve(AgentRunScratchSpace.OWNER_FILE).writeText("101")
    val live = Files.createDirectories(root.resolve("${AgentRunScratchSpace.DIRECTORY_PREFIX}live"))
    live.resolve(AgentRunScratchSpace.OWNER_FILE).writeText("202")
    val space = AgentRunScratchSpace(root, FIXED_CLOCK, ownerPid = 1, isProcessAlive = { pid -> pid == 202L })

    space.withScratch { }

    assertFalse(Files.exists(abandoned))
    assertTrue(Files.exists(live))
  }

  @Test
  fun `the JVM temp root loses entries older than seven days and keeps newer ones`(
    @TempDir root: Path,
  ) {
    val jvmRoot = Files.createDirectories(root.resolve(AgentRunScratchSpace.JVM_DIRECTORY))
    val stale = Files.createDirectories(jvmRoot.resolve("stale"))
    stale.resolve("leftover").writeText("x")
    Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofDays(7)).minusSeconds(1)))
    val recent = Files.createFile(jvmRoot.resolve("recent"))
    Files.setLastModifiedTime(recent, FileTime.from(NOW.minus(Duration.ofDays(7)).plusSeconds(1)))

    AgentRunScratchSpace(root, FIXED_CLOCK).withScratch { }

    assertFalse(Files.exists(stale))
    assertTrue(Files.exists(recent))
  }

  @Test
  fun `a dangling link in the JVM temp root does not stop the sweep removing stale entries`(
    @TempDir root: Path,
  ) {
    val jvmRoot = Files.createDirectories(root.resolve(AgentRunScratchSpace.JVM_DIRECTORY))
    Files.createSymbolicLink(jvmRoot.resolve("dangling"), jvmRoot.resolve("gone"))
    val stale = Files.createFile(jvmRoot.resolve("stale"))
    Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofDays(8))))

    AgentRunScratchSpace(root, FIXED_CLOCK).withScratch { }

    assertFalse(Files.exists(stale))
  }

  @Test
  fun `a JVM temp root that cannot be prepared does not cost the run its scratch directory`(
    @TempDir root: Path,
  ) {
    Files.createFile(root.resolve(AgentRunScratchSpace.JVM_DIRECTORY))

    val directory = AgentRunScratchSpace(root, FIXED_CLOCK).withScratch { it }

    assertTrue(directory != null)
  }

  @Test
  fun `the environment replaces a temp root option and keeps every other option in order`(
    @TempDir root: Path,
  ) {
    val directory = root.resolve("run-1")
    val inherited =
      "-javaagent:/a.jar -Djava.io.tmpdir=/tmp \"-Djava.io.tmpdir=/a b\" -Djava.io.tmpdir=\"/c d\" " +
        "-Dother=\"x y\" -Xss1m"

    val environment =
      AgentRunScratchSpace(root, FIXED_CLOCK)
        .environment(directory, mapOf(RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS to inherited))

    assertEquals(
      "-javaagent:/a.jar -Dother=\"x y\" -Xss1m -Djava.io.tmpdir=${root.resolve("jvm")}",
      environment[RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS],
    )
    assertEquals(directory.toString(), environment[RuntimeTempEnvironmentKeys.SKILL_BILL_RUN_SCRATCH_DIR])
    assertEquals(directory.toString(), environment["TMPDIR"])
  }

  @Test
  fun `a state directory containing whitespace is quoted so the JVM can start`() {
    val root = Path.of("/state dir/scratch")

    val environment = AgentRunScratchSpace(root, FIXED_CLOCK).environment(root.resolve("run-1"), emptyMap())

    assertEquals(
      "-Djava.io.tmpdir=\"/state dir/scratch/jvm\"",
      environment[RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS],
    )
  }

  @Test
  fun `a state directory containing a quote character is quoted so the JVM can start`() {
    val apostrophe = Path.of("/o'brien/scratch")
    val doubleQuote = Path.of("/a\"b/scratch")

    assertEquals(
      "-Djava.io.tmpdir=\"/o'brien/scratch/jvm\"",
      AgentRunScratchSpace(apostrophe, FIXED_CLOCK)
        .environment(apostrophe.resolve("run-1"), emptyMap())[RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS],
    )
    assertEquals(
      "-Djava.io.tmpdir=\"/a\"'\"'\"b/scratch/jvm\"",
      AgentRunScratchSpace(doubleQuote, FIXED_CLOCK)
        .environment(doubleQuote.resolve("run-1"), emptyMap())[RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS],
    )
  }

  @Test
  fun `no scratch directory adds nothing to the environment`(
    @TempDir root: Path,
  ) {
    val environment =
      AgentRunScratchSpace(root, FIXED_CLOCK)
        .environment(null, mapOf(RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS to "-Xss1m"))

    assertEquals(emptyMap<String, String>(), environment)
  }
}
