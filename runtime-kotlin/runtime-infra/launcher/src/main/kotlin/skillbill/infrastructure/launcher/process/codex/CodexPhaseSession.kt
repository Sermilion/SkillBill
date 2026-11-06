package skillbill.infrastructure.launcher.process.codex

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalChannelOpening
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalOpenResult
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessApprovalFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessProbeFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration.between
import java.util.Comparator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
    val directory =
      try {
        Files.createTempDirectory(request.launch.workingDirectory, ".skill-bill-codex-schema-")
      } catch (failure: IOException) {
        return refused("Could not prepare the Codex protocol inspection: ${failure.message.orEmpty()}")
      }
    return try {
      val incompatible = inspect(directory, launch)
      if (incompatible != null) incompatible else runSession(launch)
    } finally {
      catchingNonCancellation {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }.onFailure { diagnostics.warning("seam=codex_schema outcome=cleanup_failed detail=${it.message}") }
    }
  }

  private fun inspect(
    directory: Path,
    launch: CodexSessionLaunch,
  ): AgentRunProcessResult? {
    val executable = request.launch.command.first()
    val version = probe(listOf(executable, "--version"))
    if (!version.usable()) return version
    val expected = Regex("(?:^|\\s)codex-cli ${Regex.escape(CODEX_PROTOCOL_VERSION)}(?:$|\\s)")
    if (!expected.containsMatchIn(version.stdout.trim())) {
      return refused(
        "The installed Codex CLI is incompatible with the supported " +
          "$CODEX_PROTOCOL_VERSION app-server.",
      )
    }
    val generated =
      probe(
        listOf(executable, "app-server", "generate-json-schema", "--experimental", "--out", "$directory"),
      )
    if (!generated.usable()) return generated
    return CodexSchemaAdmission.inspect(
      directory,
      launch.model,
      launch.effort,
      launch.workspaceRoots.isNotEmpty(),
      launch.readOnly,
    )?.let(::refused)
  }

  private fun probe(command: List<String>): AgentRunProcessResult {
    val remaining = remainingRequest() ?: return expired()
    val limit = remaining.timing.timeout?.coerceAtMost(PROBE_LIMIT) ?: PROBE_LIMIT
    val probe =
      remaining.copy(
        launch = AgentRunProcessLaunchFields(command, request.launch.workingDirectory),
        timing = remaining.timing.copy(timeout = limit),
        probes = AgentRunProcessProbeFields(),
        approval = AgentRunProcessApprovalFields(),
      )
    val result = runner.run(probe)
    return if (result.usable()) {
      result
    } else {
      diagnostics.warning("seam=codex_schema outcome=probe_failed")
      result.copy(
        stderr = "Could not inspect the installed Codex app-server protocol. ${result.stderr}",
        sessionOutcome = AgentRunSessionOutcome("", null, null, false),
      )
    }
  }

  private fun runSession(launch: CodexSessionLaunch): AgentRunProcessResult {
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

  private fun refused(reason: String): AgentRunProcessResult {
    diagnostics.warning("seam=codex_session outcome=admission_refused detail=$reason")
    request.launch.outputSink.write(AgentRunOutputStream.STDERR, "$reason\n")
    return AgentRunProcessResult(null, "", stderr = reason, timedOut = false, interrupted = false, spawnFailed = true)
  }

  private fun expired(): AgentRunProcessResult =
    AgentRunProcessResult(
      null,
      "",
      stderr = "Codex session admission exhausted the original run deadline.",
      timedOut = true,
      interrupted = false,
      spawnFailed = false,
    )

  private companion object {
    val PROBE_LIMIT = 30.seconds
    const val CONTINUATION_PROMPT =
      "Continue the same task from the current session and worktree. The " +
        "previous response reached its " +
        "output-token limit. Preserve completed edits and continue the unfinished " +
        "work. " +
        "Return the required final output only after the work is complete."
  }
}

private fun AgentRunProcessResult.usable(): Boolean =
  exitStatus == 0 && !spawnFailed && !timedOut && !interrupted && !outputCaptureIncomplete && !stdoutTruncated &&
    spawnDenied == null
