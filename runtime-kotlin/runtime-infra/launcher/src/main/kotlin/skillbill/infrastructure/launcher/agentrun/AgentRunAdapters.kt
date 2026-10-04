package skillbill.infrastructure.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessEnvironmentFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessProbeFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessReviewFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessTimingFields
import skillbill.infrastructure.launcher.process.support.launcherSha256Hex
import skillbill.infrastructure.launcher.review.CursorReviewStreamMalformedError
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.SupportedAgent
import skillbill.install.model.agentLauncherUnavailableMessage
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

internal sealed interface LauncherResolution {
  data class Resolved(val command: List<String>) : LauncherResolution

  data class Missing(val message: String) : LauncherResolution
}

internal class ProcessAgentRunAdapter(
  val agent: SupportedAgent,
  private val commandBuilder: AgentRunCommandBuilder,
  private val processRunner: AgentRunProcessRunner,
  private val executableLookup: ExecutableLookup = PathExecutableLookup(),
) {
  fun launch(request: SkillRunRequest): AgentRunLaunchOutcome {
    val built = commandBuilder.build(request)
    val command =
      when (val resolution = resolveLauncherExecutable(built.command, commandBuilder.launcherCli)) {
        is LauncherResolution.Resolved -> built.copy(command = resolution.command)
        is LauncherResolution.Missing -> return unavailableLauncherFacts(request, built, resolution.message)
      }
    val result = processRunner.run(processRequest(command, request))
    result.spawnDenied?.let { denied -> return AgentRunLaunchDenied(agent, denied.pauseReason) }
    return launchFacts(request, command, result)
  }

  private fun launchFacts(
    request: SkillRunRequest,
    command: AgentRunCommand,
    result: AgentRunProcessResult,
  ): AgentRunLaunchFacts {
    val decoder = command.outputDecoder ?: commandBuilder.outputDecoder
    val decoded =
      runCatching { decoder.decode(result.stdout) }.getOrElse { error ->
        if (!decoder.undecodable(error)) throw error

        DecodedAgentRunOutput(text = "", rawOutputPreview = result.stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS))
      }

    require(result.spawnFailed != result.processStarted) {
      "AgentRunProcessRunner result must report exactly one of spawnFailed/processStarted; got " +
        "spawnFailed=${result.spawnFailed}, processStarted=${result.processStarted}."
    }
    val normalizedStdout = decoded.text
    val decodedBodyBytes =
      if (normalizedStdout == result.stdout) {
        result.stdoutBytes
      } else {
        normalizedStdout.encodeToByteArray()
      }
    return AgentRunLaunchFacts(
      agent = agent,
      termination = result.termination(),
      stdout = normalizedStdout,
      stderr = result.stderr,
      liveness = result.liveness,
      processStarted = result.processStarted,
      mcpStartupObserved = result.mcpStartupObserved,
      stdoutTruncated = result.stdoutTruncated,
      stdoutByteSize = if (result.stdoutTruncated) result.stdoutByteSize else decodedBodyBytes.size.toLong(),
      stdoutSha256 = if (result.stdoutTruncated) result.stdoutSha256 else launcherSha256Hex(decodedBodyBytes),
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
      assistantEventCount = decoded.assistantEventCount,
      rawOutputPreview = decoded.rawOutputPreview,
    )
  }

  private fun resolveLauncherExecutable(
    command: List<String>,
    launcher: AgentLauncherCli,
  ): LauncherResolution {
    val requested = command.firstOrNull()
    return when {
      requested == null -> LauncherResolution.Missing("Agent '${agent.id}' produced an empty launch command.")
      executableLookup.onPath(requested) -> LauncherResolution.Resolved(command)
      requested !in launcher.executables ->
        LauncherResolution.Missing("Agent '${agent.id}' cannot be launched: '$requested' is not on PATH.")
      else -> resolveDeclaredAlternate(requested, command, launcher)
    }
  }

  private fun resolveDeclaredAlternate(
    requested: String,
    command: List<String>,
    launcher: AgentLauncherCli,
  ): LauncherResolution {
    val alternate =
      launcher.executables
        .firstOrNull { candidate -> candidate != requested && executableLookup.onPath(candidate) }
        ?: return LauncherResolution.Missing(agentLauncherUnavailableMessage(agent, requested, launcher.installHint))
    return LauncherResolution.Resolved(listOf(alternate) + command.drop(1))
  }

  private fun unavailableLauncherFacts(
    request: SkillRunRequest,
    command: AgentRunCommand,
    message: String,
  ) = AgentRunLaunchFacts(
    agent = agent,
    termination = AgentRunTermination.SpawnFailed,
    stdout = "",
    stderr = message,
    stdoutByteSize = 0,
    stdoutSha256 = launcherSha256Hex(ByteArray(0)),
    childSessionPath = command.workingDirectory.toString(),
    childSessionId = childSessionId(agent, request, command.workingDirectory),
  )

  private fun processRequest(
    command: AgentRunCommand,
    request: SkillRunRequest,
  ) = AgentRunProcessRequest(
    launch =
      AgentRunProcessLaunchFields(
        command = command.command,
        workingDirectory = command.workingDirectory,
        stdinText = command.stdinText,
        outputSink = request.outputSink,
      ),
    timing =
      AgentRunProcessTimingFields(
        timeout = command.timeout,
        progressIdleTimeout = request.progressIdleTimeout,
        operationDeadline = request.timeout,
      ),
    probes =
      AgentRunProcessProbeFields(
        progressProbe = request.progressProbe,
        declaredProgressProbe = request.declaredProgressProbe,
        mcpStartupProbe = request.mcpStartupProbe,
        progressEmitter = request.progressEmitter,
        activityProbe = WorktreeActivityProbe(command.workingDirectory),
        activityStampSink = request.activityStampSink,
        worktreeEditObserver = request.worktreeEditObserver,
        idlePolicy = command.idlePolicy,
      ),
    environmentFields =
      AgentRunProcessEnvironmentFields(
        environment = command.environment,
        inheritEnvironment = command.inheritEnvironment,
        environmentPassthroughKeys = command.environmentPassthroughKeys,
      ),
    review =
      AgentRunProcessReviewFields(
        conversationIsolation = command.conversationIsolation,
        reviewEvidenceBroker = request.reviewEvidenceBroker,
        reviewEvidenceEndpoint = request.reviewEvidenceEndpoint,
        spawnAuthorization = request.spawnAuthorization,
      ),
  )

  private fun childSessionId(
    agent: SupportedAgent,
    request: SkillRunRequest,
    workingDirectory: Path,
  ): String =
    buildString {
      append(agent.id)
      append(':')
      append(request.issueKey)
      request.subtaskId?.let { id ->
        append(":subtask-")
        append(id)
      }
      append(':')
      append(workingDirectory.fileName?.toString() ?: workingDirectory.toString())
    }
}

