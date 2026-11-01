package skillbill.infrastructure.workflow.github

import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhGoalPullRequestPortTest {
  private val url = "https://github.com/acme/repo/pull/463"
  private val request =
    GoalPullRequestRequest(Path.of("/tmp/repo"), "SKILL-417", "Status", "main", "feat/SKILL-417", "Status", "Body")

  private val stackedRequest = request.copy(baseBranch = "feat/SKILL-416")

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
  fun `an absent pull request is created against a base that still exists`() {
    val create = createAfterLookup(request, GhCommandResult(0, lookupJson(ref = "{\"name\":\"main\"}")))

    assertEquals("main", create.valueAfter("--base"))
    assertEquals("Body", create.valueAfter("--body"))
  }

  @Test
  fun `a deleted stacked base falls back to the default branch and says so in the body`() {
    val create = createAfterLookup(stackedRequest, GhCommandResult(0, lookupJson(ref = "null")))

    assertEquals("main", create.valueAfter("--base"))
    val body = create.valueAfter("--body")
    assertTrue(body.startsWith("> Recorded base branch `feat/SKILL-416` no longer exists"), body)
    assertTrue(body.endsWith("Body"), body)
  }

  @Test
  fun `a failed base lookup keeps the recorded base`() {
    val create = createAfterLookup(stackedRequest, GhCommandResult(1, "HTTP 502"))

    assertEquals("feat/SKILL-416", create.valueAfter("--base"))
    assertEquals("Body", create.valueAfter("--body"))
  }

  private fun createAfterLookup(
    request: GoalPullRequestRequest,
    lookup: GhCommandResult,
  ): List<String> {
    var create: List<String>? = null
    val port =
      GhGoalPullRequestPort(
        gh = { _, args ->
          if (args.first() == "api") {
            lookup
          } else {
            create = args
            GhCommandResult(0, url)
          }
        },
        identityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
      )

    assertEquals(GoalPullRequestResult.Opened(url), port.open(request))
    return checkNotNull(create)
  }

  private fun lookupJson(ref: String): String =
    "{\"data\":{\"repository\":{\"ref\":$ref,\"defaultBranchRef\":{\"name\":\"main\"}}}}"

  private fun List<String>.valueAfter(flag: String): String = this[indexOf(flag) + 1]
}
