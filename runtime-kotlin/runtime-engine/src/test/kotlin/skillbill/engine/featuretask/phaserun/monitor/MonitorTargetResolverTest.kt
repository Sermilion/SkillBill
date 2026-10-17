package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MonitorTargetResolverTest {
  private val root = Path.of("/caller")
  private val source = FakeMonitorPullRequestSource(lookup = { MonitorPullRequestLookup.Found(pullRequest(it)) })
  private val resolver = MonitorTargetResolver(source)

  private fun resolve(selector: MonitorTargetSelector) = resolver.resolve(root, selector)

  private fun resolveUrl(url: String) = resolve(MonitorTargetSelector.Url(url))

  @Test
  fun `an exact pull request url resolves that pull request in its own repository`() {
    val open = assertIs<MonitorTargetResolution.Open>(resolveUrl("https://github.com/acme/repo/pull/5"))

    assertEquals(identity(5), open.target.pullRequest)
    assertEquals(listOf(ACME to 5), source.lookups)
    assertTrue(source.branchQueries.isEmpty())
  }

  @Test
  fun `a url for another repository or a malformed url is refused before any pull request lookup`() {
    val fork = assertIs<MonitorTargetResolution.Refused>(resolveUrl("https://github.com/fork/repo/pull/5"))
    val malformed = assertIs<MonitorTargetResolution.Refused>(resolveUrl("https://github.com/acme/repo/pull/5/files"))

    assertEquals(MonitorTargetRefusal.REPOSITORY_MISMATCH, fork.reason)
    assertEquals(MonitorTargetRefusal.UNSUPPORTED_URL, malformed.reason)
    assertTrue(source.lookups.isEmpty())
    assertTrue(source.branchQueries.isEmpty())
  }

  @Test
  fun `an unavailable caller repository is refused`() {
    source.caller = MonitorCallerRepository.Unavailable("no origin")

    val refused = assertIs<MonitorTargetResolution.Refused>(resolveUrl("https://github.com/acme/repo/pull/5"))

    assertEquals(MonitorTargetRefusal.CALLER_REPOSITORY_UNAVAILABLE, refused.reason)
  }

  @Test
  fun `explicit merged and closed pull requests complete or block while naming their identity`() {
    source.lookup = { MonitorPullRequestLookup.Found(pullRequest(it, state = MonitorPullRequestState.MERGED)) }
    assertEquals(MonitorTargetResolution.Merged(identity(5)), resolveUrl("https://github.com/acme/repo/pull/5"))

    source.lookup = { MonitorPullRequestLookup.Found(pullRequest(it, state = MonitorPullRequestState.CLOSED)) }
    assertEquals(MonitorTargetResolution.ClosedUnmerged(identity(5)), resolveUrl("https://github.com/acme/repo/pull/5"))
  }

  @Test
  fun `a missing, failed, or mismatched provider answer is never substituted with another pull request`() {
    source.lookup = { MonitorPullRequestLookup.Missing }
    assertEquals(
      MonitorTargetRefusal.TARGET_NOT_FOUND,
      assertIs<MonitorTargetResolution.Refused>(resolveUrl("https://github.com/acme/repo/pull/5")).reason,
    )

    source.lookup = { MonitorPullRequestLookup.Failed("rate limited") }
    assertEquals(
      MonitorTargetResolution.Failed("rate limited"),
      resolveUrl("https://github.com/acme/repo/pull/5"),
    )

    source.lookup = { MonitorPullRequestLookup.Found(pullRequest(99)) }
    assertEquals(
      MonitorTargetRefusal.INCOMPLETE_TARGET,
      assertIs<MonitorTargetResolution.Refused>(resolveUrl("https://github.com/acme/repo/pull/5")).reason,
    )
  }

  @Test
  fun `an issue key resolves the single listed pull request that names it`() {
    source.listing =
      MonitorPullRequestListing.Complete(listOf(summary(6, title = "SKILL-42"), summary(7, title = "SKILL-427")))

    val open = assertIs<MonitorTargetResolution.Open>(resolve(MonitorTargetSelector.IssueKey("SKILL-427")))

    assertEquals(identity(7), open.target.pullRequest)
    assertEquals(listOf(ACME to 7), source.lookups)
  }

  @Test
  fun `an issue key with no match, several matches, or an incomplete listing is not guessed`() {
    val key = MonitorTargetSelector.IssueKey("SKILL-427")
    source.listing = MonitorPullRequestListing.Complete(listOf(summary(6, title = "SKILL-42")))
    assertEquals(MonitorTargetRefusal.TARGET_NOT_FOUND, assertIs<MonitorTargetResolution.Refused>(resolve(key)).reason)

    source.listing =
      MonitorPullRequestListing.Complete(listOf(summary(1, title = "SKILL-427"), summary(2, body = "SKILL-427")))
    val ambiguous = assertIs<MonitorTargetResolution.Refused>(resolve(key))
    assertEquals(MonitorTargetRefusal.AMBIGUOUS_TARGET, ambiguous.reason)
    assertEquals(2, ambiguous.candidateUrls.size)

    source.listing = MonitorPullRequestListing.Failed("page 3 failed")
    assertEquals(MonitorTargetResolution.Failed("page 3 failed"), resolve(key))
    assertTrue(source.lookups.isEmpty())
  }

  @Test
  fun `an omitted target uses the caller branch and completes when it has no open pull request`() {
    val branch = MonitorTargetSelector.CurrentBranch("feat/x")

    assertEquals(MonitorTargetResolution.NoOpenPullRequest, resolve(branch))
    assertEquals(listOf(Triple(ACME, ACME, "feat/x")), source.branchQueries)

    source.branchListing = MonitorPullRequestListing.Complete(listOf(summary(3, headRepository = FORK), summary(4)))
    val open = assertIs<MonitorTargetResolution.Open>(resolve(branch))
    assertEquals(identity(4), open.target.pullRequest)
  }
}
