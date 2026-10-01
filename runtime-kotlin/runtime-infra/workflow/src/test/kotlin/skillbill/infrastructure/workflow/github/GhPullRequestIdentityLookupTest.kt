package skillbill.infrastructure.workflow.github

import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GhPullRequestIdentityLookupTest {
  private val repoRoot = Path.of("/tmp/skillbill-pr-identity")

  @Test
  fun `the first listed pull request for the branch is found with its url, number, and title`() {
    val calls = mutableListOf<List<String>>()
    val lookup =
      GhPullRequestIdentityLookup { _, args ->
        calls += args
        GhCommandResult(
          exitCode = 0,
          stdout = """[{"number":42,"url":"https://github.com/acme/repo/pull/42","title":"[SKILL-380] Rules"}]""",
        )
      }

    val identity = lookup.lookup(repoRoot, "feat/SKILL-380")

    assertEquals(PullRequestIdentity.Found("https://github.com/acme/repo/pull/42", 42, "[SKILL-380] Rules"), identity)
    assertEquals(
      listOf("pr", "list", "--head", "feat/SKILL-380", "--json", "url,number,title", "--limit", "1"),
      calls.single(),
    )
  }

  @Test
  fun `an empty pull request list means the branch has no pull request`() {
    val lookup = GhPullRequestIdentityLookup { _, _ -> GhCommandResult(exitCode = 0, stdout = "[]\n") }

    assertEquals(PullRequestIdentity.Absent, lookup.lookup(repoRoot, "feat/SKILL-380"))
  }

  @Test
  fun `a failing gh command is unavailable with its redacted output as the reason`() {
    val lookup =
      GhPullRequestIdentityLookup { _, _ ->
        GhCommandResult(exitCode = 1, stdout = "could not reach https://token@github.com/acme/repo\n")
      }

    assertEquals(
      PullRequestIdentity.Unavailable("could not reach https://<redacted>@github.com/acme/repo"),
      lookup.lookup(repoRoot, "feat/SKILL-380"),
    )
  }

  @Test
  fun `output that is not a pull request list is unavailable rather than absent`() {
    val lookup = GhPullRequestIdentityLookup { _, _ -> GhCommandResult(exitCode = 0, stdout = "not json") }

    val identity = lookup.lookup(repoRoot, "feat/SKILL-380")

    assertEquals(
      PullRequestIdentity.Unavailable("GitHub CLI returned unparsable pull request output."),
      identity,
    )
  }
}
