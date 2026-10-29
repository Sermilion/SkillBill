package skillbill.cli

import skillbill.cli.core.CliRuntime
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliGoalFreshQualityRuntimeTest {
  @Test
  fun `fresh-quality clears an ambiguous historical quality block for the next goal run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val recoveredDb = fixture.tempDir.resolve("recovered.db")
    val launcher = GoalFixtureAgentRunLauncher(fixture, dbPath = recoveredDb)
    val blocked = CliRuntime.run(fixture.goalCommand(dbPath = recoveredDb), fixture.context(launcher = launcher))
    assertContains(blocked.stdout, "ambiguous historical quality state")

    val chosen = CliRuntime.run(freshQuality(fixture, recoveredDb), fixture.context(launcher = launcher))
    val repeated = CliRuntime.run(freshQuality(fixture, recoveredDb), fixture.context(launcher = launcher))
    val resumed = CliRuntime.run(fixture.goalCommand(dbPath = recoveredDb), fixture.context(launcher = launcher))

    assertEquals(0, chosen.exitCode, chosen.stdout)
    assertEquals(1, repeated.exitCode, repeated.stdout)
    assertContains(repeated.stdout, "has no ambiguous historical quality state")
    assertFalse(resumed.stdout.contains("ambiguous historical quality state"), resumed.stdout)
  }

  private fun freshQuality(
    fixture: GoalCliFixture,
    dbPath: Path,
  ): List<String> =
    listOf(
      "--db",
      dbPath.toString(),
      "goal",
      "fresh-quality",
      "SKILL-901",
      "--reason",
      "the original quality evidence is gone",
      "--repo-root",
      fixture.tempDir.toString(),
    )
}
