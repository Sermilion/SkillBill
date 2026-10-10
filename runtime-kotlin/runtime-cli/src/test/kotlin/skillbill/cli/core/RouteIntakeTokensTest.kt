package skillbill.cli.core

import skillbill.cli.isolatedCliEnvironment
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class RouteIntakeTokensTest {
  private val isCommand: (String) -> Boolean = { token -> token in setOf("goal", "phase", "operation") }

  @Test
  fun `an issue-key first token prepends goal`() {
    assertEquals(
      listOf("goal", "APP-123", "Add CSV export"),
      routeIntakeTokens(listOf("APP-123", "Add CSV export"), isCommand),
    )
  }

  @Test
  fun `a phase colon form splits into phase and name`() {
    assertEquals(listOf("phase", "review"), routeIntakeTokens(listOf("phase:review"), isCommand))
  }

  @Test
  fun `phse review is an unknown command and does not reach goal intake`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-route-typo")
    try {
      val result =
        CliRuntime.run(
          listOf("--db", tempDir.resolve("metrics.db").toString(), "phse", "review"),
          CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir)),
        )
      assertNotEquals(0, result.exitCode, result.stdout + result.stderr)
      assertContains(result.stderr, "phse")
      assertFalse(result.stderr.contains("To start new work"), result.stderr)
      assertFalse(result.stderr.contains("tracker issue key"), result.stderr)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `phase verify names the verify operation`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-phase-verify")
    try {
      val db = tempDir.resolve("metrics.db").toString()
      val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
      val split = CliRuntime.run(listOf("--db", db, "phase", "verify", "APP-1"), context)
      val colon = CliRuntime.run(listOf("--db", db, "phase:verify", "APP-1"), context)
      assertNotEquals(0, split.exitCode, split.stdout + split.stderr)
      assertNotEquals(0, colon.exitCode, colon.stdout + colon.stderr)
      assertContains(split.stderr, "skill-bill operation verify")
      assertContains(colon.stderr, "skill-bill operation verify")
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }
}
