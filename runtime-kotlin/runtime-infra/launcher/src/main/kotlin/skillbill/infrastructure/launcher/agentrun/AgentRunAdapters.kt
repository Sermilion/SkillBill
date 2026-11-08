package skillbill.infrastructure.launcher.agentrun

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.agentrun.display.BuildCommandTap
import skillbill.infrastructure.launcher.agentrun.display.ReadableAgentOutputSink
import skillbill.infrastructure.launcher.agentrun.display.SerializedBuildObserver
import skillbill.infrastructure.launcher.process.codex.CodexPhaseSession
import skillbill.infrastructure.launcher.process.codex.CodexSessionChannel
import skillbill.infrastructure.launcher.process.codex.CodexSessionLaunch
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.CursorAcpLaunch
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpDependency
import skillbill.infrastructure.launcher.process.cursor.CursorSessionChannel
import skillbill.infrastructure.launcher.process.cursor.cursorAcpDependencyRefusal
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalChannelOpening
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalOpenResult
import skillbill.infrastructure.launcher.process.interaction.CLAUDE_APPROVAL_PROVIDER
import skillbill.infrastructure.launcher.process.interaction.ClaudeApprovalChannelOpening
import skillbill.infrastructure.launcher.process.interaction.ClaudeApprovalLaunch
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessApprovalFields
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
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.CursorMcpDependencyRequirement
import skillbill.ports.agentrun.model.MAX_REPORTED_LAUNCH_IDENTITIES
import skillbill.ports.agentrun.model.ReportedLaunchIdentity
import skillbill.ports.agentrun.model.ReportedLaunchIdentityReason
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.claudeProfileIdentity
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.LAUNCH_MODEL_TOKEN_PATTERN
import skillbill.workflow.taskruntime.model.skeleton.boundedSafeModelIdentity
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

internal sealed interface LauncherResolution {
  data class Resolved(val command: List<String>) : LauncherResolution

  data class Missing(val message: String) : LauncherResolution
}

private const val CURSOR_MCP_DEPENDENCY_ENV = "SKILL_BILL_CURSOR_MCP_DEPENDENCY"

private fun resolveCursorMcpDependency(
  request: CursorMcpDependencyRequirement,
  providerEnvironment: Map<String, String>,
): CursorAcpMcpDependency? {
  val configured = providerEnvironment[CURSOR_MCP_DEPENDENCY_ENV]
  val environmentRequirement =
    when (configured) {
      null -> CursorMcpDependencyRequirement.Unspecified
      "project-user-only" -> CursorMcpDependencyRequirement.ProjectUserOnly
      "team-dashboard-required" -> CursorMcpDependencyRequirement.TeamDashboardRequired
      else -> return null
    }
  if (request != CursorMcpDependencyRequirement.Unspecified &&
    environmentRequirement != CursorMcpDependencyRequirement.Unspecified && request != environmentRequirement
  ) {
    return null
  }
  return when (request.takeIf { it != CursorMcpDependencyRequirement.Unspecified } ?: environmentRequirement) {
    CursorMcpDependencyRequirement.Unspecified -> CursorAcpMcpDependency.Unspecified
    CursorMcpDependencyRequirement.ProjectUserOnly -> CursorAcpMcpDependency.ProjectUserOnly
    CursorMcpDependencyRequirement.TeamDashboardRequired -> CursorAcpMcpDependency.TeamDashboardRequired
  }
}

