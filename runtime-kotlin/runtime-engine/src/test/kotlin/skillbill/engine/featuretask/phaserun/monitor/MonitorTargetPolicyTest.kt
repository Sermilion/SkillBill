package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorWritability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MonitorTargetPolicyTest {
  @Test
  fun `intake selects exactly one target kind or refuses`() {
    assertEquals(
      MonitorIntakeParse.Selected(MonitorTargetSelector.CurrentBranch("feat/x")),
      parseMonitorIntake(null, "feat/x"),
    )
    assertEquals(
      MonitorIntakeParse.Selected(MonitorTargetSelector.IssueKey("SKILL-427")),
      parseMonitorIntake(" skill-427 ", "feat/x"),
    )
    assertEquals(
      MonitorIntakeParse.Selected(MonitorTargetSelector.Url("https://github.com/acme/repo/pull/5")),
      parseMonitorIntake("https://github.com/acme/repo/pull/5", null),
    )
    mapOf(
      parseMonitorIntake("  ", null) to MonitorTargetRefusal.NO_CURRENT_BRANCH,
      parseMonitorIntake("SKILL-1 SKILL-2", "feat/x") to MonitorTargetRefusal.AMBIGUOUS_INTAKE,
      parseMonitorIntake("nonsense", "feat/x") to MonitorTargetRefusal.MALFORMED_INTAKE,
    ).forEach { (parsed, reason) -> assertEquals(reason, assertIs<MonitorIntakeParse.Refused>(parsed).reason) }
  }

  @Test
  fun `only a plain https pull request url is accepted and keeps its own repository`() {
    val parsed = assertIs<MonitorUrlParse.Parsed>(parseMonitorPullRequestUrl("https://GitHub.com/Fork/Repo/pull/9"))
    assertEquals(9, parsed.identity.number)
    assertEquals(FORK, parsed.identity.repository)

    listOf(
      "http://github.com/acme/repo/pull/5",
      "https://github.com/acme/repo/pull/5/files",
      "https://github.com/acme/repo/pull/5?x=1",
      "https://github.com/acme/repo/pull/5#frag",
      "https://github.com/acme/repo/issues/5",
      "https://github.com/acme/repo/pull/five",
      "https://github.com/acme/repo/pull/0",
      "https://user@github.com/acme/repo/pull/5",
      "https://github.com:8443/acme/repo/pull/5",
      "https://github.com/acme//pull/5",
    ).forEach { assertIs<MonitorUrlParse.Rejected>(parseMonitorPullRequestUrl(it), it) }
  }

  @Test
  fun `issue keys match whole tokens in the title, body, or head branch and prefer open pull requests`() {
    assertFalse(summaryMentionsIssueKey(summary(1, title = "SKILL-427 fix"), "SKILL-42"))
    assertFalse(summaryMentionsIssueKey(summary(1, title = "SKILL-427.1 fix"), "SKILL-427"))
    assertTrue(summaryMentionsIssueKey(summary(1, headRef = "feat/SKILL-427-monitor"), "SKILL-427"))
    assertTrue(summaryMentionsIssueKey(summary(1, body = "Closes [skill-427]"), "SKILL-427"))

    val merged = summary(1, title = "SKILL-427", state = MonitorPullRequestState.MERGED)
    val open = summary(2, title = "SKILL-427")
    assertEquals(MonitorCandidateSelection.Unique(open), selectIssueCandidate("SKILL-427", listOf(merged, open)))
    assertEquals(MonitorCandidateSelection.Unique(merged), selectIssueCandidate("SKILL-427", listOf(merged)))
    assertEquals(MonitorCandidateSelection.Absent, selectIssueCandidate("SKILL-9", listOf(merged, open)))
    assertEquals(
      MonitorCandidateSelection.Ambiguous(
        listOf("https://github.com/acme/repo/pull/2", "https://github.com/acme/repo/pull/3"),
      ),
      selectIssueCandidate("SKILL-427", listOf(open, summary(3, headRef = "SKILL-427"))),
    )
  }

  @Test
  fun `a branch selects only an open pull request whose head is that repository and branch`() {
    val ours = summary(1)
    val fork = summary(2, headRepository = FORK)
    val other = summary(3, headRef = "feat/y")
    val closed = summary(4, state = MonitorPullRequestState.CLOSED)

    val all = listOf(ours, fork, other, closed)
    assertEquals(MonitorCandidateSelection.Unique(ours), selectBranchCandidate(all, ACME, "feat/x"))
    assertEquals(MonitorCandidateSelection.Unique(fork), selectBranchCandidate(all, FORK, "feat/x"))
    assertEquals(MonitorCandidateSelection.Absent, selectBranchCandidate(listOf(other, closed), ACME, "feat/x"))
    assertIs<MonitorCandidateSelection.Ambiguous>(selectBranchCandidate(listOf(ours, summary(5)), ACME, "feat/x"))
  }

  @Test
  fun `an open target needs head, base, endpoint, and write evidence before it is monitorable`() {
    val open = assertIs<MonitorTargetResolution.Open>(validatedOpenTarget(pullRequest(1))).target
    assertEquals(MonitorWritability.REPOSITORY_PUSH, open.writability)
    assertEquals("https://github.com/acme/repo.git", open.baseFetchUrl)

    val maintainer = validatedOpenTarget(pullRequest(1, head = head(FORK, push = false, maintainerCanModify = true)))
    assertEquals(
      MonitorWritability.MAINTAINER_CAN_MODIFY,
      assertIs<MonitorTargetResolution.Open>(maintainer).target.writability,
    )

    val incomplete =
      listOf(
        pullRequest(1, head = null),
        pullRequest(1, base = null),
        pullRequest(1, head = head(ref = "--upload-pack=evil")),
        pullRequest(1, head = head(ref = "a..b")),
        pullRequest(1, head = head(sha = "abc")),
        pullRequest(1, head = head(url = "https://evil.example/acme/repo.git")),
        pullRequest(1, head = head(push = null)),
        pullRequest(1, head = head(FORK, push = false, maintainerCanModify = null)),
      )
    val notWritable =
      listOf(
        pullRequest(1, head = head(FORK, push = false, maintainerCanModify = false)),
        pullRequest(1, head = head(push = false)),
      )
    assertRefused(MonitorTargetRefusal.INCOMPLETE_TARGET, incomplete)
    assertRefused(MonitorTargetRefusal.NOT_WRITABLE, notWritable)
  }

  private fun assertRefused(
    reason: MonitorTargetRefusal,
    candidates: List<MonitorPullRequest>,
  ) {
    candidates.forEach { candidate ->
      val outcome = assertIs<MonitorTargetResolution.Refused>(validatedOpenTarget(candidate), candidate.toString())
      assertEquals(reason, outcome.reason, candidate.toString())
    }
  }
}
