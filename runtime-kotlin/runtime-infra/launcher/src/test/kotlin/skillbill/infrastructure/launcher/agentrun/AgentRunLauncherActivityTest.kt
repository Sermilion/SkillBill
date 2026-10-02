package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunActivityProbe
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherActivityTest {
  @Test
  fun `declared live long operation survives past former idle window and is classified working`() {
    var sequence = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.8"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          progressIdleTimeout = 100.milliseconds
          operationDeadline = 10.seconds
          declaredProgressProbe =
            AgentRunDeclaredProgressProbe {
              AgentRunDeclaredProgressSnapshot(
                latestEvent =
                  GoalProgressEvent(
                    eventKind = GoalProgressEventKind.OPERATION_STARTED,
                    workflowId = "wfl-child",
                    workflowPhase = "validate",
                    processAlive = true,
                    sequenceNumber = sequence,
                    timestamp = "2026-06-02T10:0${sequence++}:00Z",
                    operationName = "gradlew check",
                    expectedLong = true,
                  ),
                processAlive = true,
              )
            }
        },
      )

    assertFalse(result.timedOut, "declared live long op must not be killed by the idle timeout")
    assertEquals(0, result.exitStatus)
  }

  @Test
  fun `operation observed only via heartbeat anchors its deadline to first observation not process start`() {
    val withheldPolls = WITHHELD_POLLS
    var probeCalls = 0
    var sequence = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 2"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 5.seconds
          operationDeadline = 1.seconds
          declaredProgressProbe =
            AgentRunDeclaredProgressProbe {
              if (probeCalls++ < withheldPolls) {
                null
              } else {
                AgentRunDeclaredProgressSnapshot(
                  latestEvent =
                    GoalProgressEvent(
                      eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
                      workflowId = "wfl-child",
                      workflowPhase = "validate",
                      processAlive = true,
                      sequenceNumber = sequence,
                      timestamp = "2026-06-02T10:0${sequence++}:00Z",
                      operationName = "gradlew check",
                      expectedLong = true,
                    ),
                  processAlive = true,
                )
              }
            }
        },
      )

    assertFalse(
      result.timedOut,
      "heartbeat-only long op must not be killed: its deadline anchors to first observation, " +
        "not process start (liveness=${result.liveness})",
    )
    assertEquals(0, result.exitStatus)
    assertNotEquals(
      GoalRunnerLivenessState.UNRESPONSIVE,
      result.liveness?.livenessState,
      "process-start anchoring would have produced an operation_deadline_overrun kill",
    )
  }

  @Test
  fun `declared dead process produces a deterministic unresponsive kill`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 5.seconds
          progressIdleTimeout = 5.seconds
          operationDeadline = 50.milliseconds
          declaredProgressProbe =
            AgentRunDeclaredProgressProbe {
              AgentRunDeclaredProgressSnapshot(
                latestEvent =
                  GoalProgressEvent(
                    eventKind = GoalProgressEventKind.OPERATION_STARTED,
                    workflowId = "wfl-child",
                    workflowPhase = "implement",
                    processAlive = true,
                    sequenceNumber = 1,
                    timestamp = "2026-06-02T10:00:00Z",
                    operationName = "gradlew check",
                    expectedLong = true,
                  ),
                processAlive = true,
              )
            }
        },
      )

    assertTrue(result.timedOut, "operation deadline overrun must produce a deterministic kill")
    assertEquals(GoalRunnerLivenessState.UNRESPONSIVE, result.liveness?.livenessState)
  }

  @Test
  fun `process lifecycle drives declared operation events without phase agent self-report`() {
    val emissions = mutableListOf<AgentRunProgressEmission>()
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.35"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          statusHeartbeatInterval = 100.milliseconds
          progressEmitter = AgentRunProgressEmitter { emissions += it }
        },
      )

    assertEquals(0, result.exitStatus)
    val started = emissions.first()
    assertEquals(GoalProgressEventKind.OPERATION_STARTED, started.eventKind)
    assertEquals("child_agent_run", started.operationName)
    assertEquals("long_child_run", started.operationKind)
    assertTrue(started.expectedLong)
    assertTrue(started.processAlive)
    assertTrue(
      emissions.any { it.eventKind == GoalProgressEventKind.OPERATION_HEARTBEAT },
      "expected at least one gated operation_heartbeat",
    )
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.SUCCEEDED, completed.outcome)
    assertFalse(completed.processAlive)
    assertEquals(1, emissions.count { it.eventKind == GoalProgressEventKind.OPERATION_STARTED })
    assertEquals(1, emissions.count { it.eventKind == GoalProgressEventKind.OPERATION_COMPLETED })
  }

  @Test
  fun `process lifecycle emits timed-out completion when wall clock cap elapses`() {
    val emissions = mutableListOf<AgentRunProgressEmission>()
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 300.milliseconds
          progressEmitter = AgentRunProgressEmitter { emissions += it }
        },
      )

    assertTrue(result.timedOut)
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.TIMED_OUT, completed.outcome)
    assertFalse(completed.processAlive)
  }

  @Test
  fun `process lifecycle emits cancelled completion when parent thread is interrupted`() {
    val emissions = Collections.synchronizedList(mutableListOf<AgentRunProgressEmission>())
    val runner = JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver())
    val worker =
      thread(start = true) {
        runner.run(
          testAgentRunProcessRequest(
            listOf("sh", "-c", "sleep 30"),
            Path.of(".").toAbsolutePath().normalize(),
          ) {
            timeout = 30.seconds
            progressEmitter = AgentRunProgressEmitter { emissions += it }
          },
        )
      }

    Thread.sleep(150)
    worker.interrupt()
    worker.join(5_000)

    assertFalse(worker.isAlive)
    val completed = emissions.last()
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, completed.eventKind)
    assertEquals(GoalProgressOutcome.CANCELLED, completed.outcome)
  }

  @Test
  fun `a faulty emitter never fails the run`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf done"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          progressEmitter = AgentRunProgressEmitter { error("emitter boom") }
        },
      )

    assertEquals(0, result.exitStatus)
    assertEquals("done", result.stdout)
    assertTrue(result.stderr.contains("run degradation"))
    assertTrue(result.stderr.contains("progress_lifecycle_emit"))
  }

  @Test
  fun `launch facts expose provider-neutral child session path and id for codex and other builders`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)
    listOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE, SupportedAgent.JUNIE, SupportedAgent.CURSOR).forEach { agent ->
      val facts = requireNotNull(adapters[agent]).launchFacts(skillRunRequest())
      assertEquals("/tmp/skillbill-agent-run", facts.childSessionPath, "session path for $agent")
      val sessionId = requireNotNull(facts.childSessionId) { "session id for $agent" }
      assertContains(sessionId, agent.id)
      assertContains(sessionId, "SKILL-56")
      assertContains(sessionId, "subtask-2")
    }
  }

  @Test
  fun `worktree edit observer fires once per changed activity token and never alters idle detection`() {
    val tokens = listOf("seed", "a", "b", "b")
    var probeCalls = 0
    val observations = AtomicInteger(0)
    val observed =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.8"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          activityProbe = AgentRunActivityProbe { tokens[minOf(probeCalls++, tokens.lastIndex)] }
          worktreeEditObserver =
            AgentRunWorktreeEditObserver {
              if (observations.incrementAndGet() == 2) error("journal unavailable")
            }
        },
      )

    assertEquals(0, observed.exitStatus)
    assertFalse(observed.timedOut)
    assertEquals(2, observations.get(), "two token changes after wait-loop seed read, including the throwing one")

    val idleObservations = AtomicInteger(0)
    val idle =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 2"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 5.seconds
          progressIdleTimeout = 100.milliseconds
          worktreeEditObserver = AgentRunWorktreeEditObserver { idleObservations.incrementAndGet() }
        },
      )

    assertTrue(idle.timedOut, "observer wiring must not keep an idle child alive")
    assertEquals(GoalRunnerLivenessState.IDLE, idle.liveness?.livenessState)
  }

  @Test
  fun `worktree activity probe tracks meaningful file changes and ignores build outputs`() {
    val root = Files.createTempDirectory("skillbill-worktree-activity")
    val probe = WorktreeActivityProbe(root, scanIntervalNanos = 0)
    val initial = probe.activityToken()

    Files.writeString(root.resolve("source.kt"), "source")
    val sourceChanged = probe.activityToken()

    Files.createDirectories(root.resolve("build"))
    Files.writeString(root.resolve("build/generated.txt"), "generated")
    val buildChanged = probe.activityToken()

    assertNotEquals(initial, sourceChanged)
    assertEquals(sourceChanged, buildChanged)
  }
}
