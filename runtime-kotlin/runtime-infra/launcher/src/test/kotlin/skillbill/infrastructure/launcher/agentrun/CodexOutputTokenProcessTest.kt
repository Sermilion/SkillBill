package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessReviewFields
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexOutputTokenProcessTest {
  @Test
  fun `real worker continuation retains its review endpoint until the final process exits`() {
    val fixture = Files.createTempDirectory("codex-output-continuation")
    var endpointCloses = 0
    val endpoint =
      object : GovernedReviewEvidenceEndpointHandle {
        override val descriptor = StubReviewEvidenceEndpoint.descriptor

        override fun close() {
          endpointCloses += 1
          Files.writeString(fixture.resolve("endpoint-closed"), "closed")
        }
      }
    try {
      val script = fixture.resolve("worker.sh")
      Files.writeString(script, workerScript())
      val command =
        AgentRunCommand(
          command = listOf(bashExecutable().toString(), script.toString()),
          workingDirectory = fixture,
          timeout = null,
          stdinText = "Original phase briefing",
        )
      val request =
        AgentRunProcessRequest(
          launch =
            AgentRunProcessLaunchFields(
              command = command.command,
              workingDirectory = fixture,
              stdinText = command.stdinText,
              continuation = CodexAgentRunCommandBuilder().continuation(command, NoopRuntimeDiagnostics),
            ),
          review =
            AgentRunProcessReviewFields(
              conversationIsolation = ReviewConversationIsolation.FRESH,
              reviewEvidenceBroker =
                object : ReviewEvidenceBroker by NoOpReviewEvidenceBroker {
                  override fun terminalOutcome() = null
                },
              reviewEvidenceEndpoint = endpoint,
            ),
        )
      val result =
        JvmAgentRunProcessRunner(
          Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
          testGateJvmResolver(),
        ).run(request)

      assertEquals(0, result.exitStatus, result.stderr)
      assertEquals("Completed work", AgentRunOutputDecoder.CODEX_JSONL.decode(result.stdout).text)
      assertEquals(1, endpointCloses)
      assertEquals("closed", Files.readString(fixture.resolve("endpoint-closed")))
    } finally {
      fixture.toFile().deleteRecursively()
    }
  }

  private fun workerScript(): String =
    """
    cat >/dev/null
    if [[ -e endpoint-closed ]]; then
      exit 9
    fi
    if [[ "${'$'}{1:-}" == resume && "${'$'}{2:-}" == 11111111-1111-1111-1111-111111111111 ]]; then
      printf '%s\n' '{"type":"item.completed","item":{"type":"agent_message","text":"Completed work"}}'
      exit 0
    fi
    printf '%s\n' '{"type":"thread.started","thread_id":"11111111-1111-1111-1111-111111111111"}'
    printf '%s\n' '{"type":"turn.failed","error":{"message":"$CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE"}}'
    exit 1
    """.trimIndent()
}
