package dev.skillbill.desktop.benchmark

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class LiveRecord(val record: StandalonePhaseStatusRecord, val worker: FeatureTaskRuntimeWorkerOwnership)
data class LifecycleResult(val finishedId: String, val replacementId: String, val committedAtNanos: Long, val live: List<LiveRecord>)

fun runtimeWriter(database: Path, home: Path): RuntimeComponent = RuntimeComponent::class.create(
  RuntimeContext(
    EnvironmentContext(database.toString(), environment = mapOf("SKILL_BILL_TEST" to "1"), userHome = home, repositoryRoot = home),
    TransportContext(),
    WorkflowOpsContext(),
    OptionalCallbacks(),
  ),
)

fun copyStore(source: Path, dir: Path): Path {
  Files.createDirectories(dir)
  val target = dir.resolve("bench.db")
  Class.forName("org.sqlite.JDBC")
  DriverManager.getConnection("jdbc:sqlite:file:${source.toAbsolutePath()}?mode=ro").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'")
    }
  }
  return target
}

fun syntheticStore(dir: Path): Path {
  Files.createDirectories(dir)
  val database = dir.resolve("synthetic.db")
  val writer = runtimeWriter(database, dir)
  val now = Instant.now()
  writer.databaseSession.transaction { unit ->
    val namespace = UUID.randomUUID().toString()
    repeat(12) { index ->
      val invocationId = "bench-live-$index"
      val record = unit.standalonePhaseStatuses.register(
        registration(dir, namespace, invocationId, "SKILL-BENCH-$index", now, now.plusSeconds(3600)),
      )
      check(record.invocationId == invocationId && record.leaseExpiresAt.isAfter(now)) {
        "Synthetic live seed '$invocationId' was not registered with a current lease."
      }
    }
    val terminalNamespace = "${namespace}-terminal"
    repeat(5_000) { index ->
      val startedAt = now.minusSeconds(30)
      val record = unit.standalonePhaseStatuses.register(
        registration(dir, terminalNamespace, "bench-done-$index", "SKILL-DONE-$index", startedAt, startedAt.plusSeconds(3600)),
      )
      val result = unit.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration, "terminal", "review", null,
          now.minusSeconds(1), finishedAt = now.minusSeconds(1), terminalResult = "completed",
        ),
      )
      check(result == StandalonePhaseStatusUpdateResult.ACCEPTED) { "Synthetic terminal seed '$index' was rejected: $result." }
    }
  }
  return database
}

fun registerLive(writer: RuntimeComponent, count: Int, root: Path, namespace: String): List<LiveRecord> {
  val now = Instant.now()
  return writer.databaseSession.transaction { unit ->
    List(count) { index ->
      val invocationId = "$namespace-live-$index"
      val issueKey = "SKILL-BENCH-${namespace.take(8)}-$index"
      val expectedExecutionId = "execution-$invocationId"
      val record = unit.standalonePhaseStatuses.register(
        registration(root, namespace, invocationId, issueKey, now, now.plusSeconds(3600)),
      )
      val workflowId = "bench-workflow-$invocationId"
      val worker = registerBenchmarkWorker(unit, workflowId, issueKey, now)
      check(
        record.invocationId == invocationId && record.executionId == expectedExecutionId &&
          record.workflowId == workflowId && record.issueKey == issueKey && record.leaseExpiresAt.isAfter(now),
      ) { "Benchmark registration did not produce the fresh live record '$invocationId'." }
      LiveRecord(record, worker)
    }
  }
}

fun replayActivity(writer: RuntimeComponent, live: List<LiveRecord>): List<LiveRecord> {
  val updated = live.mapIndexed { index, liveRecord ->
    writer.databaseSession.transaction { unit ->
      val now = Instant.now()
      val worker = liveRecord.worker.copy(heartbeatAt = now.toString(), expiresAt = now.plusSeconds(3600).toString())
      check(unit.workflowStates.heartbeatFeatureTaskRuntimeWorker(worker)) {
        "Benchmark heartbeat for '${liveRecord.record.invocationId}' was rejected."
      }
      unit.worktreeEditJournal.append(
        checkNotNull(liveRecord.record.workflowId),
        WorktreeEditTick(
          recordedAt = now,
          phaseId = "review",
          source = WorktreeEditSource.WORKTREE_PROBE,
          entries = listOf(GoalObservabilityFileDiffStat("src/bench/${liveRecord.record.invocationId}.kt", index + 1, index)),
        ),
      )
      worker
    }
  }
  return live.zip(updated) { previous, worker -> previous.copy(worker = worker) }
}