internal class ProcessAgentRunAdapter(
  val agent: SupportedAgent,
  private val commandBuilder: AgentRunCommandBuilder,
  private val processRunner: AgentRunProcessRunner,
  private val executableLookup: ExecutableLookup = PathExecutableLookup(),
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val approvals: AgentRunApprovalOwners? = null,
  private val inheritedEnvironment: Map<String, String> = emptyMap(),
  private val userHome: Path? = null,
) {
  val canHostApprovalCards: Boolean get() = commandBuilder.approvalProtocol != null

  fun launch(request: SkillRunRequest): AgentRunLaunchOutcome {
    approvalRefusal(request)?.let { return it }
    val built = commandBuilder.build(request)
    val cardRequiredCursor =
      built.approvalProtocol == AgentRunApprovalProtocol.CURSOR_ACP &&
        request.approval is AgentRunApprovalRequirement.Owned
    val cursorDependency =
      if (cardRequiredCursor) {
        resolveCursorMcpDependency(request.cursorMcpDependency, inheritedEnvironment)
          ?: run {
            diagnostics.warning("seam=agent_run outcome=cursor_capability_declaration_invalid provider=${agent.id}")
            return UnsupportedAgentRunLaunch(
              agent,
              "Cursor MCP dependency declaration is missing, invalid, or conflicts with " +
                "$CURSOR_MCP_DEPENDENCY_ENV; the phase was not started.",
            )
          }
      } else {
        CursorAcpMcpDependency.Unspecified
      }
    if (cardRequiredCursor) {
      cursorAcpDependencyRefusal(cursorDependency)?.let { reason ->
        diagnostics.warning("seam=agent_run outcome=cursor_capability_unsupported provider=${agent.id}")
        return UnsupportedAgentRunLaunch(agent, reason)
      }
    }
    return launchResolved(request, built, cursorDependency)
  }

  private fun approvalRefusal(request: SkillRunRequest): UnsupportedAgentRunLaunch? {
    if (request.approval is AgentRunApprovalRequirement.Unowned ||
      (request.approval is AgentRunApprovalRequirement.Owned && !canHostApprovalCards)
    ) {
      diagnostics.warning("seam=agent_run outcome=approval_unsupported provider=${agent.id}")
      val reason =
        (request.approval as? AgentRunApprovalRequirement.Unowned)?.let {
          "Agent '${agent.id}' launch requires operator approval facts; ${it.reason}, so it was not started."
        } ?: commandBuilder.approvalUnavailableReason
      return UnsupportedAgentRunLaunch(agent, reason)
    }
    return null
  }

  private fun launchResolved(
    request: SkillRunRequest,
    built: AgentRunCommand,
    cursorDependency: CursorAcpMcpDependency,
  ): AgentRunLaunchOutcome {
    val command =
      when (val resolution = resolveLauncherExecutable(built.command, commandBuilder.launcherCli)) {
        is LauncherResolution.Resolved -> built.copy(command = resolution.command)
        is LauncherResolution.Missing -> return unavailableLauncherFacts(request, built, resolution.message)
      }
    val displaySink =
      if (request.captureDisplayOutput) ReadableAgentOutputSink(agent.id, request.outputSink, diagnostics) else null
    val serializedObserver =
      request.buildObserver
        .takeIf { it !== AgentRunBuildObserver.NONE && command.kind == AgentRunCommandKind.AGENT }
        ?.let(::SerializedBuildObserver)
    val tap =
      serializedObserver
        ?.takeIf { command.streamsProviderEvents }
        ?.let { observer -> BuildCommandTap(agent.id, displaySink ?: request.outputSink, observer, clock, diagnostics) }
    val streamRequest =
      request.copy(
        outputSink = tap ?: displaySink ?: request.outputSink,
        buildObserver = serializedObserver ?: AgentRunBuildObserver.NONE,
        captureDisplayOutput = false,
      )
    try {
      val process = processRequest(command, streamRequest)
      when (command.approvalProtocol) {
        AgentRunApprovalProtocol.CURSOR_ACP ->
          return launchAcpSession(streamRequest, command, process, cursorDependency, AcpProvider.Cursor)
        AgentRunApprovalProtocol.JUNIE_ACP ->
          return launchAcpSession(streamRequest, command, process, cursorDependency, AcpProvider.Junie)
        else -> Unit
      }
      return launchOtherSession(streamRequest, command, process)
    } finally {
      tap?.finish()
      displaySink?.finish()
    }
  }

  private fun launchOtherSession(
    request: SkillRunRequest,
    command: AgentRunCommand,
    process: AgentRunProcessRequest,
  ): AgentRunLaunchOutcome {
    val result =
      if (command.approvalProtocol == AgentRunApprovalProtocol.CODEX_APP_SERVER) {
        val launch =
          CodexSessionLaunch(
            prompt = requireNotNull(command.stdinText),
            cwd = command.workingDirectory.toString(),
            model = request.modelOverride,
            effort = request.effortOverride,
            savedThread = null,
            outputSink = request.outputSink,
            workspaceRoots = request.additionalWritableDirectories.map { it.toAbsolutePath().normalize().toString() },
          )
        val approvalDeadline = approvalEnclosingDeadline(clock, request.timeout)
        CodexPhaseSession(process, processRunner, clock, diagnostics) { settings ->
          openCodexSession(settings, request, approvalDeadline)
        }.run(launch)
      } else {
        runWithDisplaySink(request, process)
      }
    result.spawnDenied?.let { denied -> return AgentRunLaunchDenied(agent, denied.pauseReason) }
    return launchFacts(request, command, result)
  }

  private fun runWithDisplaySink(
    request: SkillRunRequest,
    process: AgentRunProcessRequest,
  ): AgentRunProcessResult {
    if (!request.captureDisplayOutput) return processRunner.run(process)
    val displaySink = ReadableAgentOutputSink(agent.id, request.outputSink, diagnostics)
    val displayProcess =
      process.copy(
        launch =
          process.launch.copy(
            outputSink = displaySink,
            flushOutputStreamOnEnd = true,
            continuation =
              process.launch.continuation?.let { continueLaunch ->
                { result -> continueLaunch(result)?.copy(flushOutputStreamOnEnd = true) }
              },
          ),
      )
    return try {
      processRunner.run(displayProcess)
    } finally {
      displaySink.finish()
    }
  }

  private fun launchFacts(
    request: SkillRunRequest,
    command: AgentRunCommand,
    result: AgentRunProcessResult,
  ): AgentRunLaunchFacts {
    val decoded = decodeResult(command, result)

    require(result.spawnFailed != result.processStarted) {
      "AgentRunProcessRunner result must report exactly one of " +
        "spawnFailed/processStarted; got " +
        "spawnFailed=${result.spawnFailed}, processStarted=${result.processStarted}."
    }
    val normalizedStdout = decoded.text
    val processStdoutTruncated =
      result.stdoutTruncated || (result.outputCaptureIncomplete && normalizedStdout.isBlank())
    val decodedBodyBytes =
      if (result.sessionOutcome == null && normalizedStdout == result.stdout) {
        result.stdoutBytes
      } else {
        normalizedStdout.encodeToByteArray()
      }
    val execFailure = result.sessionOutcome == null && decoded.failureDiagnostic != null
    recordReportedLaunchIdentity(request, decoded.reportedIdentity)
    return AgentRunLaunchFacts(
      agent = agent,
      termination = result.termination(),
      stdout = normalizedStdout,
      stderr =
        if (execFailure && result.exitStatus != null && result.exitStatus != 0) {
          listOfNotNull(
            result.stderr.trimEnd(),
            decoded.failureDiagnostic,
          ).filter(String::isNotBlank).joinToString("\n")
        } else {
          result.stderr
        },
      liveness = result.liveness,
      processStarted = result.processStarted,
      mcpStartupObserved = result.mcpStartupObserved,
      stdoutTruncated = processStdoutTruncated || result.sessionCapture?.truncated == true,
      stdoutByteSize =
        if (result.sessionOutcome == null && processStdoutTruncated) {
          result.stdoutByteSize
        } else {
          decodedBodyBytes.size.toLong()
        },
      stdoutSha256 =
        if (result.sessionOutcome == null && processStdoutTruncated) {
          result.stdoutSha256
        } else {
          launcherSha256Hex(decodedBodyBytes)
        },
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
      assistantEventCount = decoded.assistantEventCount,
      rawOutputPreview = decoded.rawOutputPreview,
      reportedIdentity = decoded.reportedIdentity,
      sessionFailureEvidence = result.sessionFailureEvidence,
    )
  }

  private fun decodeResult(
    command: AgentRunCommand,
    result: AgentRunProcessResult,
  ): DecodedAgentRunOutput {
    val decoder = command.outputDecoder ?: commandBuilder.outputDecoder
    return catchingNonCancellation {
      result.sessionOutcome?.let { outcome ->
        DecodedAgentRunOutput(
          text =
            if (outcome.completed && result.exitStatus == 0 && result.sessionCapture?.complete == true) {
              outcome.text
            } else {
              result.sessionCapture?.text.orEmpty()
            },
          reportedIdentity =
            outcome.model?.takeIf(LAUNCH_MODEL_TOKEN_PATTERN::matches)?.let { model ->
              ReportedLaunchIdentity.Reported(setOf(model))
            } ?: ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.NOT_REPORTED),
          failureDiagnostic = outcome.failure,
        )
      } ?: decoder.decode(result.stdout)
    }.getOrElse { error ->
      if (!decoder.undecodable(error)) throw error

      DecodedAgentRunOutput(text = "", rawOutputPreview = result.stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS))
    }
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
        continuation = commandBuilder.continuation(command, diagnostics),
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
        environment =
          if (command.approvalProtocol == AgentRunApprovalProtocol.CODEX_APP_SERVER) {
            inheritedEnvironment + command.environment
          } else {
            command.environment
          },
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
    approval = AgentRunProcessApprovalFields(channel = approvalOpening(command, request)),
  )

  private fun approvalOpening(
    command: AgentRunCommand,
    request: SkillRunRequest,
  ): AgentRunApprovalChannelOpening? {
    val owned = request.approval as? AgentRunApprovalRequirement.Owned ?: return null
    return when (command.approvalProtocol) {
      AgentRunApprovalProtocol.CLAUDE_HOST -> {
        val owners = approvals ?: return refusedOpening("no approval authority is configured")
        ClaudeApprovalChannelOpening(
          authority = owners.authority,
          openRequest =
            ApprovalOwnerOpenRequest(
              checkoutRoot = command.workingDirectory,
              runId = owned.runId,
              provider = CLAUDE_APPROVAL_PROVIDER,
              executionMode = ApprovalExecutionMode.ACT,
              profileIdentity =
                claudeProfileIdentity(
                  command.environment[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]
                    ?: inheritedEnvironment[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR],
                  userHome,
                ),
              enclosingDeadline = approvalEnclosingDeadline(owners.clock, request.timeout),
            ),
          launch =
            ClaudeApprovalLaunch(
              openingPrompt = requireNotNull(command.stdinText) { "A hosted approval launch needs its prompt." },
              workingDirectory = command.workingDirectory.toString(),
              outputSink = request.outputSink,
            ),
          clock = owners.clock,
          diagnostics = diagnostics,
        )
      }
      AgentRunApprovalProtocol.CODEX_APP_SERVER,
      AgentRunApprovalProtocol.CURSOR_ACP,
      AgentRunApprovalProtocol.JUNIE_ACP,
      -> null
      null -> refusedOpening("agent '${agent.id}' cannot host approval cards for this launch")
    }
  }

  private fun launchAcpSession(
    request: SkillRunRequest,
    command: AgentRunCommand,
    process: AgentRunProcessRequest,
    dependency: CursorAcpMcpDependency,
    provider: AcpProvider,
  ): AgentRunLaunchOutcome {
    val launch =
      CursorAcpLaunch(
        command.workingDirectory.toString(),
        requireNotNull(command.stdinText),
        command.sessionModel,
        if (provider != AcpProvider.Cursor) {
          null
        } else {
          "agent"
        },
        mcpDependency = dependency,
        provider = provider,
      )
    val deadline = approvalEnclosingDeadline(clock, request.timeout)
    val result =
      processRunner.run(
        process.copy(
          launch =
            process.launch.copy(
              stdinText = null,
              session = AgentRunApprovalChannelOpening { openCursorSession(launch, request, command, deadline) },
            ),
          approval = AgentRunProcessApprovalFields(),
        ),
      )
    return result.spawnDenied?.let {
      AgentRunLaunchDenied(
        agent,
        it.pauseReason,
      )
    } ?: launchFacts(request, command, result)
  }

  private fun openCursorSession(
    launch: CursorAcpLaunch,
    request: SkillRunRequest,
    command: AgentRunCommand,
    deadline: Instant?,
  ): AgentRunApprovalOpenResult {
    val channel = CursorSessionChannel(launch, clock, request.outputSink, diagnostics)
    val owned =
      request.approval as? AgentRunApprovalRequirement.Owned
        ?: return AgentRunApprovalOpenResult.Opened(channel)
    val authority =
      approvals?.authority ?: return AgentRunApprovalOpenResult.Refused(
        "No approval authority is configured.",
      )
    val profile =
      (
        if (launch.provider == AcpProvider.Cursor) {
          command.environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR]
            ?: inheritedEnvironment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR]
        } else {
          null
        }
      ) ?: userHome?.toString().orEmpty()
    val openRequest =
      ApprovalOwnerOpenRequest(
        request.repoRoot,
        owned.runId,
        agent.id,
        ApprovalExecutionMode.ACT,
        profile,
        deadline,
      )
    return when (val opened = authority.open(openRequest, channel.effects)) {
      is ApprovalOwnerOpenResult.Opened -> {
        channel.bind(opened.session)
        AgentRunApprovalOpenResult.Opened(channel)
      }
      is ApprovalOwnerOpenResult.Refused -> AgentRunApprovalOpenResult.Refused(opened.reason)
    }
  }

  private fun openCodexSession(
    launch: CodexSessionLaunch,
    request: SkillRunRequest,
    deadline: Instant?,
  ): AgentRunApprovalOpenResult {
    val channel = CodexSessionChannel(launch, clock, diagnostics)
    val owned =
      request.approval as? AgentRunApprovalRequirement.Owned
        ?: return AgentRunApprovalOpenResult.Opened(channel)
    val authority =
      approvals?.authority
        ?: return AgentRunApprovalOpenResult.Refused("No approval authority is configured.")
    val ownerRequest =
      ApprovalOwnerOpenRequest(
        checkoutRoot = request.repoRoot,
        runId = owned.runId,
        provider = "codex",
        executionMode = ApprovalExecutionMode.ACT,
        profileIdentity = inheritedEnvironment[AgentHomeEnvironmentKeys.CODEX_HOME] ?: userHome?.toString().orEmpty(),
        enclosingDeadline = deadline,
      )
    return when (val opened = authority.open(ownerRequest, channel)) {
      is ApprovalOwnerOpenResult.Opened -> {
        channel.bind(opened.session)
        AgentRunApprovalOpenResult.Opened(channel)
      }
      is ApprovalOwnerOpenResult.Refused -> {
        channel.finish()
        AgentRunApprovalOpenResult.Refused(opened.reason)
      }
    }
  }

  private fun recordReportedLaunchIdentity(
    request: SkillRunRequest,
    reportedIdentity: ReportedLaunchIdentity,
  ) {
    when (reportedIdentity) {
      is ReportedLaunchIdentity.Unavailable -> {
        if (reportedIdentity.reason == ReportedLaunchIdentityReason.MALFORMED) {
          diagnostics.warning(
            "seam=reported_launch_identity value_expected=object value_used=malformed",
          )
        }
      }
      is ReportedLaunchIdentity.Reported -> {
        val expected = request.modelOverride
        val identities = reportedIdentity.identities
        if (expected != null && identities.size == 1 && identities.single() == expected) return
        diagnostics.warning(
          "seam=reported_launch_identity value_expected=${
            expected?.let(::boundedSafeModelIdentity) ?: "none"
          } value_used=${
            identities.map(::boundedSafeModelIdentity).sorted().joinToString(",")
          }",
        )
      }
    }
  }

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
  val reportedIdentity: ReportedLaunchIdentity =
    ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.NOT_REPORTED),
  val failureDiagnostic: String? = null,
  val continuationSessionId: String? = null,
)

