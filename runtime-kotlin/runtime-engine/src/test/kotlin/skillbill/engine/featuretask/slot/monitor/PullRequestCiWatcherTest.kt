package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.slot.state.ExpectedCiHead
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

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(2, checks.calls)
  }

  @Test
  fun `green checks pass on the current pull request head without a recorded publication`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))))

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH)

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
  }

  @Test
  fun `green checks pass on a pull request head pushed after the recorded publication`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))))

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead("older-head"))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
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

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals(listOf("lint"), failed.failingChecks.map(PullRequestCheck::name))
  }

  @Test
  fun `all cancelled checks settle as passed`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(check("ios-build", CheckBucket.CANCEL), check("desktop-build", CheckBucket.CANCEL)),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(1, checks.calls)
  }

  @Test
  fun `cancelled skipped and passing checks settle as passed`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(
            check("lint", CheckBucket.CANCEL),
            check("unit", CheckBucket.SKIPPING),
            check("build", CheckBucket.PASS),
          ),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
  }

  @Test
  fun `cancelled checks with pending keep watching until remaining checks settle`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(check("lint", CheckBucket.CANCEL), check("integration", CheckBucket.PENDING)),
        ),
        PullRequestChecks.Reported(
          listOf(check("lint", CheckBucket.CANCEL), check("integration", CheckBucket.PASS)),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(2, checks.calls)
  }

  @Test
  fun `failed checks trigger repair without waiting for pending checks`() {
    val clockBefore = clock
    val failedCheck = check("lint", CheckBucket.FAIL)
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(check("integration", CheckBucket.PENDING), failedCheck, check("build", CheckBucket.PASS)),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Failed(listOf(failedCheck), HEAD_SHA), outcome)
    assertEquals(1, checks.calls)
    assertEquals(clockBefore, clock)
  }

  @Test
  fun `a failure left over from before a pull request edit waits for checks that ran after the repair`() {
    val repairStartedAt = clock.minus(Duration.ofMinutes(5))
    val earlierRun = repairStartedAt.minus(Duration.ofHours(1))
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(check("danger/danger", CheckBucket.FAIL), check("danger", CheckBucket.PASS, startedAt = earlierRun)),
        ),
        PullRequestChecks.Reported(
          listOf(check("danger/danger", CheckBucket.FAIL), check("danger", CheckBucket.PENDING)),
        ),
        PullRequestChecks.Reported(
          listOf(
            check("danger/danger", CheckBucket.PASS),
            check("danger", CheckBucket.PASS, startedAt = repairStartedAt.plusSeconds(60)),
          ),
        ),
      )

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA, repairStartedAt))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(3, checks.calls)
  }

  @Test
  fun `a failure that no check refreshes after a pull request edit counts once the grace passes`() {
    val watchStartedAt = clock
    val failing = check("danger/danger", CheckBucket.FAIL)
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(failing)))

    val outcome =
      watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA, watchStartedAt))

    assertEquals(PullRequestCiOutcome.Failed(listOf(failing), HEAD_SHA), outcome)
    assertTrue(Duration.between(watchStartedAt, clock) >= Duration.ofMinutes(10))
  }

  @Test
  fun `a changed pull request head keeps watching until failing checks have a stable head`() {
    var identityCalls = 0
    val identities =
      PullRequestIdentityLookup { _, _ ->
        identityCalls += 1
        PullRequestIdentity.Found(
          PR_URL,
          PR_NUMBER,
          headSha = if (identityCalls == 1) "observed-head" else "new-head",
        )
      }
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("lint", CheckBucket.FAIL))))
    val inconclusive = mutableListOf<String>()

    val outcome =
      watcher(checks, identityLookup = identities).watch(
        REPO_ROOT,
        BRANCH,
        expectedHead = ExpectedCiHead(HEAD_SHA),
        onInconclusive = inconclusive::add,
      )

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals("new-head", failed.headSha)
    assertEquals(2, checks.calls)
    assertEquals(listOf("Pull request identity changed during check observation."), inconclusive)
  }

  @Test
  fun `a merge conflict found while refreshing failed checks routes to conflict repair`() {
    var identityCalls = 0
    val identities =
      PullRequestIdentityLookup { _, _ ->
        identityCalls += 1
        PullRequestIdentity.Found(
          PR_URL,
          PR_NUMBER,
          conflicting = identityCalls == 2,
          headSha = "same-head",
        )
      }
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("lint", CheckBucket.FAIL))))

    val outcome =
      watcher(
        checks,
        identityLookup = identities,
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Conflicted, outcome)
  }

  @Test
  fun `pending checks keep reporting every poll until ci settles`() {
    val pending =
      PullRequestChecks.Reported(
        listOf(check("desktop-build", CheckBucket.PENDING), check("build", CheckBucket.PASS)),
      )
    val checks =
      ScriptedChecks(
        pending,
        pending,
        pending,
        pending,
        pending,
        PullRequestChecks.Reported(
          listOf(check("desktop-build", CheckBucket.PASS), check("build", CheckBucket.PASS)),
        ),
      )
    val reported = mutableListOf<Pair<String, List<PullRequestCheck>>>()

    val outcome =
      watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA), onCiRunning = {
          pullRequestUrl,
          pendingChecks,
        ->
        reported += pullRequestUrl to pendingChecks
      })

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(List(5) { PR_URL to listOf(check("desktop-build", CheckBucket.PENDING)) }, reported)
  }

  @Test
  fun `a newly pending check is reported before the progress interval`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))),
        PullRequestChecks.Reported(
          listOf(check("build", CheckBucket.PENDING), check("desktop-build", CheckBucket.PENDING)),
        ),
        PullRequestChecks.Reported(
          listOf(check("build", CheckBucket.PASS), check("desktop-build", CheckBucket.PASS)),
        ),
      )
    val reported = mutableListOf<Pair<String, List<PullRequestCheck>>>()

    watcher(
      checks,
      progressInterval = Duration.ofMinutes(10),
    ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA), onCiRunning = { pullRequestUrl, pendingChecks ->
      reported += pullRequestUrl to pendingChecks
    })

    assertEquals(
      listOf(
        PR_URL to listOf(check("build", CheckBucket.PENDING)),
        PR_URL to listOf(check("build", CheckBucket.PENDING), check("desktop-build", CheckBucket.PENDING)),
      ),
      reported,
    )
  }

  @Test
  fun `progress links omit user info and query parameters`() {
    val checks =
      ScriptedChecks(
        PullRequestChecks.Reported(
          listOf(
            PullRequestCheck(
              "build",
              CheckBucket.PENDING,
              "https://token@ci.example/run/1?access_token=secret",
            ),
          ),
        ),
        PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))),
      )
    val reported = mutableListOf<Pair<String, List<PullRequestCheck>>>()

    watcher(
      checks,
      identity =
        PullRequestIdentity.Found(
          "https://token@github.com/acme/repo/pull/42?secret=value",
          PR_NUMBER,
          headSha = HEAD_SHA,
        ),
    ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA), onCiRunning = { pullRequestUrl, pendingChecks ->
      reported += pullRequestUrl to pendingChecks
    })

    assertEquals(
      listOf(
        "https://github.com/acme/repo/pull/42" to
          listOf(PullRequestCheck("build", CheckBucket.PENDING, "https://ci.example/run/1")),
      ),
      reported,
    )
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

    val outcome =
      watcher(
        checks,
        startTimeout = Duration.ofMinutes(2),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(6, checks.calls)
  }

  @Test
  fun `no checks within the start timeout block`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome =
      watcher(
        checks,
        startTimeout = Duration.ofMinutes(4),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

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

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(5, checks.calls)
  }

  @Test
  fun `an empty reported check list blocks when ci does not start`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(emptyList()))

    val outcome =
      watcher(
        checks,
        startTimeout = Duration.ofMinutes(2),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

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

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    val failed = assertIs<PullRequestCiOutcome.Failed>(outcome)
    assertEquals(listOf("lint"), failed.failingChecks.map(PullRequestCheck::name))
  }

  @Test
  fun `a conflicting pull request settles before checks are read`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))))

    val outcome =
      watcher(
        checks,
        identity = PullRequestIdentity.Found(url = PR_URL, number = PR_NUMBER, conflicting = true),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Conflicted, outcome)
    assertEquals(0, checks.calls)
  }

  @Test
  fun `a merged pull request completes monitoring regardless of failing checks`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.FAIL))))

    val outcome =
      watcher(
        checks,
        identity = PullRequestIdentity.Merged(PR_URL, PR_NUMBER),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

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

    val outcome =
      watcher(
        checks,
        identityLookup = identities,
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Merged, outcome)
    assertEquals(1, checks.calls)
  }

  @Test
  fun `a merge observed before returning a failure overrides stale checks or conflict`() {
    for (conflicting in listOf(false, true)) {
      var identityCalls = 0
      val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.FAIL))))
      val identities =
        PullRequestIdentityLookup { _, _ ->
          identityCalls += 1
          if (identityCalls == 1) {
            PullRequestIdentity.Found(PR_URL, PR_NUMBER, conflicting = conflicting)
          } else {
            PullRequestIdentity.Merged(PR_URL, PR_NUMBER)
          }
        }

      val outcome =
        watcher(
          checks,
          identityLookup = identities,
        ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

      assertEquals(PullRequestCiOutcome.Merged, outcome)
      assertEquals(if (conflicting) 0 else 1, checks.calls)
    }
  }

  @Test
  fun `a cleared conflict resumes watching instead of reporting the stale conflict`() {
    var identityCalls = 0
    val identities =
      PullRequestIdentityLookup { _, _ ->
        identityCalls += 1
        PullRequestIdentity.Found(PR_URL, PR_NUMBER, conflicting = identityCalls == 1, headSha = HEAD_SHA)
      }
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PASS))))

    val outcome =
      watcher(
        checks,
        identityLookup = identities,
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Passed(HEAD_SHA), outcome)
    assertEquals(1, checks.calls)
  }

  @Test
  fun `a missing pull request settles without reading checks`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome =
      watcher(
        checks,
        identity = PullRequestIdentity.Absent,
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.NoPullRequest, outcome)
    assertEquals(0, checks.calls)
  }

  @Test
  fun `an unavailable identity refresh during pending CI retains its failure`() {
    val checks = ScriptedChecks(PullRequestChecks.Reported(listOf(check("build", CheckBucket.PENDING))))
    var identityCalls = 0
    val identities =
      PullRequestIdentityLookup { _, _ ->
        identityCalls += 1
        when (identityCalls) {
          1 -> PullRequestIdentity.Found(PR_URL, PR_NUMBER, headSha = HEAD_SHA)
          2 -> PullRequestIdentity.Unavailable("gh is not authenticated")
          else -> PullRequestIdentity.Merged(PR_URL, PR_NUMBER)
        }
      }

    val outcome =
      watcher(
        checks,
        identityLookup = identities,
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Unavailable("gh is not authenticated"), outcome)
  }

  @Test
  fun `an unavailable checks lookup reports unavailable with its reason`() {
    val checks = ScriptedChecks(PullRequestChecks.Unavailable("gh pr checks --json unsupported; upgrade gh"))

    val outcome = watcher(checks).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(
      PullRequestCiOutcome.Unavailable("gh pr checks --json unsupported; upgrade gh"),
      outcome,
    )
  }

  @Test
  fun `an unavailable pull request lookup reports unavailable with its reason`() {
    val checks = ScriptedChecks(PullRequestChecks.NoChecks)

    val outcome =
      watcher(
        checks,
        identity = PullRequestIdentity.Unavailable("gh is not authenticated"),
      ).watch(REPO_ROOT, BRANCH, expectedHead = ExpectedCiHead(HEAD_SHA))

    assertEquals(PullRequestCiOutcome.Unavailable("gh is not authenticated"), outcome)
    assertEquals(0, checks.calls)
  }

  private fun watcher(
    checks: ScriptedChecks,
    identity: PullRequestIdentity = PullRequestIdentity.Found(url = PR_URL, number = PR_NUMBER, headSha = HEAD_SHA),
    startTimeout: Duration = Duration.ofHours(1),
    progressInterval: Duration = Duration.ofSeconds(30),
    identityLookup: PullRequestIdentityLookup = fixedIdentity(identity),
  ): PullRequestCiWatcher =
    PullRequestCiWatcher(
      identityLookup = identityLookup,
      checksLookup = checks,
      now = { clock },
      sleep = { clock = clock.plus(it) },
      pollInterval = Duration.ofSeconds(30),
      startTimeout = startTimeout,
      progressInterval = progressInterval,
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
    startedAt: Instant? = null,
  ): PullRequestCheck =
    PullRequestCheck(name = name, bucket = bucket, link = "https://ci.example/$name", startedAt = startedAt)

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
    const val HEAD_SHA = "published-head"
    const val PR_NUMBER = 42
    const val PR_URL = "https://github.com/example/skill-bill/pull/42"
  }
}
