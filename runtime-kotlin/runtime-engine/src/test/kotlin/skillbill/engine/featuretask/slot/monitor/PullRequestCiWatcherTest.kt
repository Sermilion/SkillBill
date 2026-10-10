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
  fun `failed or cancelled checks trigger repair without waiting for pending checks`() {
    for (bucket in listOf(CheckBucket.FAIL, CheckBucket.CANCEL)) {
      val clockBefore = clock
      val failedCheck = check("lint", bucket)
      val checks =
        ScriptedChecks(
          PullRequestChecks.Reported(
            listOf(check("integration", CheckBucket.PENDING), failedCheck, check("build", CheckBucket.PASS)),
          ),
        )

      val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

      assertEquals(PullRequestCiOutcome.Failed(listOf(failedCheck)), outcome)
      assertEquals(1, checks.calls)
      assertEquals(clockBefore, clock)
    }
  }

  @Test
  fun `pending checks are watched past the start timeout until they pass`() {
    val pending = PullRequestChecks.Reported(listOf(check("integration", CheckBucket.PENDING)))
    val checks =
      ScriptedChecks(
        pending,
        pending,
        pending,
        pending,
        pending,
        PullRequestChecks.Reported(listOf(check("integration", CheckBucket.PASS))),
      )

    val outcome = watcher(checks, startTimeout = Duration.ofMinutes(2)).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Passed, outcome)
    assertEquals(6, checks.calls)
  }

  @Test
  fun `no checks within the start timeout block`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome = watcher(checks, startTimeout = Duration.ofMinutes(4)).watch(REPO_ROOT, BRANCH)

    val blocked = assertIs<PullRequestCiOutcome.Blocked>(outcome)
    assertTrue("did not start within 4 minutes" in blocked.reason, blocked.reason)
    assertEquals(9, checks.calls)
  }

  @Test
  fun `late checks are watched until pending checks pass`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.NoChecks,
        PullRequestChecks.NoChecks,
        PullRequestChecks.NoChecks,
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))),
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Passed, outcome)
    assertEquals(5, checks.calls)
  }

  @Test
  fun `an empty reported check list blocks when ci does not start`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(emptyList()))

    val outcome = watcher(checks, startTimeout = Duration.ofMinutes(2)).watch(REPO_ROOT, BRANCH)

    val blocked = assertIs<PullRequestCiOutcome.Blocked>(outcome)
    assertTrue("did not start within 2 minutes" in blocked.reason, blocked.reason)
  }

  @Test
  fun `checks that appear before the start timeout are watched normally`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.NoChecks,
        PullRequestChecks.Reported(listOf(check("lint", CheckBucket.FAIL))),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals(listOf("lint"), failed.failingChecks.map(PullRequestCheck::name))
  }

  @Test
  fun `a merged pull request completes monitoring regardless of failing checks`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.FAIL))))

    val outcome = watcher(checks, identity = PullRequestIdentity.Merged(PR_URL, PR_NUMBER)).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Merged, outcome)
    assertEquals(0, checks.calls)
  }

  @Test
  fun `a pull request merged during pending CI completes on the next poll`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))))
    var identityCalls = 0
    val identities =
      PullRequestIdentityLookup { _, _ ->
        identityCalls += 1
        if (identityCalls == 1) {
          PullRequestIdentity.Found(PR_URL, PR_NUMBER)
        } else {
          PullRequestIdentity.Merged(PR_URL, PR_NUMBER)
        }
      }

    val outcome = watcher(checks, identityLookup = identities).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Merged, outcome)
    assertEquals(1, checks.calls)
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
    startTimeout: Duration = Duration.ofHours(1),
    identityLookup: PullRequestIdentityLookup = fixedIdentity(identity),
  ): PullRequestCiWatcher =
    PullRequestCiWatcher(
      identityLookup = identityLookup,
      checksLookup = checks,
      now = { clock },
      sleep = { clock = clock.plus(it) },
      pollInterval = Duration.ofSeconds(30),
      startTimeout = startTimeout,
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