interface AgentRunOutputDecoder {
  fun decode(stdout: String): DecodedAgentRunOutput

  /**
   * Decoder-declared classification of a decode failure. A decoder that owns a transport it cannot
   * always parse says so here; the launcher then degrades that launch to an empty harvest with a
   * bounded preview instead of promoting undecodable bytes to phase output. Decoders that treat
   * every failure as fatal return false and keep propagating.
   */
  fun undecodable(error: Throwable): Boolean

  companion object {
    val PLAIN = decoder { DecodedAgentRunOutput(it) }
    val JUNIE_TEXT =
      decoder { stdout ->
        DecodedAgentRunOutput(
          text = stdout,
          reportedIdentity = ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.UNSUPPORTED_AGENT),
        )
      }
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

        override fun undecodable(error: Throwable): Boolean = false
      }
  }
}

internal val structuredOutputMapper: ObjectMapper by lazy { ObjectMapper() }

private fun decodeClaudeJson(stdout: String): DecodedAgentRunOutput =
  try {
    val root = structuredOutputMapper.readTree(stdout.trim())
    DecodedAgentRunOutput(
      text = root.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
      reportedIdentity = reportedClaudeIdentity(root.path("modelUsage")),
    )
  } catch (_: JsonProcessingException) {
    DecodedAgentRunOutput(stdout)
  }

