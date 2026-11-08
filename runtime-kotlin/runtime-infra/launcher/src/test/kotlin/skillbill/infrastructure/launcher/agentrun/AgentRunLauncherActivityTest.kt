package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherActivityTest {
  @Test
  fun `every provider forwards silent build process observations to the launch observer`() {
    listOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE, SupportedAgent.CURSOR, SupportedAgent.JUNIE).forEach { agent ->
      val received = mutableListOf<List<AgentRunProcessSample>>()
      val observer =
        object : AgentRunBuildObserver {
          override fun processes(
            observedAt: Instant,
            samples: List<AgentRunProcessSample>,
          ) {
            received += samples
          }

          override fun command(event: AgentRunCommandEvent) = Unit

          override fun finished(observedAt: Instant) = Unit
        }
      val samples =
        listOf(
          AgentRunProcessSample(
            42L,
            1L,
            "java",
            listOf("org.gradle.wrapper.GradleWrapperMain", "check"),
            Instant.EPOCH,
          ),
        )
      val runner =
        object : AgentRunProcessRunner {
          override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
            request.probes.buildObserver.processes(Instant.EPOCH, samples)
            request.probes.buildObserver.finished(Instant.EPOCH)
            return AgentRunProcessResult(
              0,
              "",
              stderr = "",
              timedOut = false,
              interrupted = false,
              spawnFailed = false,
            )
          }
        }
      val adapter =
        requireNotNull(
          headlessAgentRunAdapters(
            runner,
            ALL_EXECUTABLES_AVAILABLE,
            diagnostics = NoopRuntimeDiagnostics,
            clock = LAUNCH_TEST_CLOCK,
          )[agent],
        )

      adapter.launchFacts(skillRunRequest().copy(promptOverride = "Validate this project", buildObserver = observer))

      assertEquals(listOf(samples), received, "build observations for $agent")
    }
  }

  @Test
  fun `a faulty emitter never fails the run`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot()).run(
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
    val adapters =
      headlessAgentRunAdapters(
        runner,
        ALL_EXECUTABLES_AVAILABLE,
        diagnostics = NoopRuntimeDiagnostics,
        clock = LAUNCH_TEST_CLOCK,
      )
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
