package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorWorktree
import skillbill.ports.monitor.model.MonitorWritability
import skillbill.ports.monitor.model.MonitorWriteEvidence
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PhaseRunCancellationTest {
  @Test
  fun `monitor cancellation remains cancelled and is not wrapped as a run failure`() {
    val repository = MonitorRepositoryIdentity("github.com", "example", "repo")
    val tip = MonitorBranchTip(repository, "main", "a".repeat(40))
    val identity = MonitorPullRequestIdentity(repository, 7)
    val head =
      MonitorPullRequestHead(
        tip,
        "https://example.test/fetch",
        "https://example.test/push",
        MonitorWriteEvidence(null, null),
      )
    val context =
      MonitorExecutionContext(
        target =
          MonitorResolvedTarget(
            identity,
            head,
            tip,
            "https://example.test/base",
            MonitorWritability.REPOSITORY_PUSH,
          ),
        callerRoot = Path.of("/repo"),
        primaryRoot = Path.of("/repo"),
        worktree = MonitorWorktree(Path.of("/repo"), "main"),
        ownerToken = "owner",
        generation = 1,
        pinnedBaseSha = tip.sha,
      )
    val cancelled = CancellationException("operator stop")
    val outcome = primaryOutcomeOf(Result.failure(cancelled))

    assertEquals(MonitorOutcomeKind.CANCELLED, outcome.kind)
    assertSame(cancelled, monitorRunFailure(cancelled, context, MonitorSettlement.Released))
  }
}
