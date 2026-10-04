package skillbill.engine.goalrunner.status

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.lease
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.goalTestPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val STOP_NOW: Instant = Instant.parse("2026-08-07T12:00:00Z")

class GoalRunnerStopVerbTest {
  @Test
  fun `stopping a live goal terminates its runner and reports stopped`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    val result = stopService(store, supervisor).stop("SKILL-168", Path.of("."))

    assertEquals(GoalRunnerStopStatus.STOPPED, result.status)
    assertTrue(result.terminationAttempted)
    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, store.controlStateValue.pauseReason)
    assertTrue(store.controlStateValue.paused)
  }

  @Test
  fun `an unknown issue key reports not found and writes nothing`() {
    val store = StopFakeManifestStore(loaded = false)

    val result =
      stopService(store, RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive))
        .stop("SKILL-404", Path.of("."))

    assertEquals(GoalRunnerStopStatus.NOT_FOUND, result.status)
    assertEquals(0, store.pauseNowCalls)
  }

  @Test
  fun `a goal with no lease records the stop and reports no live lease without terminating`() {
    val store = StopFakeManifestStore(lease = null)
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    val result = stopService(store, supervisor).stop("SKILL-168", Path.of("."))

    assertEquals(GoalRunnerStopStatus.NO_LIVE_LEASE, result.status)
    assertFalse(result.terminationAttempted)
    assertEquals(emptyList(), supervisor.calls)
    assertTrue(store.controlStateValue.paused)
  }

  @Test
  fun `an expired lease whose process the supervisor still confirms live is terminated`() {
    val store = StopFakeManifestStore(lease = liveLease().copy(expiresAt = Instant.parse("2026-08-07T11:00:00Z")))
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.STOPPED, result.status)
    assertTrue(result.terminationAttempted)
    assertTrue(supervisor.calls.contains("terminateGracefully"), "calls were ${supervisor.calls}")
  }

  @Test
  fun `an expired lease whose process is gone reports no live lease`() {
    val store = StopFakeManifestStore(lease = liveLease().copy(expiresAt = Instant.parse("2026-08-07T11:00:00Z")))
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.NO_LIVE_LEASE, result.status)
    assertFalse(result.terminationAttempted)
  }

  @Test
  fun `a goal already carrying an operator stop with no live lease reports already stopped`() {
    val store =
      StopFakeManifestStore(
        lease = null,
        control =
          GoalRunnerControlState(
            pauseRequested = true,
            pauseConsumed = true,
            paused = true,
            pauseReason = GOAL_PAUSE_REASON_OPERATOR_STOP,
            pausedAt = "2026-08-07T11:00:00Z",
          ),
      )
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.ALREADY_STOPPED, result.status)
    assertEquals(emptyList(), supervisor.calls)
  }

  @Test
  fun `a second stop makes no second termination attempt`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    val service = stopService(store, supervisor)

    assertEquals(GoalRunnerStopStatus.STOPPED, service.stop("SKILL-168", null).status)
    store.lease = null
    supervisor.calls.clear()

    val second = service.stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.ALREADY_STOPPED, second.status)
    assertFalse(second.terminationAttempted)
    assertEquals(emptyList(), supervisor.calls)
  }

  @Test
  fun `an ownership mismatch refuses to terminate and reports the refusal`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor =
      RecordingSupervisor(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("lease was recorded on another boot"),
      )

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.IDENTITY_MISMATCH, result.status)
    assertFalse(result.terminationAttempted)
    assertEquals(listOf("inspect"), supervisor.calls)
  }

  @Test
  fun `an unsupported inspection refuses to terminate`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.Unsupported("no liveness inspection"))

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.IDENTITY_MISMATCH, result.status)
    assertEquals(listOf("inspect"), supervisor.calls)
  }

  @Test
  fun `the no-op supervisor never terminates anything`() {
    val store = StopFakeManifestStore(lease = liveLease())

    val result =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        clock = stopClock(),
        ports =
          GoalRunnerStatusTestPorts(
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
      ).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.IDENTITY_MISMATCH, result.status)
    assertFalse(result.terminationAttempted)
  }

  @Test
  fun `graceful termination is attempted strictly before forcible termination`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    stopService(store, supervisor).stop("SKILL-168", null)

    val graceful = supervisor.calls.indexOf("terminateGracefully")
    val forcible = supervisor.calls.indexOf("terminateForcibly")
    assertTrue(graceful >= 0 && forcible > graceful, "calls were ${supervisor.calls}")
  }

  @Test
  fun `forcible termination is skipped once the runner is gone`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    supervisor.inspectionAfterGraceful = FeatureTaskRuntimeProcessInspection.NotRunning

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertEquals(GoalRunnerStopStatus.STOPPED, result.status)
    assertFalse(supervisor.calls.contains("terminateForcibly"), "calls were ${supervisor.calls}")
  }

  @Test
  fun `the durable stop survives a supervisor whose graceful termination throws`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive, throwOnTerminate = true)

    val result = stopService(store, supervisor).stop("SKILL-168", null)

    assertTrue(result.terminationAttempted)
    assertTrue(store.controlStateValue.paused)
    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, store.controlStateValue.pauseReason)
    assertEquals(STOP_NOW.toString(), store.controlStateValue.pausedAt)
  }

  @Test
  fun `the durable stop survives a supervisor whose termination returns false`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)

    stopService(store, supervisor).stop("SKILL-168", null)

    assertTrue(store.controlStateValue.paused)
    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, store.controlStateValue.pauseReason)
  }

  @Test
  fun `the durable stop is written before any supervisor contact`() {
    val store = StopFakeManifestStore(lease = liveLease())
    val supervisor = RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    supervisor.onFirstCall = { assertTrue(store.controlStateValue.paused, "intent must land before termination") }

    stopService(store, supervisor).stop("SKILL-168", null)

    assertTrue(supervisor.calls.isNotEmpty())
  }

  @Test
  fun `the pause timestamp comes from the injected clock`() {
    val store = StopFakeManifestStore(lease = liveLease())

    val result =
      stopService(store, RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive))
        .stop("SKILL-168", null)

    assertEquals(STOP_NOW.toString(), result.pausedAt)
    assertEquals(STOP_NOW.toString(), store.controlStateValue.pausedAt)
  }

  @Test
  fun `a stop overwrites a plain operator pause reason with the more specific operator stop`() {
    val store =
      StopFakeManifestStore(
        lease = liveLease(),
        control =
          GoalRunnerControlState(
            pauseRequested = true,
            pauseConsumed = true,
            paused = true,
            pauseReason = GOAL_PAUSE_REASON_OPERATOR_REQUEST,
            pausedAt = "2026-08-07T11:00:00Z",
          ),
      )

    stopService(store, RecordingSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)).stop("SKILL-168", null)

    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, store.controlStateValue.pauseReason)
    assertEquals(STOP_NOW.toString(), store.controlStateValue.pausedAt)
  }

  private fun stopService(
    store: StopFakeManifestStore,
    supervisor: FeatureTaskRuntimeWorkerSupervisor,
  ) = testGoalRunnerStatusService(
    manifestStore = store,
    outcomeStore = RecordingOutcomeStore(),
    phaseRecorder = goalTestPhaseRecorder(),
    clock = stopClock(),
    ports =
      GoalRunnerStatusTestPorts(
        workerSupervisor = supervisor,
      ),
  )
}

