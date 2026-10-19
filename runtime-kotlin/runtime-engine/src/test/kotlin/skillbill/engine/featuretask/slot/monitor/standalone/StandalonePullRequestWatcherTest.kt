package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.phaserun.monitor.FakeMonitorPullRequestSource
import skillbill.engine.featuretask.phaserun.monitor.head
import skillbill.engine.featuretask.phaserun.monitor.identity
import skillbill.engine.featuretask.phaserun.monitor.pullRequest
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PUBLISHED = "1111111111111111111111111111111111111111"
private const val REPAIRED = "2222222222222222222222222222222222222222"
private const val FOREIGN = "3333333333333333333333333333333333333333"
private const val PR_NUMBER = 427

class StandalonePullRequestWatcherTest {
  private var clock = Instant.parse("2026-10-16T10:00:00Z")
  private val sleeps = mutableListOf<Duration>()
  private var reported: PullRequestChecks = PullRequestChecks.NoChecks
  private val reportedSequence = ArrayDeque<PullRequestChecks>()
  private var state = MonitorPullRequestState.OPEN
  private var conflicting = false
  private var lookupFailure: String? = null
  private var cancelled = false
  private val running = mutableListOf<List<String>>()
  private val adopted = mutableListOf<String>()
  private var adoptionRefusal: String? = null
  private val checks =
    object : MonitorCheckSource {
      override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks =
        when {
          reportedSequence.size > 1 -> reportedSequence.removeFirst()
          reportedSequence.isNotEmpty() -> reportedSequence.first()
          else -> reported
        }

      override fun failureLog(
        pullRequest: MonitorPullRequestIdentity,
        check: PullRequestCheck,
      ): MonitorFailureLog = MonitorFailureLog.Unavailable("not needed")
    }
  private val heads = ArrayDeque<String>()
  private val source =
    FakeMonitorPullRequestSource(
      lookup = {
        val sha = if (heads.size > 1) heads.removeFirst() else heads.first()
        lookupFailure?.let(MonitorPullRequestLookup::Failed)
          ?: MonitorPullRequestLookup.Found(
            pullRequest(it, state = state, head = head(sha = sha)).copy(conflicting = conflicting),
          )
      },
    )

  private fun watch(
    expected: String = PUBLISHED,
    superseded: Set<String> = emptySet(),
    refreshAfter: Instant? = null,
  ): PullRequestCiOutcome =
    StandalonePullRequestWatcher(
      source = source,
      checks = checks,
      now = { clock },
      sleep = { duration ->
        sleeps += duration
        clock = clock.plus(duration)
      },
      cancelled = { cancelled },
    ).watch(
      identity(PR_NUMBER),
      StandaloneWatchHeads(expected, superseded, refreshAfter),
      { sha -> adoptionRefusal.also { if (it == null) adopted += sha } },
    ) { _, pending ->
      running += pending.map(PullRequestCheck::name)
    }

  private fun check(
    name: String,
    bucket: CheckBucket,
  ) = PullRequestCheck(name, bucket, "https://github.com/acme/repo/actions/runs/$name")

  private fun reports(vararg checks: PullRequestCheck) = PullRequestChecks.Reported(checks.toList())

  @Test
  fun `a pull request that never reports a check stops at the one hour obstacle`() {
    heads += PUBLISHED

    val outcome = watch()

    assertEquals(PullRequestCiOutcome.Blocked("CI did not start within 60 minutes."), outcome)
    assertTrue(Duration.between(Instant.parse("2026-10-16T10:00:00Z"), clock) >= Duration.ofHours(1))
  }

  @Test
  fun `after a pull request edit a stale failure waits for the check that re-ran on the edit`() {
    heads += PUBLISHED
    val editedAt = clock.minusSeconds(60)
    reportedSequence +=
      listOf(
        reports(check("danger/danger", CheckBucket.FAIL)),
        reports(check("danger/danger", CheckBucket.FAIL), check("danger", CheckBucket.PENDING)),
        reports(
          check("danger/danger", CheckBucket.PASS),
          check("danger", CheckBucket.PASS).copy(startedAt = editedAt.plusSeconds(5)),
        ),
      )

    assertEquals(PullRequestCiOutcome.Passed(PUBLISHED), watch(refreshAfter = editedAt))
    assertEquals(2, sleeps.size)
  }

