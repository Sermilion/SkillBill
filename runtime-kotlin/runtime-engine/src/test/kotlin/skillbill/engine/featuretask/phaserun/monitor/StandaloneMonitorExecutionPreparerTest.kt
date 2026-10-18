package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorAdmissionBlock
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorExpectedOwnerToken
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchRejection
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorFetchedRefs
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorRecoveryRefusal
import skillbill.ports.monitor.model.MonitorRepositoryContext
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettleRequest
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.monitor.model.MonitorWorktree
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val PR_URL = "https://github.com/acme/repo/pull/5"
private val CALLER_ROOT: Path = Path.of("/caller")
private val FENCE_DIRECTORY: Path = Path.of("/common/skill-bill/standalone-monitor/target")

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private fun MonitorCleanupOwnership.settleCompleted(): MonitorSettlement =
  settle(MonitorPrimaryOutcome(MonitorOutcomeKind.COMPLETED, "completed"))

private class RecordingResources : StandaloneMonitorResources {
  val calls = mutableListOf<String>()
  var context: MonitorContextResolution =
    MonitorContextResolution.Resolved(MonitorRepositoryContext(CALLER_ROOT, CALLER_ROOT, Path.of("/common")))
  var blockAcquisitionWith: MonitorAdmissionBlock? = null
  var fetch: MonitorFetchResult =
    MonitorFetchResult.Fetched(
      MonitorFetchedRefs("refs/head", "refs/base"),
      "b".repeat(40),
    )
  var recovery: MonitorStaleRecovery = MonitorStaleRecovery.Recovered("old-token")
  var settlement: MonitorSettlement = MonitorSettlement.Released
  var failure: Pair<String, Throwable>? = null
  var settleFailure: Throwable? = null
  var acquiredToken: String? = null

  override fun resolveContext(callerRoot: Path): MonitorContextResolution = step("resolveContext") { context }

  override fun acquire(request: MonitorAcquireRequest): MonitorAcquisition =
    step("acquire") {
      acquiredToken = request.ownerToken
      val blocked = blockAcquisitionWith
      if (blocked == null) {
        MonitorAcquisition.Acquired(MonitorFence(FENCE_DIRECTORY, request.ownerToken, 3))
      } else {
        MonitorAcquisition.Blocked(blocked, FENCE_DIRECTORY, "owned elsewhere", null)
      }
    }

  override fun fetchPinned(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
  ): MonitorFetchResult = step("fetchPinned") { fetch }

  override fun createWorktree(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    refs: MonitorFetchedRefs,
  ): MonitorWorktree =
    step("createWorktree") { MonitorWorktree(Path.of("/wt"), "skill-bill/monitor/${fence.ownerToken}") }

  override fun markPrepared(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    worktree: MonitorWorktree,
  ) = step("markPrepared") { Unit }

  override fun settle(request: MonitorSettleRequest): MonitorSettlement =
    step("settle") {
      settleFailure?.let { throw it }
      settlement
    }

  override fun recoverStale(request: MonitorStaleRecoveryRequest): MonitorStaleRecovery =
    step("recoverStale") { recovery }

  private fun <T> step(
    name: String,
    body: () -> T,
  ): T {
    calls += name
    failure?.takeIf { it.first == name }?.let { throw it.second }
    return body()
  }
}

private class RecordingShutdownHooks : ShutdownHookPort {
  val registered = mutableListOf<() -> Unit>()

  override fun register(action: () -> Unit): ShutdownHookRegistration {
    registered += action
    return ShutdownHookRegistration { registered.remove(action) }
  }
}

class StandaloneMonitorExecutionPreparerTest {
  private val source = FakeMonitorPullRequestSource(lookup = { MonitorPullRequestLookup.Found(pullRequest(it)) })
  private val resources = RecordingResources()
  private val diagnostics = RecordingDiagnostics()
  private val shutdownHooks = RecordingShutdownHooks()
  private val preparer =
    StandaloneMonitorExecutionPreparer(
      MonitorTargetResolver(source),
      resources,
      diagnostics,
      shutdownHooks,
      AgentRunChildDrain { true },
    )

  private fun prepare(
    intake: String? = PR_URL,
    expectedStaleOwner: MonitorExpectedOwnerToken? = null,
  ) = preparer.prepare(MonitorPreparationRequest(CALLER_ROOT, intake, "feat/x", expectedStaleOwner))

  @Test
  fun `a prepared invocation hands back an execution context and keeps its resources until settled`() {
    val prepared = assertIs<MonitorPreparation.Prepared>(prepare())

    assertEquals(
      listOf("resolveContext", "acquire", "fetchPinned", "createWorktree", "markPrepared"),
      resources.calls,
    )
    assertEquals(resources.acquiredToken, prepared.context.ownerToken)
    assertEquals(3, prepared.context.generation)
    assertEquals(identity(5), prepared.context.target.pullRequest)
    assertEquals(Path.of("/wt"), prepared.context.worktree.path)
    assertEquals(CALLER_ROOT, prepared.context.callerRoot)

    assertEquals(MonitorSettlement.Released, prepared.cleanup.settleCompleted())
  }

  @Test
  fun `runtime exit settles a handed-off invocation through its shutdown hook and settling unregisters it`() {
    val prepared = assertIs<MonitorPreparation.Prepared>(prepare())
    val exitHook = shutdownHooks.registered.single()

    exitHook()

    assertEquals(1, resources.calls.count { it == "settle" })
    assertTrue(shutdownHooks.registered.isEmpty())
    assertEquals(MonitorSettlement.Released, prepared.cleanup.settleCompleted())
    assertEquals(1, resources.calls.count { it == "settle" })
  }

