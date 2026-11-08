package skillbill.infrastructure.launcher.process.codex

import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalChannelOpening
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalOpenResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessApprovalFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration.between
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

internal class CodexPhaseSession(
  private val request: AgentRunProcessRequest,
  private val runner: AgentRunProcessRunner,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val open: (CodexSessionLaunch) -> AgentRunApprovalOpenResult,
) {
  private val startedAt = clock.instant()
  private var thread: String? = null

  fun run(launch: CodexSessionLaunch): AgentRunProcessResult {
    val remaining = remainingRequest() ?: return expired()
    val initial = fields(launch)
    val processRequest =
      remaining.copy(
        launch = initial.copy(continuation = { result -> continueTurn(result, launch) }),
        approval = AgentRunProcessApprovalFields(),
      )
    return runner.run(processRequest)
  }

  private fun fields(launch: CodexSessionLaunch): AgentRunProcessLaunchFields =
    request.launch.copy(
      stdinText = null,
      continuation = null,
      session = AgentRunApprovalChannelOpening { open(launch) },
    )

  private fun continueTurn(
    result: AgentRunProcessResult,
    launch: CodexSessionLaunch,
  ): AgentRunProcessLaunchFields? {
    val outcome = result.sessionOutcome ?: return null
    val observed = outcome.threadId?.takeIf(String::isNotBlank)
    val resumable =
      outcome.canContinue && outcome.failure == CODEX_OUTPUT_EXHAUSTED && observed != null &&
        (thread == null || thread == observed)
    return if (!resumable) {
      diagnostics.warning("seam=codex_output_continuation outcome=not_resumable")
      null
    } else {
      thread = observed
      diagnostics.warning("seam=codex_output_continuation outcome=continuation_requested")
      fields(launch.copy(prompt = CONTINUATION_PROMPT, savedThread = observed, model = outcome.model ?: launch.model))
    }
  }

  private fun remainingRequest(): AgentRunProcessRequest? {
    val elapsed = between(startedAt, clock.instant()).toKotlinDuration().coerceAtLeast(Duration.ZERO)
    val timeout = request.timing.timeout?.minus(elapsed)
    val deadline = request.timing.operationDeadline?.minus(elapsed)
    return if (timeout?.isPositive() == false || deadline?.isPositive() == false) {
      null
    } else {
      request.copy(timing = request.timing.copy(timeout = timeout, operationDeadline = deadline))
    }
  }

  private fun expired(): AgentRunProcessResult =
    AgentRunProcessResult(
      null,
      "",
      stderr = "Codex session exhausted the original run deadline.",
      timedOut = true,
      interrupted = false,
      spawnFailed = false,
    )

  private companion object {
    const val CONTINUATION_PROMPT =
      "Continue the same task from the current session and worktree. The " +
        "previous response reached its " +
        "output-token limit. Preserve completed edits and continue the unfinished " +
        "work. " +
        "Return the required final output only after the work is complete."
  }
}
