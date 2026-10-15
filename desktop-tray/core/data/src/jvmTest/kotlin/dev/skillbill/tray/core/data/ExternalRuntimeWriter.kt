package dev.skillbill.tray.core.data

import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs [ExternalRuntimeWriter] in a child JVM, so the store is written by a process that shares nothing with the test. */
fun runExternalWriter(path: Path, mode: String) {
  val process = ProcessBuilder(
    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    "-cp", System.getProperty("java.class.path"), ExternalRuntimeWriter::class.java.name, path.toString(), mode,
  ).redirectErrorStream(true).start()
  assertTrue(process.waitFor(10, TimeUnit.SECONDS))
  assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
}

object ExternalRuntimeWriter {
  @JvmStatic fun main(args: Array<String>) {
    val path = Path.of(args[0])
    val mode = args[1]
    val factory = sqliteSessionFactoryForTests(path.parent, path.toString(), emptyMap())
    val now = Instant.now()
    val processOwner = if (mode == "owned-phase") {
      JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
      }, JdkHostPlatformPort).currentProcess()
    } else null
    try {
      if (mode == "activity") {
        factory.selfManagedWrite { unit ->
          unit.agentActivityStamps.record("commit", AgentActivityStamp(now, AgentActivityLabel.STDOUT))
        }
        return
      }
      factory.transaction { unit ->
        if (mode == "unrelated-commit") {
          val connection = unit::class.java.getDeclaredField("connection").apply { isAccessible = true }
            .get(unit) as java.sql.Connection
          connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE unrelated_desktop_signal_probe (id TEXT PRIMARY KEY)")
            statement.execute("INSERT INTO unrelated_desktop_signal_probe VALUES ('committed')")
          }
          return@transaction
        }
        if (mode == "goal-commit" || mode == "coherent-commit") {
          unit.goalRunnerControls.persistControlState(mode, GoalRunnerControlState(pauseRequested = true))
          if (mode == "goal-commit") return@transaction
        }
        if (mode == "complete") {
          val record = requireNotNull(unit.standalonePhaseStatuses.readByInvocation("commit"))
          unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
            record.leaseOwner, record.leaseGeneration, "terminal", "review", null, now, finishedAt = now, terminalResult = "completed"))
          return@transaction
        }
        unit.standalonePhaseStatuses.register(StandalonePhaseStatusRegistration(
          "repo-root-realpath-v1:${path.parent}", "main", "SKILL-423", null, mode, "review", "execution-$mode", "active", "review",
          now, "owned-test", 1, now.plusSeconds(300), processOwner = processOwner))
        if (mode == "rollback") error("owned rollback")
      }
      if (mode == "crash-after-commit") Runtime.getRuntime().halt(0)
      if (mode == "owned-phase") {
        println("registered")
        System.out.flush()
        System.`in`.read()
      }
    } catch (error: IllegalStateException) {
      if (mode != "rollback") throw error
    }
  }
}
