package skillbill.cli.goal.import

import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliGoalImportCommandTest {
  @Test
  fun `head mismatch refused import exits one and maps refusal payload`() {
    val result =
      GoalRunnerImportResult.Refused(
        issueKey = "SKILL-901",
        reason =
          "Current branch 'main' is not the recorded feature branch 'feat/SKILL-901-goal' " +
            "at commit 'abc123'; import refused.",
        featureBranch = "feat/SKILL-901-goal",
        commitSha = "abc123",
      )
    val payload = result.toGoalImportCliMap()
    assertEquals(1, goalImportExitCode(result))
    assertEquals(result.reason, payload[GoalRunnerImportPayloadKeys.REFUSAL_REASON])
    assertContains(goalImportText(result), "feat/SKILL-901-goal")
    assertContains(goalImportText(result), "abc123")
  }
}
