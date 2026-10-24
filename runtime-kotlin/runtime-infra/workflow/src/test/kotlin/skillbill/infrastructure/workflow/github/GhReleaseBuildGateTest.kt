package skillbill.infrastructure.workflow.github

import skillbill.ports.operation.model.ReleaseBuildReadiness
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GhReleaseBuildGateTest {
  private val repo = Path.of("/tmp/release-build-gate")
  private val sha = "1234567890abcdef1234567890abcdef12345678"

  @Test
  fun `only successful push builds for the exact default branch commit pass`() {
    val calls = mutableListOf<List<String>>()
    val gate =
      GhReleaseBuildGate { root, args ->
        assertEquals(repo, root)
        calls += args
        GhCommandResult(0, if (args.first() == "repo") repository else "[${run()}]")
      }

    assertEquals(ReleaseBuildReadiness.Passed("main"), gate.inspect(repo, sha))
    assertEquals(
      listOf(
        "run", "list", "--branch", "main", "--commit", sha, "--event", "push", "--limit", "100",
        "--json", "databaseId,workflowDatabaseId,workflowName,status,conclusion,url,headBranch,headSha",
      ),
      calls.last(),
    )
  }

  @Test
  fun `missing pending failed cancelled skipped and unknown results never allow release`() {
    val results =
      listOf(
        "[]",
        "[${run(status = "queued", conclusion = "")} ]",
        "[${run(status = "in_progress", conclusion = "")} ]",
        "[${run(conclusion = "failure")} ]",
        "[${run(conclusion = "cancelled")} ]",
        "[${run(conclusion = "skipped")} ]",
        "[${run(conclusion = "future_conclusion")} ]",
      )
    for (result in results) {
      assertIs<ReleaseBuildReadiness.NotReady>(gate(result).inspect(repo, sha), result)
    }
    val failed = assertIs<ReleaseBuildReadiness.NotReady>(gate("[${run(conclusion = "failure")}]").inspect(repo, sha))
    assertContains(failed.reason, "https://github.com/acme/repo/actions/runs/2")
  }

  @Test
  fun `a later workflow rerun supersedes its own old failure but cannot hide another workflow failure`() {
    val old = run(id = 1, conclusion = "failure")
    val successful = run(id = 2)
    assertIs<ReleaseBuildReadiness.Passed>(gate("[$successful,$old]").inspect(repo, sha))

    val failing = run(id = 3, workflow = 11, conclusion = "failure")
    assertIs<ReleaseBuildReadiness.NotReady>(gate("[$successful,$failing,$old]").inspect(repo, sha))
    val pending = run(id = 4, status = "queued", conclusion = "")
    assertIs<ReleaseBuildReadiness.NotReady>(gate("[$successful,$pending]").inspect(repo, sha))
  }

  @Test
  fun `malformed truncated foreign commit and foreign branch evidence is unavailable`() {
    for (result in listOf(
      "not json",
      "{}",
      "[{}]",
      "[${run().replace(sha, "another-commit")} ]",
      "[${run().replace("main", "feature")} ]",
      List(100) { run(id = it + 1) }.joinToString(",", "[", "]"),
    )) {
      assertIs<ReleaseBuildReadiness.Unavailable>(gate(result).inspect(repo, sha), result)
    }
  }

  @Test
  fun `provider errors do not become an empty or passing build report`() {
    for (failAt in listOf("repo", "run")) {
      val gate =
        GhReleaseBuildGate { _, args ->
          if (args.first() == failAt) GhCommandResult(1, "authentication failed") else GhCommandResult(0, repository)
        }
      val result = assertIs<ReleaseBuildReadiness.Unavailable>(gate.inspect(repo, sha))
      assertContains(result.reason, "authentication failed")
    }
    val gate = GhReleaseBuildGate { _, _ -> GhCommandResult(0, "{}") }
    assertIs<ReleaseBuildReadiness.Unavailable>(gate.inspect(repo, sha))
  }

  private fun gate(runs: String): GhReleaseBuildGate =
    GhReleaseBuildGate { _, args ->
      GhCommandResult(0, if (args.first() == "repo") repository else runs)
    }

  private fun run(
    id: Int = 2,
    workflow: Int = 10,
    status: String = "completed",
    conclusion: String = "success",
  ): String =
    """
    {"databaseId":$id,"workflowDatabaseId":$workflow,"workflowName":"Build",
     "status":"$status","conclusion":"$conclusion",
     "url":"https://github.com/acme/repo/actions/runs/$id","headBranch":"main","headSha":"$sha"}
    """.trimIndent()

  private val repository = """{"defaultBranchRef":{"name":"main"}}"""
}
