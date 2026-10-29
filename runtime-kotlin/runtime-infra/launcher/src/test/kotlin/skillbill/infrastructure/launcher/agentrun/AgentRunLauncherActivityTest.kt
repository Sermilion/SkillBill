package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.testAgentRunScratchRoot
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherActivityTest {
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
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE, diagnostics = NoopRuntimeDiagnostics)
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