  @Test
  fun `after a pull request edit a failure no check refreshes counts once the grace passes`() {
    heads += PUBLISHED
    val failing = check("danger/danger", CheckBucket.FAIL)
    reported = reports(failing)
    val startedAt = clock

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), PUBLISHED), watch(refreshAfter = startedAt))
    assertTrue(Duration.between(startedAt, clock) >= Duration.ofMinutes(10))
  }

  @Test
  fun `a head that still shows the superseded commit keeps polling until the repair becomes visible`() {
    heads += listOf(PUBLISHED, PUBLISHED, REPAIRED)
    reported = PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.PASS, "")))

    val outcome = watch(expected = REPAIRED, superseded = setOf(PUBLISHED))

    assertEquals(PullRequestCiOutcome.Passed(REPAIRED), outcome)
    assertEquals(2, sleeps.size)
  }

  @Test
  fun `a head pushed by someone else is adopted and its own checks are judged`() {
    heads += FOREIGN
    val failing = check("build", CheckBucket.FAIL)
    reported = reports(failing)

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), FOREIGN), watch())
    assertEquals(listOf(FOREIGN), adopted)
  }

  @Test
  fun `a head the monitor cannot follow blocks with the refusal`() {
    heads += FOREIGN
    adoptionRefusal = "The owned worktree has uncommitted changes."

    val outcome = assertIs<PullRequestCiOutcome.Blocked>(watch())

    assertTrue(FOREIGN in outcome.reason && "uncommitted changes" in outcome.reason, outcome.reason)
  }

  @Test
  fun `failing checks are attributed to the head this invocation published`() {
    heads += PUBLISHED
    val failing = PullRequestCheck("build", CheckBucket.FAIL, "https://github.com/acme/repo/actions/runs/1")
    reported = PullRequestChecks.Reported(listOf(failing, PullRequestCheck("lint", CheckBucket.PASS, "")))

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), PUBLISHED), watch())
  }

  @Test
  fun `a merged pull request completes monitoring even while its checks fail`() {
    heads += PUBLISHED
    state = MonitorPullRequestState.MERGED
    reported = reports(check("build", CheckBucket.FAIL))

    assertEquals(PullRequestCiOutcome.Merged, watch())
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `a pull request closed without merging blocks`() {
    heads += PUBLISHED
    state = MonitorPullRequestState.CLOSED

    val outcome = assertIs<PullRequestCiOutcome.Blocked>(watch())

    assertTrue("closed without merging" in outcome.reason, outcome.reason)
  }

  @Test
  fun `a conflicting pull request starts merge repair even before any check is reported`() {
    heads += PUBLISHED
    conflicting = true

    assertEquals(PullRequestCiOutcome.Conflicted, watch())
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `a stale conflict flag on the superseded head waits for the repair instead of starting another`() {
    heads += listOf(PUBLISHED, REPAIRED)
    conflicting = true
    reported = reports(check("build", CheckBucket.PASS))

    val outcome = watch(expected = REPAIRED, superseded = setOf(PUBLISHED))

    assertEquals(PullRequestCiOutcome.Conflicted, outcome)
    assertEquals(1, sleeps.size, "the superseded head must be waited out before any conflict is judged")
  }

  @Test
  fun `pending checks are reported as running and waited on until they pass`() {
    heads += PUBLISHED
    reportedSequence += reports(check("build", CheckBucket.PENDING))
    reportedSequence += reports(check("build", CheckBucket.PASS))

    assertEquals(PullRequestCiOutcome.Passed(PUBLISHED), watch())
    assertEquals(1, sleeps.size)
    assertEquals(listOf(listOf("build")), running)
  }

  @Test
  fun `a failing check starts repair while other checks are still pending`() {
    heads += PUBLISHED
    val failing = check("build", CheckBucket.FAIL)
    reported = reports(failing, check("e2e", CheckBucket.PENDING))

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), PUBLISHED), watch())
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `skipped and cancelled checks pass only once nothing is pending`() {
    heads += PUBLISHED
    reportedSequence += reports(check("docs", CheckBucket.SKIPPING), check("e2e", CheckBucket.PENDING))
    reportedSequence += reports(check("docs", CheckBucket.SKIPPING), check("e2e", CheckBucket.CANCEL))

    assertEquals(PullRequestCiOutcome.Passed(PUBLISHED), watch())
    assertEquals(1, sleeps.size, "a skipped check must not pass while another is pending")
  }

  @Test
  fun `an unavailable check service is reported with its evidence`() {
    heads += PUBLISHED
    reported = PullRequestChecks.Unavailable("gh: HTTP 502")

    assertEquals(PullRequestCiOutcome.Unavailable("gh: HTTP 502"), watch())
  }

  @Test
  fun `a pull request that cannot be read is reported unavailable rather than missing`() {
    heads += PUBLISHED
    lookupFailure = "gh: rate limited"

    assertEquals(PullRequestCiOutcome.Unavailable("gh: rate limited"), watch())
  }

  @Test
  fun `cancellation stops monitoring before the pull request is read again`() {
    heads += PUBLISHED
    cancelled = true

    assertEquals(PullRequestCiOutcome.Blocked(StandalonePullRequestWatcher.CANCELLED_REASON), watch())
    assertEquals(emptyList(), source.lookups)
  }
}
