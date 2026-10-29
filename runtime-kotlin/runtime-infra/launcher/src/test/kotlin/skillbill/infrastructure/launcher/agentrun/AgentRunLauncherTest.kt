package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunScratchRoot
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class RecordingAgentRunProcessRunner(
  private val result: AgentRunProcessResult =
    AgentRunProcessResult(
      exitStatus = 0,
      stdout = "ok",
      stderr = "",
      timedOut = false,
      interrupted = false,
      spawnFailed = false,
    ),
) : AgentRunProcessRunner {
  val requests: MutableList<AgentRunProcessRequest> = mutableListOf()

  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    requests += request
    return result
  }
}

class HeadlessAgentRunAdapterTest {
  private fun phaseRunRequest(): SkillRunRequest =
    SkillRunRequest(
      issueKey = "SKILL-88",
      repoRoot = Path.of("/tmp/skillbill-agent-run"),
      subtaskId = 1,
      timeout = 10.seconds,
      goalContinuation = null,
    ).copy(promptOverride = "Phase: preplan")

  private fun skillRunRequest(): SkillRunRequest =
    SkillRunRequest(
      issueKey = "SKILL-88",
      repoRoot = Path.of("/tmp/skillbill-agent-run"),
      subtaskId = 1,
      timeout = 3.seconds,
      goalContinuation = null,
      promptOverride = "Test prompt",
      streamOutputForLiveness = true,
    )

  @Test
  fun `headless runtime adapters cover exactly the runtime-capable install agents`() {
    val adapters = headlessAgentRunAdapters(RecordingAgentRunProcessRunner(), diagnostics = NoopRuntimeDiagnostics)

    assertEquals(
      setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX, SupportedAgent.JUNIE, SupportedAgent.CURSOR),
      adapters.keys,
    )
  }

  @Test
  fun `process adapter carries codex fork turns none to the worker start request`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = governedReviewRequest()
    val builder = CodexAgentRunCommandBuilder()

    ProcessAgentRunAdapter(
      SupportedAgent.CODEX,
      builder,
      runner,
      ALL_EXECUTABLES_AVAILABLE,
      diagnostics = NoopRuntimeDiagnostics,
    ).launch(request)

    assertEquals(ReviewConversationIsolation.FRESH, runner.requests.single().review.conversationIsolation)
    assertTrue(runner.requests.single().launch.command.any { it == "fork_turns=none" })
  }

  @Test
  fun `cursor is registered as a headless adapter with correct builder and decoder`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE, diagnostics = NoopRuntimeDiagnostics)

    val cursorAdapter = adapters[SupportedAgent.CURSOR]
    assertNotNull(cursorAdapter, "cursor must be registered as a headless adapter")

    val request = phaseRunRequest()
    cursorAdapter.launch(request)

    val captured = runner.requests.single()
    assertEquals("agent", captured.launch.command.first())
    assertTrue(captured.launch.command.contains("--workspace"))
  }

  @Test
  fun `cursor launch is not refused and succeeds`() {
    val launcher =
      FileSystemAgentRunLauncher(
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot()),
        ALL_EXECUTABLES_AVAILABLE,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val outcome =
      launcher.launch(
        AgentRunLaunchRequest(
          agentId = "cursor",
          skillRunRequest = skillRunRequest(),
        ),
      )

    assertFalse(outcome is UnsupportedAgentRunLaunch, "cursor launch must not be refused")
  }

  @Test
  fun `cursor process launch honors timeout expiry with streamed output`() {
    val runner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = null,
            stdout = "partial output",
            stderr = "slow",
            timedOut = true,
            interrupted = false,
            spawnFailed = false,
          ),
      )
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE, diagnostics = NoopRuntimeDiagnostics)
    val request =
      skillRunRequest().copy(
        promptOverride = "Run timeout test",
        timeout = 100.milliseconds,
      )

    requireNotNull(adapters[SupportedAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(100.milliseconds, captured.timing.timeout)
    assertTrue(captured.launch.command.contains("stream-json"))
  }

  @Test
  fun `cursor process launch honors interruption`() {
    val runner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = null,
            stdout = "partial",
            stderr = "interrupted",
            timedOut = false,
            interrupted = true,
            spawnFailed = false,
          ),
      )
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE, diagnostics = NoopRuntimeDiagnostics)

    val outcome = requireNotNull(adapters[SupportedAgent.CURSOR]).launchFacts(skillRunRequest())

    assertEquals(AgentRunTermination.Interrupted, outcome.termination)
    assertEquals("interrupted", outcome.stderr)
  }

  @Test
  fun `cursor durable-progress policies remain in force with streamed output`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapters = headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE, diagnostics = NoopRuntimeDiagnostics)
    val request =
      skillRunRequest().copy(
        promptOverride = "Test progress policies",
        streamOutputForLiveness = true,
      )

    requireNotNull(adapters[SupportedAgent.CURSOR]).launch(request)

    val captured = runner.requests.single()
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, captured.probes.idlePolicy)
  }
}