fun commitLifecycle(writer: RuntimeComponent, live: List<LiveRecord>, root: Path, namespace: String): LifecycleResult {
  val finished = live.first()
  val finishedRecord = finished.record
  val replacementId = "$namespace-live-${UUID.randomUUID()}"
  val replacement = writer.databaseSession.transaction { unit ->
    val now = Instant.now()
    val result = unit.standalonePhaseStatuses.update(
      StandalonePhaseStatusUpdate(
        finishedRecord.executionId, finishedRecord.statusRevision, finishedRecord.leaseOwner, finishedRecord.leaseGeneration, "terminal", "review", null,
        now, finishedAt = now, terminalResult = "completed",
      ),
    )
    check(result == StandalonePhaseStatusUpdateResult.ACCEPTED) {
      "Benchmark lifecycle transition for '${finishedRecord.invocationId}' was rejected: $result."
    }
    val terminal = checkNotNull(unit.standalonePhaseStatuses.readByInvocation(finishedRecord.invocationId)) {
      "Benchmark lifecycle record '${finishedRecord.invocationId}' could not be read back."
    }
    check(terminal.executionId == finishedRecord.executionId && terminal.lifecycleState == "terminal" && terminal.statusRevision != finishedRecord.statusRevision) {
      "Benchmark lifecycle transition for '${finishedRecord.invocationId}' did not persist its terminal state."
    }
    check(unit.workflowStates.releaseFeatureTaskRuntimeWorker(finished.worker.workflowId, finished.worker.ownerToken, finished.worker.generation))
    val issueKey = "SKILL-BENCH-${namespace.take(8)}-replacement-${UUID.randomUUID()}"
    val registered = unit.standalonePhaseStatuses.register(
      registration(root, namespace, replacementId, issueKey, now, now.plusSeconds(3600)),
    )
    val worker = registerBenchmarkWorker(unit, "bench-workflow-$replacementId", issueKey, now)
    check(
      registered.invocationId == replacementId && registered.executionId == "execution-$replacementId" &&
        registered.workflowId == "bench-workflow-$replacementId" && registered.issueKey == issueKey && registered.leaseExpiresAt.isAfter(now),
    ) {
      "Benchmark replacement '$replacementId' was not registered as a fresh live record."
    }
    registered to worker
  }
  val committedAtNanos = System.nanoTime()
  return LifecycleResult(
    finishedRecord.invocationId,
    replacement.first.invocationId,
    committedAtNanos,
    live.drop(1) + LiveRecord(replacement.first, replacement.second),
  )
}

private fun registerBenchmarkWorker(
  unit: skillbill.ports.persistence.UnitOfWork,
  workflowId: String,
  issueKey: String,
  now: Instant,
): FeatureTaskRuntimeWorkerOwnership {
  val row = WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "benchmark-$workflowId",
    workflowName = "bill-feature-task",
    contractVersion = "0.1",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    currentStepId = "review",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = now.toString(),
    updatedAt = null,
    finishedAt = null,
    mode = FeatureTaskWorkflowMode.RUNTIME,
    issueKey = issueKey,
  )
  unit.workflowStates.saveFeatureTaskWorkflow(row, FeatureTaskWorkflowMode.RUNTIME)
  val process = JdkFeatureTaskRuntimeWorkerSupervisor(
    object : RuntimeDiagnostics {
      override fun warning(message: String, error: Throwable?) = Unit
      override fun error(message: String, error: Throwable?) = Unit
    },
    JdkHostPlatformPort,
  ).currentProcess()
  val ownership = FeatureTaskRuntimeWorkerOwnership(
    workflowId, 1, "benchmark-owner-$workflowId", process.hostIdentity, process.bootIdentity, process.pid,
    process.processBirthToken, FeatureTaskRuntimeWorkerLeaseState.ACTIVE, now.toString(), now.plusSeconds(3600).toString(), "review", 1,
  )
  check(unit.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, unit.workflowStates.getFeatureTaskWorkflow(workflowId)?.updatedAt))
  return ownership
}

private fun registration(root: Path, namespace: String, id: String, issue: String, startedAt: Instant, leaseExpiresAt: Instant) =
  StandalonePhaseStatusRegistration(
    "repo-root-realpath-v1:${root.toAbsolutePath()}", "benchmark-$namespace", issue, "bench-workflow-$id", id, "review", "execution-$id", "active", "review",
    startedAt, "benchmark-writer-$namespace", 1, leaseExpiresAt,
    JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
      override fun warning(message: String, error: Throwable?) = Unit
      override fun error(message: String, error: Throwable?) = Unit
    }, JdkHostPlatformPort).currentProcess(),
  )
