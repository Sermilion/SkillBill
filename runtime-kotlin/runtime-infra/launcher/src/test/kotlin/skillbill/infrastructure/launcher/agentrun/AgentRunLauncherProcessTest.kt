package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunTermination
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherProcessTest {
  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for stdin-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = AGENT_RUN_LAUNCHER_PHASE_PROMPT)

    requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CLAUDE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("claude", captured.launch.command[0])
    assertEquals(AGENT_RUN_LAUNCHER_PHASE_PROMPT, captured.launch.stdinText)

    assertEquals("--add-dir", captured.launch.command[captured.launch.command.size - 2])
  }

  @Test
  fun `a phase-briefing prompt override drives the per-agent CLI for argv-delivered agents`() {
    val runner = RecordingAgentRunProcessRunner()
    val request = skillRunRequest(goalContinuation = null).copy(promptOverride = AGENT_RUN_LAUNCHER_PHASE_PROMPT)

    requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.JUNIE]).launch(request)

    val captured = runner.requests.single()
    assertEquals("junie", captured.launch.command.first())
    assertEquals(AGENT_RUN_LAUNCHER_PHASE_PROMPT, captured.launch.command.last())
  }

  @Test
  fun `unknown agent id fails before launch`() {
    val launcher =
      FileSystemAgentRunLauncher(
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()),
        ALL_EXECUTABLES_AVAILABLE,
      )

    assertFailsWith<IllegalArgumentException> {
      launcher.launch(
        AgentRunLaunchRequest(
          agentId = "not-an-agent",
          skillRunRequest = skillRunRequest(),
        ),
      )
    }
  }

  @Test
  fun `timeout and spawn-failure paths stay launch-level facts`() {
    val timeoutRunner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = null,
            stdout = "partial",
            stderr = "slow",
            timedOut = true,
            interrupted = false,
            spawnFailed = false,
          ),
      )
    val timeout =
      requireNotNull(
        headlessAgentRunAdapters(timeoutRunner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CODEX],
      ).launchFacts(skillRunRequest())
    assertEquals(AgentRunTermination.TimedOut, timeout.termination)

    val spawnRunner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = null,
            stdout = "",
            stderr = "missing executable",
            timedOut = false,
            interrupted = false,
            spawnFailed = true,
          ),
      )
    val spawnFailure =
      requireNotNull(
        headlessAgentRunAdapters(spawnRunner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CODEX],
      ).launchFacts(skillRunRequest())
    assertEquals(AgentRunTermination.SpawnFailed, spawnFailure.termination)
    assertEquals("missing executable", spawnFailure.stderr)
  }

  @Test
  fun `plain launch facts retain the decoded body byte size and digest`() {
    val rawBytes = byteArrayOf(0, 13, 10, -1, 42)
    val runner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = 0,
            stdout = "\u0000\r\n�*",
            stdoutBytes = rawBytes,
            stderr = "",
            timedOut = false,
            interrupted = false,
            spawnFailed = false,
          ),
      )

    val facts =
      requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CODEX])
        .launchFacts(skillRunRequest())

    assertEquals(rawBytes.size.toLong(), facts.stdoutByteSize)
    assertEquals(baselineSha256Hex(rawBytes), facts.stdoutSha256)
  }

  @Test
  fun `structured provider launch facts expose the decoded response body bytes not envelope bytes`() {
    val envelope = """{"type":"result","result":"{\"status\":\"blocked\"}","usage":{}}"""
    val runner =
      RecordingAgentRunProcessRunner(
        result =
          AgentRunProcessResult(
            exitStatus = 0,
            stdout = envelope,
            stderr = "",
            timedOut = false,
            interrupted = false,
            spawnFailed = false,
          ),
      )

    val facts =
      requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CLAUDE])
        .launchFacts(skillRunRequest())

    assertEquals("""{"status":"blocked"}""", facts.stdout)
    assertEquals(20L, facts.stdoutByteSize)
    assertEquals(baselineSha256Hex(facts.stdout.encodeToByteArray()), facts.stdoutSha256)
  }

  @Test
  fun `adapter invokes process runner once per launch`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter = requireNotNull(headlessAgentRunAdapters(runner, ALL_EXECUTABLES_AVAILABLE)[SupportedAgent.CODEX])

    adapter.launch(
      skillRunRequest(issueKey = "SKILL-56", goalContinuation = null)
        .copy(promptOverride = "$AGENT_RUN_LAUNCHER_PHASE_PROMPT\nIssue key: SKILL-56"),
    )
    adapter.launch(
      skillRunRequest(issueKey = "SKILL-57", goalContinuation = null)
        .copy(promptOverride = "$AGENT_RUN_LAUNCHER_PHASE_PROMPT\nIssue key: SKILL-57"),
    )

    assertEquals(2, runner.requests.size)
    assertContains(requireNotNull(runner.requests[0].launch.stdinText), "SKILL-56")
    assertContains(requireNotNull(runner.requests[1].launch.stdinText), "SKILL-57")
  }

  @Test
  fun `jvm process runner tees live output while preserving captured output`() {
    val events = mutableListOf<Pair<AgentRunOutputStream, String>>()
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf stdout-line; printf stderr-line >&2"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          outputSink = { stream, text -> synchronized(events) { events += stream to text } }
        },
      )

    assertEquals(0, result.exitStatus)
    assertEquals("stdout-line", result.stdout)
    assertEquals("stderr-line", result.stderr)
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDOUT && it.second.contains("stdout-line") },
    )
    assertTrue(
      events.any { it.first == AgentRunOutputStream.STDERR && it.second.contains("stderr-line") },
    )
  }

  @Test
  fun `jvm process runner closes child stdin for non-interactive runs`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "if read line; then printf got; else printf eof; fi"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
        },
      )

    assertEquals(0, result.exitStatus)
    assertEquals("eof", result.stdout)
  }

  @Test
  fun `jvm process runner writes configured stdin text before closing child stdin`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "cat"),
          Path.of(".").toAbsolutePath().normalize(),
        ) {
          timeout = 3.seconds
          stdinText = "prompt over stdin"
        },
      )

    assertEquals(0, result.exitStatus)
    assertEquals("prompt over stdin", result.stdout)
  }
}

private fun baselineSha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