private fun AgentRunProcessResult.termination(): AgentRunTermination =
  when {
    spawnFailed -> AgentRunTermination.SpawnFailed
    interrupted -> AgentRunTermination.Interrupted
    timedOut -> AgentRunTermination.TimedOut
    else ->
      AgentRunTermination.Exited(
        requireNotNull(exitStatus) { "A settled agent process must report an exit status." },
      )
  }

data class DecodedAgentRunOutput(
  val text: String,
  val assistantEventCount: Int? = null,
  val rawOutputPreview: String? = null,
)

interface AgentRunOutputDecoder {
  fun decode(stdout: String): DecodedAgentRunOutput

  /**
   * Decoder-declared classification of a decode failure. A decoder that owns a transport it cannot
   * always parse says so here; the launcher then degrades that launch to an empty harvest with a
   * bounded preview instead of promoting undecodable bytes to phase output. Decoders that treat
   * every failure as fatal inherit the default and keep propagating.
   */
  fun undecodable(error: Throwable): Boolean = false

  companion object {
    val PLAIN = decoder { DecodedAgentRunOutput(it) }
    val CLAUDE_JSON = decoder { stdout -> decodeClaudeJson(stdout) }
    val CLAUDE_STREAM_JSON = decoder { stdout -> decodeClaudeStreamJson(stdout) }
    val CODEX_JSONL = decoder { stdout -> decodeCodexJsonl(stdout) }
    val CURSOR_STREAM_JSON: AgentRunOutputDecoder =
      object : AgentRunOutputDecoder {
        override fun decode(stdout: String): DecodedAgentRunOutput = decodeCursorStreamJson(stdout)

        override fun undecodable(error: Throwable): Boolean = error is CursorReviewStreamMalformedError
      }

    private fun decoder(body: (String) -> DecodedAgentRunOutput): AgentRunOutputDecoder =
      object : AgentRunOutputDecoder {
        override fun decode(stdout: String): DecodedAgentRunOutput = body(stdout)
      }
  }
}

internal val structuredOutputMapper: ObjectMapper by lazy { ObjectMapper() }

private fun decodeClaudeJson(stdout: String): DecodedAgentRunOutput =
  runCatching {
    val root = structuredOutputMapper.readTree(stdout.trim())
    DecodedAgentRunOutput(
      text = root.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    )
  }.getOrElse { DecodedAgentRunOutput(stdout) }

private fun decodeClaudeStreamJson(stdout: String): DecodedAgentRunOutput {
  val terminal =
    stdout.lineSequence()
      .filter(String::isNotBlank)
      .mapNotNull { line ->

        runCatching { structuredOutputMapper.readTree(line) }.getOrNull()
      }
      .lastOrNull { event -> event.path("type").takeIf { it.isTextual }?.asText() == "result" }

      ?: return DecodedAgentRunOutput(
        text = "",
        rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS),
      )
  return DecodedAgentRunOutput(
    text = terminal.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
  )
}

private fun decodeCodexJsonl(stdout: String): DecodedAgentRunOutput {
  var text: String? = null
  var decodedEnvelope = false
  stdout.lineSequence().filter(String::isNotBlank).forEach { line ->

    runCatching { structuredOutputMapper.readTree(line) }.getOrNull()?.let { event ->
      decodedEnvelope = true
      event.path("item").path("text").takeIf { it.isTextual }?.asText()?.let { text = it }
    }
  }
  return DecodedAgentRunOutput(
    text = text ?: if (decodedEnvelope) "" else stdout,
  )
}

internal const val RAW_OUTPUT_PREVIEW_MAX_CHARS = 2_000

internal fun headlessAgentRunAdapters(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
): Map<SupportedAgent, ProcessAgentRunAdapter> =
  listOf(
    ClaudeAgentRunCommandBuilder(databasePath = databasePath),
    CodexAgentRunCommandBuilder(databasePath = databasePath),
    JunieAgentRunCommandBuilder(databasePath = databasePath),
    CursorAgentRunCommandBuilder(databasePath = databasePath),
  ).associate { builder ->
    builder.agent to
      ProcessAgentRunAdapter(
        agent = builder.agent,
        commandBuilder = builder,
        processRunner = processRunner,
        executableLookup = executableLookup,
      )
  }
