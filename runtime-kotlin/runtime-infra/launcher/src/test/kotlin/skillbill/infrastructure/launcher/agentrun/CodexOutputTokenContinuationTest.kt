package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.runAgentRunContinuations
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class CodexOutputTokenContinuationTest {
  @Test
  fun `output exhaustion resumes the same worker beyond repair retry caps and returns only completed output`() {
    val requests = mutableListOf<AgentRunProcessRequest>()
    val clock = ContinuationClock()
    val facts =
      adapter(
        object : AgentRunProcessRunner {
          override fun run(request: AgentRunProcessRequest): AgentRunProcessResult =
            runAgentRunContinuations(request, clock) { current ->
              requests += current
              if (requests.size <= 5) exhausted() else completed()
            }
        },
      ).launchFacts(request(model = "worker-model", effort = "high").copy(timeout = null))

    assertEquals(AgentRunTermination.Exited(0), facts.termination)
    assertEquals("Completed work", facts.stdout)
    assertEquals("", facts.stderr)
    assertEquals(6, requests.size)
    val original = requests.first()
    requests.drop(1).forEach { resumed ->
      assertEquals(original.launch.command + listOf("resume", SESSION_ID, "-"), resumed.launch.command)
      assertEquals(original.launch.workingDirectory, resumed.launch.workingDirectory)
      assertEquals(original.environmentFields, resumed.environmentFields)
      assertSame(original.probes, resumed.probes)
      assertSame(original.review, resumed.review)
      assertSame(original.launch.outputSink, resumed.launch.outputSink)
      assertNotEquals(original.launch.stdinText, resumed.launch.stdinText)
      assertNotNull(resumed.launch.stdinText)
    }
  }

  @Test
  fun `unrelated failures and unavailable session identities never resume another conversation`() {
    val failures =
      listOf(
        exhausted(message = "Selected model is at capacity"),
        exhausted(sessionId = null),
        exhausted(sessionId = "--last"),
        exhausted().copy(timedOut = true),
        exhausted().copy(interrupted = true),
        exhausted().copy(stdoutTruncated = true),
        exhausted().copy(outputCaptureIncomplete = true),
        exhausted().copy(stdout = exhausted().stdout + "\n{\"type\":\"turn.completed\"}"),
      )
    failures.forEach { failure ->
      var launches = 0
      val settled =
        runAgentRunContinuations(processRequest(), ContinuationClock()) {
          launches += 1
          failure
        }
      assertEquals(1, launches, failure.toString())
      assertSame(failure, settled)
    }
  }

  @Test
  fun `continuations share the original wall clock deadline instead of restarting it`() {
    val clock = ContinuationClock()
    val original = processRequest()
    val requests = mutableListOf<AgentRunProcessRequest>()
    val timed =
      original.copy(
        timing = original.timing.copy(timeout = 5.seconds, operationDeadline = 3.seconds),
      )

    val settled =
      runAgentRunContinuations(timed, clock) { current ->
        requests += current
        clock.advanceSeconds(2)
        exhausted()
      }

    assertEquals(2, requests.size)
    assertEquals(3.seconds, requests.last().timing.timeout)
    assertEquals(1.seconds, requests.last().timing.operationDeadline)
    assertEquals(true, settled.timedOut)
    assertEquals(null, settled.exitStatus)
  }

  @Test
  fun `a continuation cannot switch sessions after the worker identity is pinned`() {
    var launches = 0
    val settled =
      runAgentRunContinuations(processRequest(), ContinuationClock()) {
        launches += 1
        if (launches == 1) exhausted() else exhausted(sessionId = OTHER_SESSION_ID)
      }

    assertEquals(2, launches)
    assertEquals(1, settled.exitStatus)
  }

  private fun processRequest(): AgentRunProcessRequest {
    var captured: AgentRunProcessRequest? = null
    adapter(
      object : AgentRunProcessRunner {
        override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
          captured = request
          return completed()
        }
      },
    ).launch(request())
    return requireNotNull(captured)
  }

  private fun adapter(runner: AgentRunProcessRunner) =
    ProcessAgentRunAdapter(
      agent = SupportedAgent.CODEX,
      commandBuilder = CodexAgentRunCommandBuilder(),
      processRunner = runner,
      executableLookup = ALL_EXECUTABLES_AVAILABLE,
      diagnostics = NoopRuntimeDiagnostics,
    )

  private fun exhausted(
    sessionId: String? = SESSION_ID,
    message: String = CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE,
  ) = result(
    exitStatus = 1,
    stdout =
      listOfNotNull(
        sessionId?.let { """{"type":"thread.started","thread_id":"$it"}""" },
        """{"type":"item.completed","item":{"type":"agent_message","text":"Partial work"}}""",
        """{"type":"turn.failed","error":{"message":"$message"}}""",
      ).joinToString("\n"),
  )

  private fun completed() =
    result(
      exitStatus = 0,
      stdout = """{"type":"item.completed","item":{"type":"agent_message","text":"Completed work"}}""",
    )

  private fun result(
    exitStatus: Int,
    stdout: String,
  ) = AgentRunProcessResult(
    exitStatus = exitStatus,
    stdout = stdout,
    stderr = "",
    timedOut = false,
    interrupted = false,
    spawnFailed = false,
  )

  private companion object {
    const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
    const val OTHER_SESSION_ID = "22222222-2222-2222-2222-222222222222"
  }
}

private class ContinuationClock : Clock() {
  private var now: Instant = Instant.EPOCH

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = now

  fun advanceSeconds(seconds: Long) {
    now = now.plusSeconds(seconds)
  }
}
