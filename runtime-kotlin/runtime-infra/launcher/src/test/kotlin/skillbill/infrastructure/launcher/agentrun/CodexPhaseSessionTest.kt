package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import skillbill.infrastructure.launcher.process.ApprovalTestOwner
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodexPhaseSessionTest {
  @Test
  fun `owned codex worker resumes exhausted app server turns and harvests only final prose`() {
    val directory = Files.createTempDirectory("codex-phase-session")
    try {
      val script = directory.resolve("server.sh")
      Files.writeString(script, providerScript())
      val worker = JvmAgentRunProcessRunner(LAUNCH_TEST_CLOCK, testGateJvmResolver(), testAgentRunScratchRoot())
      val fakeCommand = listOf(bashExecutable().toString(), script.toString())
      val runner =
        object : AgentRunProcessRunner {
          override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
            assertEquals("app-server", request.launch.command[1])
            return worker.run(
              request.copy(
                launch =
                  request.launch.copy(
                    command = fakeCommand,
                    continuation =
                      request.launch.continuation?.let { next ->
                        { result -> next(result)?.copy(command = fakeCommand) }
                      },
                  ),
              ),
            )
          }
        }
      val adapter =
        requireNotNull(
          headlessAgentRunAdapters(
            runner,
            ALL_EXECUTABLES_AVAILABLE,
            workerBinding =
              AgentRunWorkerBinding(
                approvals =
                  AgentRunApprovalOwners(
                    object : ApprovalOwnerAuthority {
                      override fun open(
                        request: ApprovalOwnerOpenRequest,
                        effects: ApprovalOwnerEffects,
                      ): ApprovalOwnerOpenResult = ApprovalOwnerOpenResult.Opened(ApprovalTestOwner())
                    },
                    LAUNCH_TEST_CLOCK,
                  ),
              ),
            diagnostics = NoopRuntimeDiagnostics,
            clock = LAUNCH_TEST_CLOCK,
          )[SupportedAgent.CODEX],
        )
      val result =
        adapter.launchFacts(
          request(model = "worker-model", effort = "high").copy(
            repoRoot = directory,
            timeout = null,
            additionalWritableDirectories = listOf(directory.resolve("qa")),
            approval = AgentRunApprovalRequirement.Owned("test-run"),
          ),
        )

      assertEquals(AgentRunTermination.Exited(0), result.termination, result.stderr)
      assertEquals("Completed work", result.stdout)
      assertContains(result.stderr, "Readian authentication or upstream request failed")
      assertEquals("6", Files.readString(directory.resolve("turn-count")).trim())
      val frames = Files.readString(directory.resolve("frames"))
      assertEquals(5, frames.lineSequence().count { it.contains("\"method\":\"thread/resume\"") })
      assertEquals(1, frames.lineSequence().count { it.contains("\"method\":\"thread/start\"") })
      assertContains(frames, "\"threadId\":\"thread-1\"")
      assertContains(frames, "\"effort\":\"high\"")
      assertContains(frames, "runtimeWorkspaceRoots")
      assertFalse(result.stdout.contains("turn/completed"))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  private fun providerScript(): String =
    """
    printf '%s\n' 'Readian authentication or upstream request failed' >&2
    count=0
    if [[ -f turn-count ]]; then count=${'$'}(cat turn-count); fi
    count=${'$'}((count + 1))
    printf '%s' "${'$'}count" >turn-count
    while IFS= read -r frame; do
      printf '%s\n' "${'$'}frame" >>frames
      case "${'$'}frame" in
        *'"method":"initialize"'*)
          printf '%s\n' '{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (fixture)"}}' ;;
        *'"method":"thread/start"'*|*'"method":"thread/resume"'*)
          printf '%s\n' "{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"},\"cwd\":\"${'$'}PWD\",\"model\":\"worker-model\",\"sandbox\":{\"type\":\"dangerFullAccess\"}}}" ;;
        *'"method":"turn/start"'*)
          printf '%s\n' '{"id":3,"result":{"turn":{"id":"turn-1","status":"inProgress"}}}'
          if [[ "${'$'}count" -lt 6 ]]; then
            printf '%s\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"$CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE"}}}}'
          else
            printf '%s\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"i","type":"agentMessage","text":"Completed work"}}}'
            printf '%s\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
          fi
          cat >/dev/null
          if [[ "${'$'}count" -lt 6 ]]; then exit 7; fi
          exit 0 ;;
      esac
    done
    """.trimIndent()
}
