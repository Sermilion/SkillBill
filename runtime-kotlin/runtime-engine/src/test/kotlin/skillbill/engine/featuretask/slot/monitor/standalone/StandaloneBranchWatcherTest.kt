package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.phaserun.monitor.ACME
import skillbill.engine.featuretask.phaserun.monitor.FakeMonitorPullRequestSource
import skillbill.engine.featuretask.phaserun.monitor.SHA
import skillbill.engine.featuretask.phaserun.monitor.head
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.model.MonitorBranchIdentity
import skillbill.ports.monitor.model.MonitorBranchLookup
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private const val FIRST = "1111111111111111111111111111111111111111"
private const val SECOND = "2222222222222222222222222222222222222222"
private const val THIRD = "3333333333333333333333333333333333333333"
private const val LINK = "https://ci.example/build"
private val POLL: Duration = Duration.ofSeconds(30)

class StandaloneBranchWatcherTest {
  private var clock = Instant.parse("2026-11-02T10:00:00Z")
  private val tips = ArrayDeque<MonitorBranchLookup>()
  private val bySha = mutableMapOf<String, PullRequestChecks>()
  private val adopted = mutableListOf<String>()
  private val refusals = mutableMapOf<String, String>()
  private var unavailableUntil = clock
  private var cancelled = false
  private val checks =
    object : MonitorCheckSource {
      override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks =
        error("the branch watcher reads commit checks")

      override fun commitChecks(
        repository: MonitorRepositoryIdentity,
        sha: String,
      ): PullRequestChecks =
        if (clock < unavailableUntil) {
          PullRequestChecks.Unavailable("gh failed")
        } else {
          bySha[sha] ?: PullRequestChecks.NoChecks
        }

      override fun failureLog(
        pullRequest: MonitorPullRequestIdentity,
        check: PullRequestCheck,
      ): MonitorFailureLog = error("the branch watcher reads no logs")
    }
  private val source =
    FakeMonitorPullRequestSource(
      branchLookup = { if (tips.size > 1) tips.removeFirst() else tips.first() },
    )

  private fun tip(sha: String): MonitorBranchLookup =
    MonitorBranchLookup.Found(head(ref = "main", sha = sha), true, MonitorBranchTip(ACME, "main", SHA))

  private fun reads(vararg shas: String) = tips.addAll(shas.map(::tip))

  private fun bucket(bucket: CheckBucket) = PullRequestChecks.Reported(listOf(PullRequestCheck("build", bucket, LINK)))

  private fun watch(): PullRequestCiOutcome =
    StandaloneBranchWatcher(
      source,
      checks,
      { clock },
      { clock += it },
      { cancelled },
    ).watch(
      MonitorBranchIdentity(ACME, "main"),
      FIRST,
      { sha ->
        adopted += sha
        refusals[sha]
      },
      { _, _ -> },
    )

  @Test
  fun `a tip that moves during confirmation discards the verdict and the new tip decides`() {
    reads(FIRST, SECOND)
    bySha[FIRST] = bucket(CheckBucket.FAIL)
    bySha[SECOND] = bucket(CheckBucket.PASS)

    assertEquals(PullRequestCiOutcome.Passed(SECOND), watch())
    assertEquals(listOf(SECOND), adopted)
  }

  @Test
  fun `a failing check fails the verdict while other checks are still pending`() {
    reads(FIRST)
    val failing = PullRequestCheck("build", CheckBucket.FAIL, LINK)
    bySha[FIRST] = PullRequestChecks.Reported(listOf(failing, PullRequestCheck("slow", CheckBucket.PENDING, LINK)))

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), FIRST, null), watch())
  }

  @Test
  fun `a passed verdict names the cancelled checks`() {
    reads(FIRST)
    bySha[FIRST] =
      PullRequestChecks.Reported(
        listOf(PullRequestCheck("build", CheckBucket.PASS, LINK), PullRequestCheck("deploy", CheckBucket.CANCEL, LINK)),
      )

    assertEquals(PullRequestCiOutcome.Passed(FIRST, listOf("deploy")), watch())
  }

  @Test
  fun `a followed commit with no checks blocks one start timeout after it was adopted, not after the watch began`() {
    reads(FIRST, SECOND)
    val start = clock

    val outcome = watch()

    assertEquals(PullRequestCiOutcome.Blocked("CI did not start within 60 minutes on $SECOND."), outcome)
    assertEquals(start + POLL + Duration.ofHours(1), clock)
  }

  @Test
  fun `a rewound tip the run saw earlier is followed instead of hanging`() {
    reads(SECOND, FIRST, FIRST)
    bySha[FIRST] = bucket(CheckBucket.PASS)

    assertEquals(PullRequestCiOutcome.Passed(FIRST), watch())
    assertEquals(listOf(SECOND, FIRST), adopted)
  }

  @Test
  fun `an adoption refusal retries when the tip moved again and blocks when it did not`() {
    refusals[SECOND] = "the fetch failed"
    reads(SECOND, THIRD)
    bySha[THIRD] = bucket(CheckBucket.PASS)
    assertEquals(PullRequestCiOutcome.Passed(THIRD), watch())
    assertEquals(listOf(SECOND, THIRD), adopted)

    tips.clear()
    reads(SECOND)
    assertEquals(
      PullRequestCiOutcome.Blocked("The branch moved to $SECOND and monitoring could not follow it: the fetch failed"),
      watch(),
    )
  }

  @Test
  fun `unavailable checks keep polling and never count toward the start timeout`() {
    reads(FIRST)
    unavailableUntil = clock + Duration.ofHours(2)
    bySha[FIRST] = bucket(CheckBucket.FAIL)

    assertEquals(
      PullRequestCiOutcome.Failed(listOf(PullRequestCheck("build", CheckBucket.FAIL, LINK)), FIRST, null),
      watch(),
    )
  }

  @Test
  fun `a cancelled watch stops before it reads the branch`() {
    cancelled = true

    assertEquals(PullRequestCiOutcome.Blocked(StandalonePullRequestWatcher.CANCELLED_REASON), watch())
  }

  @Test
  fun `a deleted branch blocks and a failed read keeps polling`() {
    tips.add(MonitorBranchLookup.Failed("rate limited"))
    tips.add(MonitorBranchLookup.Missing)

    assertEquals(PullRequestCiOutcome.Blocked("Branch main no longer exists on acme/repo."), watch())
  }
}