private fun decodeClaudeStreamJson(stdout: String): DecodedAgentRunOutput {
  val terminal =
    stdout.lineSequence()
      .filter(String::isNotBlank)
      .mapNotNull(::readJsonLineOrNull)
      .lastOrNull { event -> event.path("type").takeIf { it.isTextual }?.asText() == "result" }

      ?: return DecodedAgentRunOutput(
        text = "",
        rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS),
      )
  return DecodedAgentRunOutput(
    text = terminal.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    reportedIdentity = reportedClaudeIdentity(terminal.path("modelUsage")),
  )
}

private fun reportedClaudeIdentity(modelUsage: JsonNode): ReportedLaunchIdentity {
  if (modelUsage.isMissingNode || modelUsage.isNull) {
    return ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.NOT_REPORTED)
  }
  if (!modelUsage.isObject) {
    return ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.MALFORMED)
  }
  val identities =
    modelUsage.fieldNames().asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .take(MAX_REPORTED_LAUNCH_IDENTITIES)
      .toSet()
  if (identities.isEmpty()) {
    return ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.NOT_REPORTED)
  }
  return if (identities.all(LAUNCH_MODEL_TOKEN_PATTERN::matches)) {
    ReportedLaunchIdentity.Reported(identities)
  } else {
    ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.MALFORMED)
  }
}

