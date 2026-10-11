package skillbill.infrastructure.workflow.github

import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GhGoalPullRequestPortTest {
  private val url = "https://github.com/acme/repo/pull/463"
  private val request =
    GoalPullRequestRequest(Path.of("/tmp/repo"), "SKILL-417", "Status", "main", "feat/SKILL-417", "Status", "Body")

  @Test
  fun `a merged pull request is reused even after the remote branch is deleted`() {
    val port =
      GhGoalPullRequestPort(
        gh = { _, _ -> error("A merged pull request must not be recreated.") },
        identityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Merged(url, 463) },
      )

    assertEquals(GoalPullRequestResult.Existing(url), port.open(request))
  }

  @Test
  fun `a failed identity lookup blocks publication instead of attempting creation`() {
    val port =
      GhGoalPullRequestPort(
        gh = { _, _ -> error("An unavailable lookup does not prove the pull request is absent.") },
        identityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Unavailable("Not authenticated") },
      )

    assertEquals(GoalPullRequestResult.Failed("Not authenticated"), port.open(request))
  }

  @Test
  fun `an absent pull request is created`() {
    val port =
      GhGoalPullRequestPort(
        gh = { _, args ->
          assertEquals("create", args[1])
          GhCommandResult(0, url)
        },
        identityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
      )

    assertEquals(GoalPullRequestResult.Opened(url), port.open(request))
  }
}
