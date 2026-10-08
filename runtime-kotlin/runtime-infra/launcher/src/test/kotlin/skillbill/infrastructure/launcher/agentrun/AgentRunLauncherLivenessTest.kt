package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunActivityProbe
import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherLivenessTest {
  @Test
  fun `install scripts refuse process-level execution during goal continuation`() {
    val repoRoot = repoRoot()
    val fixtureRoot = Files.createTempDirectory("skillbill-goal-continuation-install-guard")

    listOf("install.sh", "uninstall.sh").forEach { scriptName ->
      val script = fixtureRoot.resolve(scriptName)
      Files.copy(repoRoot.resolve(scriptName), script)

      val result =
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
          testAgentRunProcessRequest(
            listOf(bashExecutable().toString(), script.toString()),
            fixtureRoot,
          ) {
            timeout = 3.seconds
            environment =
              mapOf(
                "HOME" to fixtureRoot.resolve("home").toString(),
                "SKILL_BILL_GOAL_CONTINUATION" to "1",
              )
            inheritEnvironment = false
          },
        )

      assertEquals(64, result.exitStatus, "$scriptName stdout=${result.stdout} stderr=${result.stderr}")
      assertContains(result.stdout, "Refusing to run $scriptName during skill-bill goal-continuation.")
      assertFalse(result.stdout.contains("Applying install through the runtime plan/apply path."))
      assertFalse(result.stdout.contains("Uninstall complete"))
    }
  }

  @Test
  fun `jvm process runner stops a live process after workflow progress stays idle without wall clock cap`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          progressIdleTimeout = 100.milliseconds
          progressProbe = AgentRunProgressProbe { null }
        },
      )

    assertTrue(result.timedOut)
    assertEquals(null, result.exitStatus)
    assertContains(result.stderr, "without durable workflow progress")
    assertContains(result.stderr, "No file activity was observed")
  }

  @Test
  fun `incremental output alone keeps a db-silent process alive past the idle window`() {
    val emitting = listOf("sh", "-c", "i=0; while [ \$i -lt 8 ]; do echo tick; sleep 0.15; i=\$((i+1)); done")

    val streamed =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          emitting,
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          progressIdleTimeout = 600.milliseconds
          progressProbe = AgentRunProgressProbe { null }
          idlePolicy = AgentRunIdlePolicy.OUTPUT_EXTENDED
        },
      )

    assertFalse(streamed.timedOut, "output arriving inside the idle window must extend it")
    assertEquals(0, streamed.exitStatus)

    val unstreamed =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          emitting,
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          progressIdleTimeout = 600.milliseconds
          progressProbe = AgentRunProgressProbe { null }
          idlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY
        },
      )

    assertTrue(unstreamed.timedOut, "the same output must not rescue a db-progress-only launch")
  }

  @Test
  fun `a silent process still dies at the idle deadline under output-extended liveness`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          progressIdleTimeout = 300.milliseconds
          progressProbe = AgentRunProgressProbe { null }
          idlePolicy = AgentRunIdlePolicy.OUTPUT_EXTENDED
        },
      )

    assertTrue(result.timedOut)
    assertEquals(null, result.exitStatus)
  }

  @Test
  fun `jvm process runner reports durable workflow progress labels`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    var probeCount = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.4"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          progressProbe =
            object : AgentRunProgressProbe {
              override fun progressToken(): String = "token-${probeCount++}"

              override fun progressLabel(): String = "subtask 7 workflow wfl-child step implement"
            }
          outputSink = { stream, text -> events += stream to text }
        },
      )

    assertEquals(0, result.exitStatus)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: workflow progress: subtask 7 workflow wfl-child step implement" in event.second
      },
    )
  }

  @Test
  fun `jvm process runner treats file activity as idle liveness`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    var activityProbeCount = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.8"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          progressIdleTimeout = 500.milliseconds
          fileActivityGraceTimeout = 2.seconds
          progressProbe = AgentRunProgressProbe { "workflow-token" }
          activityProbe =
            object : AgentRunActivityProbe {
              override fun activityToken(): String = if (activityProbeCount++ < 2) "files-before" else "files-after"

              override fun activityLabel(): String = "worktree files changed"
            }
          outputSink = { stream, text -> events += stream to text }
        },
      )

    assertEquals(0, result.exitStatus)
    assertFalse(result.timedOut)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: file activity observed; durable workflow progress is still pending" in event.second
      },
    )
  }

  @Test
  fun `jvm process runner emits periodic status heartbeat during long active runs`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 0.35"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          statusHeartbeatInterval = 100.milliseconds
          progressProbe =
            object : AgentRunProgressProbe {
              override fun progressToken(): String = "workflow-token"

              override fun progressLabel(): String = "subtask 4 workflow wfl-child step preplan"
            }
          outputSink = { stream, text -> events += stream to text }
        },
      )

    assertEquals(0, result.exitStatus)
    assertFalse(result.timedOut)
    assertTrue(
      events.any { event ->
        event.first == AgentRunOutputStream.STDERR &&
          "skill-bill: status heartbeat (100ms): child run still active;" in event.second &&
          "workflow: subtask 4 workflow wfl-child step preplan" in event.second
      },
    )
  }

  @Test
  fun `process wrapper heartbeats cannot keep a delegated worker past progress idle`() {
    val providerProgress = SharedDeclaredProgressStore()
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 5.seconds
          progressIdleTimeout = 100.milliseconds
          declaredProgressProbe = AgentRunDeclaredProgressProbe(providerProgress::snapshot)
          progressEmitter =
            AgentRunProgressEmitter { emission ->
              if (emission.authoritative) providerProgress.record(emission)
            }
        },
      )

    assertTrue(result.timedOut)
    assertEquals("progress_idle_timeout", result.liveness?.reason)
    assertTrue(
      providerProgress.recorded.isEmpty(),
      "Only provider-owned lifecycle envelopes may arm declared progress.",
    )
  }

  @Test
  fun `jvm process runner stops after bounded file activity grace without durable workflow progress`() {
    var activityProbeCount = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 5.seconds
          progressIdleTimeout = 100.milliseconds
          fileActivityGraceTimeout = 300.milliseconds
          progressProbe = AgentRunProgressProbe { "workflow-token" }
          activityProbe =
            object : AgentRunActivityProbe {
              override fun activityToken(): String = "files-${activityProbeCount++}"

              override fun activityLabel(): String = "worktree files changed"
            }
        },
      )

    assertTrue(result.timedOut)
    assertContains(result.stderr, "without durable workflow progress")
    assertContains(result.stderr, "file-activity grace window was exhausted")
  }

  @Test
  fun `wall clock cap restarts when the workflow step changes`() {
    var probeCount = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 1.2"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 1.seconds
          progressProbe =
            object : AgentRunProgressProbe {
              override fun progressToken(): String = "step-${++probeCount}"

              override fun progressLabel(): String =
                "subtask 8 workflow wfl-child step ${if (probeCount <= 2) "implement" else "validate"}"
            }
        },
      )

    assertFalse(result.timedOut, "a phase transition must restart the wall-clock cap")
    assertEquals(0, result.exitStatus)
  }

  @Test
  fun `wall clock cap still fires when the workflow step stays put`() {
    var probeCount = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 1.2"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 1.seconds
          progressProbe =
            object : AgentRunProgressProbe {
              override fun progressToken(): String = "implement-${++probeCount}"

              override fun progressLabel(): String = "subtask 8 workflow wfl-child step implement"
            }
        },
      )

    assertTrue(result.timedOut)
    assertContains(result.stderr, "wall-clock cap")
  }
}