private fun readJsonLineOrNull(line: String): JsonNode? =
  try {
    structuredOutputMapper.readTree(line)
  } catch (_: JsonProcessingException) {
    null
  }

private fun decodeCodexJsonl(stdout: String): DecodedAgentRunOutput {
  var text: String? = null
  var decodedEnvelope = false
  var failureDiagnostic: String? = null
  var sessionId: String? = null
  stdout.lineSequence().filter(String::isNotBlank).forEach { line ->

    readJsonLineOrNull(line)?.let { event ->
      decodedEnvelope = true
      val eventType = event.path("type").asText()
      when (eventType) {
        "thread.started" -> sessionId = event.path("thread_id").takeIf { it.isTextual }?.asText()
        "turn.completed" -> failureDiagnostic = null
      }
      event.path("item").path("text").takeIf { it.isTextual }?.asText()?.let { text = it }
      val failure =
        when (eventType) {
          "error" -> event.path("message")
          "turn.failed" -> event.path("error").path("message")
          else -> null
        }
      failure?.takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)?.let {
        failureDiagnostic = it.take(RAW_OUTPUT_PREVIEW_MAX_CHARS)
      }
    }
  }
  return DecodedAgentRunOutput(
    text = text ?: if (decodedEnvelope) "" else stdout,
    reportedIdentity = ReportedLaunchIdentity.Unavailable(ReportedLaunchIdentityReason.UNSUPPORTED_AGENT),
    failureDiagnostic = failureDiagnostic,
    continuationSessionId = sessionId?.takeIf { failureDiagnostic == CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE },
  )
}

