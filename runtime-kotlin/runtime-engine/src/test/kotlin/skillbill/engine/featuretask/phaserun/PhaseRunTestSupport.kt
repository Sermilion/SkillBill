package skillbill.engine.featuretask.phaserun

import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.contracts.JsonCodec
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.monitor.MonitorTargetResolver
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.EnabledRuntimeTelemetrySettingsProvider
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.runner.SlotBaselineTestResources
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.UnavailableStandaloneMonitorService
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.infrastructure.workflow.featuretask.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorFetchedRefs
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorSettleRequest
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.monitor.model.MonitorWorktree
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val DURABLE_WORKFLOW_TABLES =
  listOf(
    "feature_task_workflows",
    "feature_task_runtime_sessions",
    "feature_task_phase_settlements",
    "feature_task_execution_identities",
  )

internal fun phaseRunDatabase(
  home: Path,
  clock: Clock,
): DatabaseSessionFactory =
  sqliteSessionFactoryForTests(
    userHome = home,
    dbPathOverride = home.resolve("metrics.db").toString(),
    environment = emptyMap(),
    clock = clock,
  ).also { database ->
    database.transaction { }
  }

internal fun phaseRunEntry(
  strategies: PhaseStrategyLookup,
  gitOperations: WorkflowGitOperations,
  database: DatabaseSessionFactory,
  clock: Clock,
  runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
): PhaseRunEntry = PhaseRunEntryFixture(strategies, gitOperations, database, clock, runLoopEntry).entry()

internal data class PhaseRunEntryFixture(
  val strategies: PhaseStrategyLookup,
  val gitOperations: WorkflowGitOperations,
  val database: DatabaseSessionFactory,
  val clock: Clock,
  val runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  val standaloneMonitorPreparer: StandaloneMonitorExecutionPreparer = refusingMonitorPreparer(),
)

internal fun PhaseRunEntryFixture.entry(): PhaseRunEntry =
  PhaseRunEntry(
    statusPublisherFactory =
      testStandalonePhasePublisher(database, clock, NoopRuntimeDiagnostics),
    strategies = strategies,
    gitOperations = gitOperations,
    reviewResultAssembly =
      ParallelCodeReviewRunnerResultAssembly(
        GoalRunnerSubtaskLauncher { error("A phase run must not launch an integration pass.") },
        ReviewContextEnvelopeValidator { _, _ -> },
        RuntimeOwnedPersistenceBoundary(database, NoopRuntimeDiagnostics),
        clock,
      ),
    lifecycleTelemetry =
      LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider, clock, NoopRuntimeDiagnostics),
    diagnostics = NoopRuntimeDiagnostics,
    clock = clock,
    intakeResolver =
      PhaseRunIntakeResolver(FileSystemFeatureSpecPathResolver(), FileSystemFeatureTaskRuntimeRunInvariantsSource()),
    runLoopEntry = runLoopEntry,
    agentRunLauncher =
      object : AgentRunLauncher {
        override fun resolveLaunchModel(request: AgentRunLaunchModelRequest) = passThroughResolvedLaunchModel(request)

        override fun launch(request: AgentRunLaunchRequest) =
          error("A phase run fixture must not resolve launches through the test support launcher.")
      },
    standaloneMonitorPreparer = standaloneMonitorPreparer,
  )

internal fun refusingMonitorPreparer(
  resources: StandaloneMonitorResources = UnreachableMonitorResources(),
): StandaloneMonitorExecutionPreparer =
  StandaloneMonitorExecutionPreparer(
    MonitorTargetResolver(UnavailableStandaloneMonitorService.source),
    resources,
    NoopRuntimeDiagnostics,
    object : ShutdownHookPort {
      override fun register(action: () -> Unit): ShutdownHookRegistration = ShutdownHookRegistration { true }
    },
    AgentRunChildDrain { true },
  )

internal open class UnreachableMonitorResources : StandaloneMonitorResources {
  override fun resolveContext(callerRoot: Path): MonitorContextResolution =
    MonitorContextResolution.Unavailable("test runs have no caller repository")

  override fun acquire(request: MonitorAcquireRequest): MonitorAcquisition = unreachable("acquire")

  override fun fetchPinned(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
  ): MonitorFetchResult = unreachable("fetchPinned")

  override fun createWorktree(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    refs: MonitorFetchedRefs,
  ): MonitorWorktree = unreachable("createWorktree")

  override fun markPrepared(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    worktree: MonitorWorktree,
  ) = unreachable("markPrepared")

  override fun adoptHead(
    fence: MonitorFence,
    headSha: String,
  ): String? = unreachable("adoptHead")

  override fun settle(request: MonitorSettleRequest): MonitorSettlement = unreachable("settle")

  override fun recoverStale(request: MonitorStaleRecoveryRequest): MonitorStaleRecovery = unreachable("recoverStale")

  private fun unreachable(operation: String): Nothing =
    error("$operation must not run when the monitor context cannot be resolved.")
}

internal fun DatabaseSessionFactory.assertNoDurableWorkflowState() {
  DURABLE_WORKFLOW_TABLES.forEach { table ->
    assertEquals(emptyList(), SlotBaselineSqlite.rows(resolveDbPath(), table), "$table must stay empty")
  }
}

internal fun RecordingWorkflowGitOperations.assertNoCommitOrCheckpointRef(headBefore: String) {
  assertEquals(headBefore, headCommitShaValue, "HEAD must not move")
  assertEquals(emptyList(), createCommitMessages, "no commit may be created")
  assertEquals(emptyList(), amendCommitMessages, "no commit may be amended")
  assertEquals(emptyList(), resetSoftToCommitCalls, "no commit may be rewound")
  assertTrue(checkpointRefs.isEmpty(), "no checkpoint ref may be written: $checkpointRefs")
  assertEquals(emptyList(), updateCheckpointRefCalls, "no checkpoint ref may be updated")
}

internal fun DatabaseSessionFactory.outboxPayloads(eventName: String): List<Map<String, Any?>> =
  SlotBaselineSqlite.rows(resolveDbPath(), "telemetry_outbox")
    .filter { row -> row["event_name"] == eventName }
    .map { row -> requireNotNull(JsonCodec.anyToStringAnyMap(row["payload_json"])) }

internal fun DatabaseSessionFactory.assertOnlyOutboxEvents(allowedEventNames: Set<String>) {
  val rows = SlotBaselineSqlite.rows(resolveDbPath(), "telemetry_outbox")
  assertEquals(allowedEventNames, allowedEventNames + rows.map { row -> row["event_name"] as String }, "outbox events")
  rows.forEach { row ->
    val payload = requireNotNull(JsonCodec.anyToStringAnyMap(row["payload_json"]))
    assertTrue("workflow_id" !in payload, "a phase run payload must carry no workflow_id: $payload")
  }
}

internal fun slotBaselineFixture(relativePath: String): Any? =
  JsonCodec.parseValue(Files.readString(SlotBaselineTestResources.resolve(relativePath)))