private fun stopClock(): Clock = Clock.fixed(STOP_NOW, ZoneOffset.UTC)

internal fun liveLease() =
  GoalRunnerExecutionLease(
    generation = 1,
    ownerToken = "owner-token-123456",
    hostIdentity = "host",
    bootIdentity = "boot",
    pid = 4242,
    processBirthToken = "birth-4242",
    heartbeatAt = "2026-08-07T11:59:50Z",
    expiresAt = "2026-08-07T12:00:20Z",
  )

private class StopFakeManifestStore(
  var lease: GoalRunnerExecutionLease? = null,
  control: GoalRunnerControlState = GoalRunnerControlState(),
  private val loaded: Boolean = true,
) : GoalRunnerManifestStoreDefaults() {
  var controlStateValue: GoalRunnerControlState = control
    private set
  var pauseNowCalls: Int = 0
    private set

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? {
    if (!loaded) return null
    return GoalRunnerManifestState(
      parentWorkflowId = "goal-parent-1",
      dbPath = "/fake/goal.db",
      manifest =
        DecompositionManifest(
          issueKey = issueKey,
          featureName = "goal-stop",
          parentSpecPath = ".feature-specs/$issueKey/spec.md",
          baseBranch = "main",
          featureBranch = "feat/$issueKey",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
          subtasks = listOf(DecompositionSubtask(id = 1, name = "One", specPath = "spec_1.md", status = "in_progress")),
        ),
      controlState = controlStateValue,
    )
  }

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = controlStateValue

  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = lease

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState {
    pauseNowCalls += 1
    if (controlStateValue.paused && !overwriteExistingReason) return controlStateValue
    controlStateValue =
      controlStateValue.copy(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = reason,
        pausedAt = pausedAt,
      )
    return controlStateValue
  }

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = false
}

private class RecordingSupervisor(
  private val inspection: FeatureTaskRuntimeProcessInspection,
  private val throwOnTerminate: Boolean = false,
) : FeatureTaskRuntimeWorkerSupervisor {
  val calls: MutableList<String> = mutableListOf()
  var inspectionAfterGraceful: FeatureTaskRuntimeProcessInspection? = null
  var onFirstCall: (() -> Unit)? = null

  private fun record(name: String) {
    if (calls.isEmpty()) onFirstCall?.invoke()
    calls.add(name)
  }

  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 1, "birth")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    record("inspect")
    return if (calls.contains("terminateGracefully")) inspectionAfterGraceful ?: inspection else inspection
  }

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    record("terminateGracefully")
    if (throwOnTerminate) error("supervisor could not signal the process")
    return false
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    record("terminateForcibly")
    if (throwOnTerminate) error("supervisor could not kill the process")
    return false
  }

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}
