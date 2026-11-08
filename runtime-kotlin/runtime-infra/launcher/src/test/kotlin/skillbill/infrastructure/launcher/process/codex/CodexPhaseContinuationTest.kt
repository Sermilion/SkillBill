package skillbill.infrastructure.launcher.process.codex

import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessTimingFields
import skillbill.infrastructure.launcher.process.launch.runAgentRunContinuations
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CodexPhaseContinuationTest {
  @Test
  fun `schema probes and continued turns consume one original deadline`() {
    val directory = Files.createTempDirectory("codex-deadline")
    try {
      val clock = SessionClock()
      val turns = mutableListOf<AgentRunProcessRequest>()
      val request =
        AgentRunProcessRequest(
          AgentRunProcessLaunchFields(listOf("codex", "app-server"), directory),
          timing = AgentRunProcessTimingFields(timeout = 10.seconds, operationDeadline = 9.seconds),
        )
      val runner =
        scriptedRunner(clock, turns) { index ->
          clock.advance(if (index == 1) 2 else 4)
          exhausted()
        }
      val session = CodexPhaseSession(request, runner, clock, NoopRuntimeDiagnostics) { error("unused") }

      val result = session.run(launch(directory))

      assertTrue(result.timedOut)
      assertEquals(2, turns.size)
      assertEquals(listOf(6.seconds, 4.seconds), turns.map { it.timing.timeout })
      assertEquals(listOf(5.seconds, 3.seconds), turns.map { it.timing.operationDeadline })
      assertSame(request.probes, turns.last().probes)
      assertSame(request.review, turns.last().review)
      assertSame(request.environmentFields, turns.last().environmentFields)
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `incomplete captures and a changed provider identity stop continuation`() {
    val failures =
      listOf(
        exhausted().copy(outputCaptureIncomplete = true),
        exhausted().copy(stdoutTruncated = true),
        exhausted().copy(interrupted = true),
        exhausted(thread = "changed-thread"),
      )
    for (failure in failures) {
      val directory = Files.createTempDirectory("codex-capture")
      try {
        val clock = SessionClock()
        val turns = mutableListOf<AgentRunProcessRequest>()
        val request = AgentRunProcessRequest(AgentRunProcessLaunchFields(listOf("codex", "app-server"), directory))
        val runner = scriptedRunner(clock, turns) { index -> if (index == 1) exhausted() else failure }
        val session = CodexPhaseSession(request, runner, clock, NoopRuntimeDiagnostics) { error("unused") }

        val result = session.run(launch(directory))

        assertEquals(2, turns.size)
        assertSame(failure, result)
        assertTrue(turns.all { it.launch.command == request.launch.command })
      } finally {
        directory.toFile().deleteRecursively()
      }
    }
  }

  private fun scriptedRunner(
    clock: SessionClock,
    turns: MutableList<AgentRunProcessRequest>,
    turn: (Int) -> AgentRunProcessResult,
  ): AgentRunProcessRunner =
    object : AgentRunProcessRunner {
      override fun run(request: AgentRunProcessRequest): AgentRunProcessResult =
        when {
          request.launch.command.last() == "--version" -> {
            clock.advance(2)
            result("codex-cli 0.166.0")
          }
          "generate-json-schema" in request.launch.command -> {
            clock.advance(2)
            writeCodexSchemaFixture(Path.of(request.launch.command.last()))
            result("")
          }
          else ->
            runAgentRunContinuations(request, clock) { next ->
              turns += next
              turn(turns.size)
            }
        }
    }

  private fun launch(directory: Path) =
    CodexSessionLaunch("briefing", "$directory", null, null, null, AgentRunOutputSink.NONE)

  private fun result(text: String) =
    AgentRunProcessResult(
      0,
      text,
      stderr = "",
      timedOut = false,
      interrupted = false,
      spawnFailed = false,
    )

  private fun exhausted(thread: String = "thread-1") =
    result("").copy(
      exitStatus = 1,
      sessionOutcome = AgentRunSessionOutcome("", "worker-model", thread, false, CODEX_OUTPUT_EXHAUSTED, true),
    )
}

private class SessionClock : Clock() {
  private var now = Instant.EPOCH

  override fun instant(): Instant = now

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  fun advance(seconds: Long) {
    now = now.plusSeconds(seconds)
  }
}
