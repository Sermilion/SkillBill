package skillbill.infrastructure.workflow.monitor

import skillbill.infrastructure.workflow.github.GhCommandResult
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val TARGET = MonitorPullRequestIdentity(MonitorRepositoryIdentity.canonical("github.com", "acme", "repo"), 427)

class GhMonitorCheckSourceTest {
  private val calls = mutableListOf<List<String>>()
  private val source =
    GhMonitorCheckSource(
      GhCommandRunner { _, args ->
        calls += args
        GhCommandResult(0, "failed step output")
      },
    )

  private fun failing(link: String) = PullRequestCheck("build", CheckBucket.FAIL, link)

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
