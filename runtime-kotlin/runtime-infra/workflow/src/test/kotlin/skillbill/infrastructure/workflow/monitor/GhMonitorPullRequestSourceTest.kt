package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.core.JsonProcessingException
import skillbill.infrastructure.workflow.github.GhCommandResult
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val REPO = MonitorRepositoryIdentity.canonical("github.com", "acme", "repo")
private const val SHA = "0123456789abcdef0123456789abcdef01234567"

private const val HEAD_REF = "feat/SKILL-427"
private const val TITLE = "SKILL-427 monitor"

private fun pullRequestJson(
  number: Int,
  state: String = "open",
  headRepo: String? = "acme/repo",
  push: Boolean? = true,
  maintainerCanModify: Boolean? = false,
): String {
  val merged = state == "merged"
  val wireState = if (merged) "closed" else state
  val permissions = push?.let { ""","permissions":{"push":$it}""" }.orEmpty()
  val head =
    if (headRepo == null) {
      """{"ref":"$HEAD_REF","sha":"$SHA","repo":null}"""
    } else {
      val repo = """{"full_name":"$headRepo","clone_url":"https://github.com/$headRepo.git"$permissions}"""
      """{"ref":"$HEAD_REF","sha":"$SHA","repo":$repo}"""
    }
  val modify = maintainerCanModify?.let { ""","maintainer_can_modify":$it""" }.orEmpty()
  return """{"number":$number,"state":"$wireState","merged":$merged,"title":"$TITLE","body":null,"head":$head,""" +
    """"base":{"ref":"main","sha":"$SHA","repo":{"full_name":"acme/repo"}}$modify}"""
}

class GhMonitorPullRequestSourceTest {
  private val calls = mutableListOf<List<String>>()

  private fun source(
    exitCode: Int = 0,
    stdout: String,
    diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  ) = GhMonitorPullRequestSource(
    GhCommandRunner { _, args ->
      calls += args
      GhCommandResult(exitCode, stdout)
    },
    diagnostics,
  )

  private fun routedSource(vararg responses: Pair<String, GhCommandResult>) =
    GhMonitorPullRequestSource(
      GhCommandRunner { _, args ->
        calls += args
        responses.toMap().getValue(args.last())
      },
      NoopRuntimeDiagnostics,
    )

  private fun foundPullRequest(json: String) =
    assertIs<MonitorPullRequestLookup.Found>(source(stdout = json).pullRequest(REPO, 1)).pullRequest

  @Test
  fun `a pull request is read through the explicit repository endpoint with head, base and write evidence`() {
    val lookup = source(stdout = pullRequestJson(42)).pullRequest(REPO, 42)

    val found = assertIs<MonitorPullRequestLookup.Found>(lookup).pullRequest
    val expected = listOf("api", "--hostname", "github.com", "--method", "GET", "repos/acme/repo/pulls/42")
    assertEquals(expected, calls.single())
    assertEquals(MonitorPullRequestState.OPEN, found.state)
    assertEquals("feat/SKILL-427", found.head?.tip?.ref)
    assertEquals(true, found.head?.writeEvidence?.repositoryPush)
    assertEquals("main", found.base?.ref)
  }

  @Test
  fun `a fork head keeps its own repository and maintainer evidence`() {
    val json = pullRequestJson(7, headRepo = "Fork/Repo", push = false, maintainerCanModify = true)

    val found = assertIs<MonitorPullRequestLookup.Found>(source(stdout = json).pullRequest(REPO, 7)).pullRequest

    assertEquals(MonitorRepositoryIdentity.canonical("github.com", "fork", "repo"), found.head?.tip?.repository)
    assertEquals(false, found.head?.writeEvidence?.repositoryPush)
    assertEquals(true, found.head?.writeEvidence?.maintainerCanModify)
  }

  @Test
  fun `a deleted head repository yields no head`() {
    assertNull(foundPullRequest(pullRequestJson(1, headRepo = null)).head)
    assertEquals(1, calls.size)
  }

