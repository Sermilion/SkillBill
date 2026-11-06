package skillbill.infrastructure.launcher.process.cursor

import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalChannelOpening
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalOpenResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessApprovalFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessProbeFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.install.model.AgentLauncherCli
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration.between
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

internal class CursorPhaseSession(
  private val request: AgentRunProcessRequest,
  private val runner: AgentRunProcessRunner,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val lookup: ExecutableLookup,
  private val cli: AgentLauncherCli,
  private val open: () -> AgentRunApprovalOpenResult,
) {
  private val startedAt = clock.instant()

  fun run(): AgentRunProcessResult {
    for (candidate in cli.executables.filter(lookup::onPath)) {
      val remaining = remaining() ?: return expired()
      val checked = probe(candidate, remaining)
      if (interrupted(checked)) return checked
      if (usable(checked)) return session(candidate)
      diagnostics.warning("seam=cursor_acp outcome=alias_incompatible")
    }
    diagnostics.warning("seam=cursor_acp outcome=unsupported")
    return AgentRunProcessResult(
      null,
      "",
      stderr = "No declared Cursor executable supplies ACP stdio.",
      timedOut = false,
      interrupted = false,
      spawnFailed = true,
    )
  }

  private fun probe(
    candidate: String,
    remaining: AgentRunProcessRequest,
  ): AgentRunProcessResult =
    runner.run(
      remaining.copy(
        launch = AgentRunProcessLaunchFields(listOf(candidate, "acp", "--help"), request.launch.workingDirectory),
        probes = AgentRunProcessProbeFields(),
        approval = AgentRunProcessApprovalFields(),
        timing = remaining.timing.copy(timeout = remaining.timing.timeout?.coerceAtMost(PROBE_LIMIT) ?: PROBE_LIMIT),
      ),
    )

  private fun interrupted(result: AgentRunProcessResult): Boolean =
    result.interrupted || result.timedOut || result.spawnDenied != null

  private fun usable(result: AgentRunProcessResult): Boolean =
    result.exitStatus == 0 &&
      !result.spawnFailed && !result.outputCaptureIncomplete

  private fun session(executable: String): AgentRunProcessResult {
    val remaining = remaining() ?: return expired()
    return runner.run(
      remaining.copy(
        launch =
          request.launch.copy(
            command = listOf(executable, "acp"),
            stdinText = null,
            session = AgentRunApprovalChannelOpening { open() },
          ),
        approval = AgentRunProcessApprovalFields(),
      ),
    )
  }

  private fun remaining(): AgentRunProcessRequest? {
    val elapsed = between(startedAt, clock.instant()).toKotlinDuration()
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
      stderr = "Cursor ACP admission exhausted the original deadline; no prompt was sent.",
      timedOut = true,
      interrupted = false,
      spawnFailed = false,
    )

  private companion object {
    val PROBE_LIMIT = 30.seconds
  }
}
