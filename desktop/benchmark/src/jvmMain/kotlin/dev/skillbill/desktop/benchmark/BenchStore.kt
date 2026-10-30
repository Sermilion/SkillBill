package dev.skillbill.desktop.benchmark

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class LiveRecord(val record: StandalonePhaseStatusRecord)
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
      check(
        record.invocationId == invocationId && record.executionId == expectedExecutionId &&
          record.workflowId == workflowId && record.issueKey == issueKey && record.leaseExpiresAt.isAfter(now),
      ) { "Benchmark registration did not produce the fresh live record '$invocationId'." }
      LiveRecord(record)
    }
  }
}

fun replayActivity(writer: RuntimeComponent, live: List<LiveRecord>): List<LiveRecord> {
  val updated = live.map { liveRecord ->
    writer.databaseSession.transaction { unit ->
      val now = Instant.now()
      unit.worktreeEditJournal.append(
        requireNotNull(liveRecord.record.workflowId),
        WorktreeEditTick(now, "review", WorktreeEditSource.WORKTREE_PROBE, listOf(GoalObservabilityFileDiffStat("src/Bench.kt", 1, 0))),
      )
    }
    writer.databaseSession.transaction { unit ->
      val record = liveRecord.record
      val now = Instant.now()
      val result = unit.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration, "active", "review", "heartbeat", now,
        ),
      )
      check(result == StandalonePhaseStatusUpdateResult.ACCEPTED) {
        "Benchmark heartbeat for '${record.invocationId}' was rejected: $result."
      }
      val current = checkNotNull(unit.standalonePhaseStatuses.readByInvocation(record.invocationId)) {
        "Benchmark heartbeat record '${record.invocationId}' could not be read back."
      }
      check(current.executionId == record.executionId && current.statusRevision != record.statusRevision) {
        "Benchmark heartbeat for '${record.invocationId}' did not advance its execution revision."
      }
      current
    }
  }
  return updated.map(::LiveRecord)
}

fun commitLifecycle(writer: RuntimeComponent, live: List<LiveRecord>, root: Path, namespace: String): LifecycleResult {
  val finished = live.first().record
  val replacementId = "$namespace-live-${UUID.randomUUID()}"
  val replacement = writer.databaseSession.transaction { unit ->
    val now = Instant.now()
    val result = unit.standalonePhaseStatuses.update(
      StandalonePhaseStatusUpdate(
        finished.executionId, finished.statusRevision, finished.leaseOwner, finished.leaseGeneration, "terminal", "review", null,
        now, finishedAt = now, terminalResult = "completed",
      ),
    )
    check(result == StandalonePhaseStatusUpdateResult.ACCEPTED) {
      "Benchmark lifecycle transition for '${finished.invocationId}' was rejected: $result."
    }
    val terminal = checkNotNull(unit.standalonePhaseStatuses.readByInvocation(finished.invocationId)) {
      "Benchmark lifecycle record '${finished.invocationId}' could not be read back."
    }
    check(terminal.executionId == finished.executionId && terminal.lifecycleState == "terminal" && terminal.statusRevision != finished.statusRevision) {
      "Benchmark lifecycle transition for '${finished.invocationId}' did not persist its terminal state."
    }
    val issueKey = "SKILL-BENCH-${namespace.take(8)}-replacement-${UUID.randomUUID()}"
    val registered = unit.standalonePhaseStatuses.register(
      registration(root, namespace, replacementId, issueKey, now, now.plusSeconds(3600)),
    )
    check(
      registered.invocationId == replacementId && registered.executionId == "execution-$replacementId" &&
        registered.workflowId == "bench-workflow-$replacementId" && registered.issueKey == issueKey && registered.leaseExpiresAt.isAfter(now),
    ) {
      "Benchmark replacement '$replacementId' was not registered as a fresh live record."
    }
    registered
  }
  val committedAtNanos = System.nanoTime()
  return LifecycleResult(
    finished.invocationId,
    replacement.invocationId,
    committedAtNanos,
    live.drop(1) + LiveRecord(replacement),
  )
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