  @Test
  fun `push permission missing from the pull request is read from the head repository endpoint`() {
    val pullRequest = pullRequestJson(480, push = null)
    val lookup =
      routedSource(
        "repos/acme/repo/pulls/480" to GhCommandResult(0, pullRequest),
        "repos/acme/repo" to GhCommandResult(0, """{"full_name":"acme/repo","permissions":{"push":true}}""""),
      ).pullRequest(REPO, 480)

    val found = assertIs<MonitorPullRequestLookup.Found>(lookup).pullRequest
    assertEquals(true, found.head?.writeEvidence?.repositoryPush)
    assertEquals(listOf("api", "--hostname", "github.com", "--method", "GET", "repos/acme/repo"), calls.last())
  }

  @Test
  fun `a fork head reads push permission from the fork repository`() {
    val pullRequest = pullRequestJson(7, headRepo = "Fork/Repo", push = null, maintainerCanModify = false)
    val lookup =
      routedSource(
        "repos/acme/repo/pulls/7" to GhCommandResult(0, pullRequest),
        "repos/fork/repo" to GhCommandResult(0, """{"permissions":{"push":false}}""""),
      ).pullRequest(REPO, 7)

    assertEquals(
      false,
      assertIs<MonitorPullRequestLookup.Found>(lookup).pullRequest.head?.writeEvidence?.repositoryPush,
    )
  }

  @Test
  fun `a failed or silent head repository lookup leaves push evidence absent`() {
    val pullRequest = pullRequestJson(1, push = null, maintainerCanModify = null)
    listOf(
      GhCommandResult(1, "gh: boom (HTTP 500)"),
      GhCommandResult(0, """{"full_name":"acme/repo"}""""),
      GhCommandResult(0, "not json"),
    ).forEach { repositoryResponse ->
      val lookup =
        routedSource(
          "repos/acme/repo/pulls/1" to GhCommandResult(0, pullRequest),
          "repos/acme/repo" to repositoryResponse,
        ).pullRequest(REPO, 1)

      val evidence = assertIs<MonitorPullRequestLookup.Found>(lookup).pullRequest.head?.writeEvidence
      assertNull(evidence?.repositoryPush)
      assertNull(evidence?.maintainerCanModify)
    }
  }

  @Test
  fun `closed pull requests map to merged or closed from the merged flag`() {
    val merged = source(stdout = pullRequestJson(3, state = "merged")).pullRequest(REPO, 3)
    val closed = source(stdout = pullRequestJson(4, state = "closed")).pullRequest(REPO, 4)

    assertEquals(MonitorPullRequestState.MERGED, assertIs<MonitorPullRequestLookup.Found>(merged).pullRequest.state)
    assertEquals(MonitorPullRequestState.CLOSED, assertIs<MonitorPullRequestLookup.Found>(closed).pullRequest.state)
  }

  @Test
  fun `not found, provider failure, and unparsable output are distinct lookup results`() {
    assertEquals(MonitorPullRequestLookup.Missing, source(1, "gh: Not Found (HTTP 404)").pullRequest(REPO, 9))
    assertIs<MonitorPullRequestLookup.Failed>(source(1, "gh: boom (HTTP 500)").pullRequest(REPO, 9))
    assertIs<MonitorPullRequestLookup.Failed>(source(stdout = "not json").pullRequest(REPO, 9))
    assertIs<MonitorPullRequestLookup.Failed>(source(stdout = """{"number":9,"state":"open"}""").pullRequest(REPO, 9))
  }

  @Test
  fun `unparsable poll leaves a diagnostic`() {
    val diagnostics = RecordingDiagnostics()

    assertIs<MonitorPullRequestLookup.Failed>(
      source(stdout = "not json", diagnostics = diagnostics).pullRequest(REPO, 9),
    )

    assertEquals(1, diagnostics.warnings.size)
    assertIs<JsonProcessingException>(diagnostics.warnings.single().second)
  }

  @Test
  fun `a paginated listing concatenates every page and asks for all states`() {
    val pages = "[${pullRequestJson(1)}]\n[${pullRequestJson(2, state = "merged")}]"

    val listing = assertIs<MonitorPullRequestListing.Complete>(source(stdout = pages).pullRequests(REPO))

    assertEquals(listOf(1, 2), listing.pullRequests.map { it.identity.number })
    assertEquals(MonitorPullRequestState.MERGED, listing.pullRequests.last().state)
    assertTrue("--paginate" in calls.single() && "state=all" in calls.single())
  }

  @Test
  fun `a branch listing scopes the query to the head owner and branch without url interpolation`() {
    val fork = MonitorRepositoryIdentity.canonical("github.com", "fork", "repo")
    source(stdout = "[]").pullRequestsForBranch(REPO, fork, "feat/x&y")

    assertTrue("head=fork:feat/x&y" in calls.single())
    assertEquals("repos/acme/repo/pulls", calls.single().last())
  }

  @Test
  fun `a truncated, malformed, or failed listing fails as a whole instead of returning a partial result`() {
    val page = "[${pullRequestJson(1)}]"
    listOf(
      source(stdout = "$page\n[{\"number\":2"),
      source(stdout = "$page [truncated]"),
      source(stdout = """[{"number":2,"state":"open"}]"""),
      source(stdout = """{"not":"an array"}"""),
      source(exitCode = 1, stdout = "gh: rate limited"),
    ).forEach { assertIs<MonitorPullRequestListing.Failed>(it.pullRequests(REPO)) }
  }
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<Pair<String, Throwable?>>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message to error
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun info(message: String) = Unit
}
