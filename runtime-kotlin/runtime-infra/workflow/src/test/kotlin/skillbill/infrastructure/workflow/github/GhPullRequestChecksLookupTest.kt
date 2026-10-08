package skillbill.infrastructure.workflow.github

import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GhPullRequestChecksLookupTest {
  private val repoRoot = Path.of("/tmp/skillbill-pr-checks")

  @Test
  fun `failing checks reported with a non-zero exit are read as failed checks with names and links`() {
    val calls = mutableListOf<List<String>>()
    val lookup =
      GhPullRequestChecksLookup { _, args ->
        calls += args
        GhCommandResult(
          exitCode = 1,
          stdout =
            """
            [
              {"name":"build","state":"FAILURE","bucket":"fail","link":"https://github.com/acme/repo/actions/runs/1"},
              {"name":"lint","state":"SUCCESS","bucket":"pass","link":"https://github.com/acme/repo/actions/runs/2"}
            ]
            """.trimIndent(),
        )
      }

    val checks = lookup.lookup(repoRoot, 42)

    assertEquals(
      PullRequestChecks.Reported(
        listOf(
          PullRequestCheck("build", CheckBucket.FAIL, "https://github.com/acme/repo/actions/runs/1"),
          PullRequestCheck("lint", CheckBucket.PASS, "https://github.com/acme/repo/actions/runs/2"),
        ),
      ),
      checks,
    )
    assertEquals(listOf("pr", "checks", "42", "--json", "name,state,bucket,link"), calls.single())
  }

  @Test
  fun `an auth error with non-JSON output is unavailable rather than passing`() {
    val lookup =
      GhPullRequestChecksLookup { _, _ ->
        GhCommandResult(exitCode = 4, stdout = "To get started with GitHub CLI, please run:  gh auth login")
      }

    val checks = lookup.lookup(repoRoot, 42)

    assertEquals(
      PullRequestChecks.Unavailable("To get started with GitHub CLI, please run:  gh auth login"),
      checks,
    )
  }

  @Test
  fun `an unknown json flag from an older gh is unavailable with an upgrade reason`() {
    val lookup =
      GhPullRequestChecksLookup { _, _ ->
        GhCommandResult(exitCode = 1, stdout = "unknown flag: --json\n")
      }

    assertEquals(
      PullRequestChecks.Unavailable("gh pr checks --json unsupported; upgrade gh"),
      lookup.lookup(repoRoot, 42),
    )
  }

  @Test
  fun `an empty check list means the pull request has no checks registered`() {
    val lookup = GhPullRequestChecksLookup { _, _ -> GhCommandResult(exitCode = 0, stdout = "[]\n") }

    assertEquals(PullRequestChecks.NoChecks, lookup.lookup(repoRoot, 42))
  }

  @Test
  fun `the no checks reported message is read as no checks`() {
    val lookup =
      GhPullRequestChecksLookup { _, _ ->
        GhCommandResult(exitCode = 1, stdout = "no checks reported on the 'feat/SKILL-407' branch\n")
      }

    assertEquals(PullRequestChecks.NoChecks, lookup.lookup(repoRoot, 42))
  }

  @Test
  fun `a check with an unrecognised bucket is unavailable rather than passing`() {
    val lookup =
      GhPullRequestChecksLookup { _, _ ->
        GhCommandResult(
          exitCode = 0,
          stdout = """[{"name":"build","state":"SUCCESS","bucket":"success","link":""}]""",
        )
      }

    assertEquals(
      PullRequestChecks.Unavailable("GitHub CLI returned a check with an unrecognised bucket or name."),
      lookup.lookup(repoRoot, 42),
    )
  }
}
