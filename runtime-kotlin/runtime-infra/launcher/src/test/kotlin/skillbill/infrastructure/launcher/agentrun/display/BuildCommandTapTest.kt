package skillbill.infrastructure.launcher.agentrun.display

import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessProbeFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BuildCommandTapTest {
  @Test
  fun `process runner binds completion samples until the final command output is drained`() {
    val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    val events = mutableListOf<AgentRunCommandEvent>()
    val serialized = SerializedBuildObserver(recordingObserver(events))
    val tap = BuildCommandTap("codex", AgentRunOutputSink.NONE, serialized, clock, NoopRuntimeDiagnostics)
    val command =
      """{"type":"item.completed","item":{"id":"call1","type":"command_execution",""" +
        """"command":"cargo build","exit_code":0,"status":"completed"}}"""
    val request =
      AgentRunProcessRequest(
        launch =
          AgentRunProcessLaunchFields(
            command = listOf("sh", "-c", "printf '%s' '$command'"),
            workingDirectory = Path.of("."),
            outputSink = tap,
          ),
        probes = AgentRunProcessProbeFields(buildObserver = serialized),
      )

    val result = JvmAgentRunProcessRunner(clock, testGateJvmResolver(), testAgentRunScratchRoot()).run(request)

    assertEquals(0, result.exitStatus)
    assertEquals(0, events.last().exitCode)
    assertEquals(emptyList(), events.last().completionProcessSamples)
    assertFalse(result.stderr.contains("build_observe"), result.stderr)
  }

  @Test
  fun `codex command events retain exit codes and pass output through unchanged`() {
    val fixedClock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)
    val delegateOutput = StringBuilder()
    val events = mutableListOf<AgentRunCommandEvent>()
    val observer = recordingObserver(events)
    val serialized = SerializedBuildObserver(observer)
    val binding = serialized.bindCompletionSamples { emptyList() }
    val tap =
      BuildCommandTap(
        "codex",
        AgentRunOutputSink { _, text -> delegateOutput.append(text) },
        serialized,
        fixedClock,
        NoopRuntimeDiagnostics,
      )
    val lines =
      listOf(
        """{"type":"item.started","item":{"id":"call1","type":"command_execution","command":"cargo build"}}""",
        """{"type":"item.completed","item":{"id":"call1","type":"command_execution","command": """ +
          """"cargo build","exit_code":101,"status":"failed"}}""",
      )
    val input = lines.joinToString("\n", postfix = "\n")

    tap.write(AgentRunOutputStream.STDOUT, input.take(47))
    tap.write(AgentRunOutputStream.STDOUT, input.drop(47))
    tap.finish()
    serialized.finished(fixedClock.instant())
    binding.close()

    assertEquals(input, delegateOutput.toString())
    assertEquals(listOf("STARTED", "FAILED"), events.map { it.kind.name })
    assertEquals(101, events.last().exitCode)
    assertEquals(emptyList(), events.last().completionProcessSamples)
    assertEquals(fixedClock.instant(), events.first().observedAt)
  }

  @Test
  fun `claude command failures are observed without changing output`() {
    val fixedClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    val output = StringBuilder()
    val events = mutableListOf<AgentRunCommandEvent>()
    val serialized = SerializedBuildObserver(recordingObserver(events))
    val binding = serialized.bindCompletionSamples { emptyList() }
    val tap =
      BuildCommandTap(
        "claude",
        AgentRunOutputSink { _, text -> output.append(text) },
        serialized,
        fixedClock,
        NoopRuntimeDiagnostics,
      )
    val lines =
      listOf(
        """{"type":"assistant","message":{"content":[{"type":"tool_use",""" +
          """"id":"call1","name":"Bash","input":{"command":"cargo build"}}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"call1","is_error":true}]}}""",
      )
    val input = lines.joinToString("\n", postfix = "\n")

    tap.write(AgentRunOutputStream.STDOUT, input)
    tap.finish()
    serialized.finished(fixedClock.instant())
    binding.close()

    assertEquals(input, output.toString())
    assertEquals(listOf("STARTED", "FAILED"), events.map { it.kind.name })
    assertEquals(null, events.last().exitCode)
    assertEquals(emptyList(), events.last().completionProcessSamples)
  }

  private fun recordingObserver(events: MutableList<AgentRunCommandEvent>) =
    object : AgentRunBuildObserver {
      override fun processes(
        observedAt: Instant,
        samples: List<AgentRunProcessSample>,
      ) = Unit

      override fun command(event: AgentRunCommandEvent) {
        events += event
      }

      override fun finished(observedAt: Instant) = Unit
    }
}
