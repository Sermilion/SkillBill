package skillbill.infrastructure.workflow.monitor

import skillbill.infrastructure.workflow.github.GhCommandResult
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
private val TARGET = MonitorPullRequestIdentity(MonitorRepositoryIdentity.canonical("github.com", "acme", "repo"), 427)

class GhMonitorCheckSourceTest {
  private val calls = mutableListOf<List<String>>()
  private val source =
    GhMonitorCheckSource(
      GhCommandRunner { _, args ->
        calls += args
        GhCommandResult(0, "failed step output")
      },
      NoopRuntimeDiagnostics,
    )

  private fun failing(link: String) = PullRequestCheck("build", CheckBucket.FAIL, link)

  private fun commitChecks(
    checkRuns: GhCommandResult,
    statuses: GhCommandResult,
  ): PullRequestChecks =
    GhMonitorCheckSource(
      GhCommandRunner { _, args ->
        calls += args
        if (args.last().endsWith("/check-runs")) checkRuns else statuses
      },
      NoopRuntimeDiagnostics,
    ).commitChecks(TARGET.repository, COMMIT)

  private fun checkRun(
    name: String,
    conclusion: String?,
    status: String = "completed",
  ): String {
    val conclusionJson = conclusion?.let { "\"$it\"" }
    return """{"name":"$name","status":"$status","conclusion":$conclusionJson,"details_url":"https://ci/$name"}"""
  }

  private fun runsPage(vararg runs: String) = GhCommandResult(0, """{"check_runs":[${runs.joinToString(",")}]}""")

  private fun statusPage(vararg contexts: String) =
    GhCommandResult(0, """{"state":"pending","statuses":[${contexts.joinToString(",")}]}""")

  @Test
  fun `commit checks map every conclusion and status context to its bucket across concatenated pages`() {
    val expected =
      mapOf(
        "success" to CheckBucket.PASS,
        "skipped" to CheckBucket.SKIPPING,
        "neutral" to CheckBucket.SKIPPING,
        "failure" to CheckBucket.FAIL,
        "timed_out" to CheckBucket.FAIL,
        "action_required" to CheckBucket.FAIL,
        "error" to CheckBucket.FAIL,
        "startup_failure" to CheckBucket.FAIL,
        "cancelled" to CheckBucket.CANCEL,
        "stale" to CheckBucket.PENDING,
      )
    val pages = expected.keys.map { runsPage(checkRun(it, it)).stdout }.joinToString("")
    val running = runsPage(checkRun("running", null, status = "in_progress")).stdout
    val contexts =
      statusPage(
        """{"context":"ci/ok","state":"success","target_url":"https://ci/ok"}""",
        """{"context":"ci/bad","state":"error"}""",
        """{"context":"ci/wait","state":"pending"}""",
      )

    val reported =
      assertIs<PullRequestChecks.Reported>(commitChecks(GhCommandResult(0, pages + running), contexts)).checks

    assertEquals(
      expected + mapOf("running" to CheckBucket.PENDING) +
        mapOf("ci/ok" to CheckBucket.PASS, "ci/bad" to CheckBucket.FAIL, "ci/wait" to CheckBucket.PENDING),
      reported.associate { it.name to it.bucket },
    )
    assertEquals("https://ci/ok", reported.single { it.name == "ci/ok" }.link)
    assertEquals("--paginate", calls.first()[calls.first().indexOf("GET") + 1])
  }

  @Test
  fun `a pending combined state with zero contexts is not a pending check`() {
    assertEquals(PullRequestChecks.NoChecks, commitChecks(runsPage(), statusPage()))
  }

  @Test
  fun `one failed endpoint, unparsable output, or a nameless entry never yields a partial answer`() {
    val failed = GhCommandResult(1, "gh: boom (HTTP 500)")
    val passing = runsPage(checkRun("build", "success"))

    assertIs<PullRequestChecks.Unavailable>(commitChecks(failed, statusPage()))
    assertIs<PullRequestChecks.Unavailable>(commitChecks(passing, failed))
    assertIs<PullRequestChecks.Unavailable>(commitChecks(passing, GhCommandResult(0, "{not json")))
    assertIs<PullRequestChecks.Unavailable>(commitChecks(runsPage("""{"status":"completed"}"""), statusPage()))
  }

  @Test
  fun `a failing check's own workflow run is read through the target repository`() {
    val log = source.failureLog(TARGET, failing("https://github.com/acme/repo/actions/runs/91/job/7"))

    assertEquals(MonitorFailureLog.Available("failed step output"), log)
    assertEquals(
      listOf("run", "view", "91", "--job", "7", "--log-failed", "--repo", "github.com/acme/repo"),
      calls.single(),
    )
  }

  @Test
  fun `a check link that points at another repository or host is never fetched`() {
    listOf(
      "https://github.com/other/repo/actions/runs/91",
      "https://evil.example/acme/repo/actions/runs/91",
      "https://github.com/acme/repo/pull/1?x=/acme/repo/actions/runs/91",
      "not a url",
    ).forEach { link ->
      assertIs<MonitorFailureLog.Unavailable>(source.failureLog(TARGET, failing(link)), link)
    }
    assertEquals(emptyList(), calls)
  }
}