internal const val RAW_OUTPUT_PREVIEW_MAX_CHARS = 2_000

internal data class AgentRunWorkerBinding(
  val databasePath: Path? = null,
  val runtimeMcpBin: Path? = null,
  val approvals: AgentRunApprovalOwners? = null,
  val userHome: Path? = null,
  val providerEnvironment: Map<String, String> = emptyMap(),
)

internal fun headlessAgentRunAdapters(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  workerBinding: AgentRunWorkerBinding = AgentRunWorkerBinding(),
  diagnostics: RuntimeDiagnostics,
  clock: Clock,
): Map<SupportedAgent, ProcessAgentRunAdapter> =
  listOf(
    ClaudeAgentRunCommandBuilder(
      providerEnvironment = workerBinding.providerEnvironment,
      databasePath = workerBinding.databasePath,
      runtimeMcpBin = workerBinding.runtimeMcpBin,
    ),
    CodexAgentRunCommandBuilder(
      databasePath = workerBinding.databasePath,
      runtimeMcpBin = workerBinding.runtimeMcpBin,
    ),
    JunieAgentRunCommandBuilder(databasePath = workerBinding.databasePath),
    CursorAgentRunCommandBuilder(databasePath = workerBinding.databasePath),
  ).associate { builder ->
    builder.agent to
      ProcessAgentRunAdapter(
        agent = builder.agent,
        commandBuilder = builder,
        processRunner = processRunner,
        clock = clock,
        executableLookup = executableLookup,
        diagnostics = diagnostics,
        approvals = workerBinding.approvals,
        inheritedEnvironment = workerBinding.providerEnvironment,
        userHome = workerBinding.userHome,
      )
  }

internal data class AgentRunApprovalOwners(
  val authority: ApprovalOwnerAuthority,
  val clock: Clock,
)

private fun refusedOpening(reason: String): AgentRunApprovalChannelOpening =
  AgentRunApprovalChannelOpening { AgentRunApprovalOpenResult.Refused("Operator approvals are unavailable: $reason.") }

private fun approvalEnclosingDeadline(
  clock: Clock,
  timeout: Duration?,
): Instant? = timeout?.let { clock.instant().plusMillis(it.inWholeMilliseconds) }
