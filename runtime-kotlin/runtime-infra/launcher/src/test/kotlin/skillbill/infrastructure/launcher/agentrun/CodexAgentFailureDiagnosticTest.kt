package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexAgentFailureDiagnosticTest {
  @Test
  fun `capacity errors survive JSON decoding as launch diagnostics without becoming phase output`() {
    val message = "Selected model is at capacity. Please try a different model."
    val events =
      """
      {"type":"thread.started","thread_id":"thread-1"}
      {"type":"error","message":"Retrying request"}
      {"type":"turn.failed","error":{"message":"$message"}}
      """.trimIndent()

    val facts = launch(events, exitStatus = 1)

    assertEquals(AgentRunTermination.Exited(1), facts.termination)
    assertEquals("", facts.stdout)
    assertEquals(0L, facts.stdoutByteSize)
    assertEquals("Reading prompt from stdin...\n$message", facts.stderr)
  }

  @Test
  fun `top level errors remain diagnostic when a failed process has partial assistant output`() {
    val events =
      """
      {"type":"item.completed","item":{"type":"agent_message","text":"Partial repair"}}
      {"type":"error","message":"Provider connection closed"}
      """.trimIndent()

    val facts = launch(events, exitStatus = 1)

    assertEquals("Partial repair", facts.stdout)
    assertTrue(facts.stderr.endsWith("Provider connection closed"))
  }

  @Test
  fun `a recovered transient error does not pollute a successful launch`() {
    val events =
      """
      {"type":"error","message":"Retrying request"}
      {"type":"item.completed","item":{"type":"agent_message","text":"Repair complete"}}
      {"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}
      """.trimIndent()

    val facts = launch(events, exitStatus = 0)

    assertEquals(AgentRunTermination.Exited(0), facts.termination)
    assertEquals("Repair complete", facts.stdout)
    assertEquals("Reading prompt from stdin...\n", facts.stderr)
  }

  private fun launch(
    events: String,
    exitStatus: Int,
  ) = ProcessAgentRunAdapter(
    agent = SupportedAgent.CODEX,
    commandBuilder = CodexAgentRunCommandBuilder(),
    processRunner =
      RecordingAgentRunProcessRunner(
        AgentRunProcessResult(
          exitStatus = exitStatus,
          stdout = events,
          stderr = "Reading prompt from stdin...\n",
          timedOut = false,
          interrupted = false,
          spawnFailed = false,
        ),
      ),
    executableLookup = ALL_EXECUTABLES_AVAILABLE,
    diagnostics = NoopRuntimeDiagnostics,
  ).launchFacts(request())
}