  @Test
  fun `settling twice releases the fence once and a retained outcome is reported`() {
    resources.settlement = MonitorSettlement.Retained(MonitorRetentionReason.DIRTY_WORKTREE, Path.of("/wt"), "edits")
    val prepared = assertIs<MonitorPreparation.Prepared>(prepare())

    val first = prepared.cleanup.settleCompleted()
    val second = prepared.cleanup.settleCompleted()

    assertSame(first, second)
    assertEquals(1, resources.calls.count { it == "settle" })
    assertTrue(diagnostics.warnings.single().contains("dirty_worktree"))
  }

  @Test
  fun `a failing settle is recorded as a retained cleanup failure rather than thrown`() {
    resources.settleFailure = IllegalStateException("disk gone")
    val prepared = assertIs<MonitorPreparation.Prepared>(prepare())

    val settled = assertIs<MonitorSettlement.Retained>(prepared.cleanup.settleCompleted())

    assertEquals(MonitorRetentionReason.CLEANUP_FAILED, settled.reason)
    assertEquals("disk gone", settled.detail)
  }

  @Test
  fun `a failure after admission settles once and the primary error survives a secondary cleanup failure`() {
    val primary = IllegalStateException("worktree add failed")
    resources.failure = "createWorktree" to primary
    resources.settleFailure = IllegalStateException("cleanup also failed")

    val thrown = assertFailsWith<IllegalStateException> { prepare() }

    assertSame(primary, thrown)
    assertEquals(listOf("resolveContext", "acquire", "fetchPinned", "createWorktree", "settle"), resources.calls)
    assertTrue(diagnostics.warnings.single().contains("cleanup_failed"))
  }

  @Test
  fun `cancellation during setup still settles the admitted ownership`() {
    val cancellation = CancellationException("runtime exiting")
    resources.failure = "fetchPinned" to cancellation

    val thrown = assertFailsWith<CancellationException> { prepare() }

    assertSame(cancellation, thrown)
    assertEquals(1, resources.calls.count { it == "settle" })
    assertTrue("createWorktree" !in resources.calls)
  }

  @Test
  fun `a rejected fetch blocks with its kind and settles the admitted ownership`() {
    resources.fetch = MonitorFetchResult.Rejected(MonitorFetchRejection.HEAD_MOVED, "head moved to abc")

    val blocked = assertIs<MonitorPreparation.Blocked>(prepare())

    assertEquals(MonitorBlockKind.HEAD_MOVED, blocked.kind)
    assertEquals(identity(5), blocked.pullRequest)
    assertEquals(listOf("resolveContext", "acquire", "fetchPinned", "settle"), resources.calls)
  }

  @Test
  fun `a live owner blocks admission without creating or settling anything`() {
    resources.blockAcquisitionWith = MonitorAdmissionBlock.LIVE_OWNER

    val blocked = assertIs<MonitorPreparation.Blocked>(prepare())

    assertEquals(MonitorBlockKind.LIVE_OWNER, blocked.kind)
    assertEquals(FENCE_DIRECTORY, blocked.path)
    assertEquals(listOf("resolveContext", "acquire"), resources.calls)
  }

  @Test
  fun `explicit stale recovery runs before admission and a refusal stops preparation`() {
    val expected = MonitorExpectedOwnerToken("old-token")
    resources.recovery = MonitorStaleRecovery.Refused(MonitorRecoveryRefusal.LIVE_OWNER, FENCE_DIRECTORY, "still alive")

    val refused = assertIs<MonitorPreparation.Blocked>(prepare(expectedStaleOwner = expected))

    assertEquals(MonitorBlockKind.RECOVERY_REFUSED, refused.kind)
    assertEquals(listOf("resolveContext", "recoverStale"), resources.calls)

    resources.calls.clear()
    resources.recovery = MonitorStaleRecovery.Recovered("old-token")
    assertIs<MonitorPreparation.Prepared>(prepare(expectedStaleOwner = expected)).cleanup.settleCompleted()
    assertEquals("recoverStale", resources.calls[1])
    assertEquals("acquire", resources.calls[2])
  }

  @Test
  fun `targets that complete, refuse, or fail never reach admission`() {
    source.lookup = { MonitorPullRequestLookup.Found(pullRequest(it, state = MonitorPullRequestState.MERGED)) }
    assertEquals(MonitorPreparation.Completed(identity(5)), prepare())

    source.lookup = { MonitorPullRequestLookup.Found(pullRequest(it, state = MonitorPullRequestState.CLOSED)) }
    val closed = assertIs<MonitorPreparation.Blocked>(prepare())
    assertEquals(MonitorBlockKind.CLOSED_UNMERGED, closed.kind)
    assertEquals(identity(5), closed.pullRequest)

    assertEquals(MonitorPreparation.Completed(null), prepare(intake = null))

    assertIs<MonitorPreparation.Refused>(prepare(intake = "not a target"))

    source.lookup = { MonitorPullRequestLookup.Failed("rate limited") }
    assertEquals(MonitorPreparation.Failed("rate limited"), prepare())

    resources.context = MonitorContextResolution.Unavailable("not a repository")
    val unavailable = assertIs<MonitorPreparation.Refused>(prepare())
    assertEquals(MonitorTargetRefusal.CALLER_REPOSITORY_UNAVAILABLE, unavailable.reason)

    assertTrue(resources.calls.none { it == "acquire" || it == "settle" })
  }
}
