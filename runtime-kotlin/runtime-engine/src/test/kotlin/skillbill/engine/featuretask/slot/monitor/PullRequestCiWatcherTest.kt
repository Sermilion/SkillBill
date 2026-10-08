package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PullRequestCiWatcherTest {
  private var clock: Instant = Instant.parse("2026-10-08T10:00:00Z")

  @Test
  fun `pending checks then a pass settle as passed`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))),
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Passed, outcome)
    assertEquals(2, checks.calls)
  }

  @Test
  fun `failing and cancelled checks settle as failed with the failing checks only`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(
            check("lint", CheckBucket.FAIL),
            check("unit", CheckBucket.CANCEL),
            check("build", CheckBucket.PASS),
          ),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals(listOf("lint", "unit"), failed.failingChecks.map(PullRequestCheck::name))
  }

  @Test
  fun `pending checks past the watch timeout block and name the pending checks`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(listOf(check("integration", CheckBucket.PENDING))),
      )

    val outcome = watcher(checks, watchTimeout = Duration.ofMinutes(2)).watch(REPO_ROOT, BRANCH)

    val blocked = assertIs<PullRequestCiOutcome.Blocked>(outcome)
    assertTrue("integration" in blocked.reason, blocked.reason)
    assertTrue("did not finish within 2 minutes" in blocked.reason, blocked.reason)
  }

  @Test
  fun `no checks beyond the grace period settle as no ci configured`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome = watcher(checks, noChecksGrace = Duration.ofMinutes(3)).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.NoCiConfigured, outcome)
    assertTrue(checks.calls > 1, "the grace period must be polled, not decided on the first empty read")
  }

  @Test
  fun `checks that appear within the grace period are watched normally`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.NoChecks,
        PullRequestChecks.Reported(listOf(check("lint", CheckBucket.FAIL))),
      )

    val outcome = watcher(checks, noChecksGrace = Duration.ofMinutes(3)).watch(REPO_ROOT, BRANCH)

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals(listOf("lint"), failed.failingChecks.map(PullRequestCheck::name))
  }

  @Test
  fun `a missing pull request settles without reading checks`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome = watcher(checks, identity = PullRequestIdentity.Absent).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.NoPullRequest, outcome)
    assertEquals(0, checks.calls)
  }

  @Test
  fun `an unavailable checks lookup reports unavailable with its reason`() {
    val checks = ScriptedChecks(PullRequestChecks.Unavailable("gh pr checks --json unsupported; upgrade gh"))

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    assertEquals(
      PullRequestCiOutcome.Unavailable("gh pr checks --json unsupported; upgrade gh"),
      outcome,
    )
  }

  @Test
  fun `an unavailable pull request lookup reports unavailable with its reason`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome =
      watcher(checks, identity = PullRequestIdentity.Unavailable("gh is not authenticated")).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Unavailable("gh is not authenticated"), outcome)
    assertEquals(0, checks.calls)
  }

  private fun watcher(
    checks: ScriptedChecks,
    identity: PullRequestIdentity = PullRequestIdentity.Found(url = PR_URL, number = PR_NUMBER),
    watchTimeout: Duration = Duration.ofMinutes(30),
    noChecksGrace: Duration = Duration.ofMinutes(3),
  ): PullRequestCiWatcher =
    PullRequestCiWatcher(
      identityLookup = fixedIdentity(identity),
      checksLookup = checks,
      now = { clock },
      sleep = { clock = clock.plus(it) },
      pollInterval = Duration.ofSeconds(30),
      watchTimeout = watchTimeout,
      noChecksGrace = noChecksGrace,
    )

  private fun fixedIdentity(identity: PullRequestIdentity): PullRequestIdentityLookup =
    object : PullRequestIdentityLookup {
      override fun lookup(
        repoRoot: Path,
        branch: String,
      ): PullRequestIdentity = identity
    }

  private fun check(
    name: String,
    bucket: CheckBucket,
  ): PullRequestCheck = PullRequestCheck(name = name, bucket = bucket, link = "https://ci.example/$name")

  private class ScriptedChecks(vararg scripted: PullRequestChecks) : PullRequestChecksLookup {
    private val responses = scripted.toList()
    var calls: Int = 0
      private set

    override fun lookup(
      repoRoot: Path,
      prNumber: Int,
    ): PullRequestChecks {
      val response = responses[minOf(calls, responses.lastIndex)]
      calls += 1
      return response
    }
  }

  private companion object {
    val REPO_ROOT: Path = Path.of("/repo")
    const val BRANCH = "feat/SKILL-407-monitor"
    const val PR_NUMBER = 42
    const val PR_URL = "https://github.com/example/skill-bill/pull/42"
  }
}
