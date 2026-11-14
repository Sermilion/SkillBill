package skillbill.infrastructure.launcher.process.launch

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.infrastructure.host.jvm.AgentGradleUserHome
import skillbill.infrastructure.host.jvm.GateJvmResolver
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.scratch.AgentRunScratchRoot
import skillbill.infrastructure.host.scratch.AgentRunScratchSpace
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalChannel
import skillbill.infrastructure.launcher.process.interaction.AgentRunApprovalOpenResult
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionChannel
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration.between
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

@Inject
class JvmAgentRunProcessRunner(
  private val clock: Clock,
  private val gateJvmResolver: GateJvmResolver,
  scratchRoot: AgentRunScratchRoot,
) : AgentRunProcessRunner {
  private val scratchSpace = AgentRunScratchSpace(scratchRoot.path, clock)

  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    request.review.reviewEvidenceEndpoint?.let(liveEndpoints::add)
    return try {
      scratchSpace.withScratch { directory ->
        runAgentRunContinuations(request.withScratchDirectory(directory), clock, ::runGoverned)
      }
    } finally {
      closeEndpoint(request)
    }
  }

  private fun AgentRunProcessRequest.withScratchDirectory(directory: Path?): AgentRunProcessRequest =
    run {
      if (environmentFields.explicitEnvironment) return this
      val machine = JdkHostPlatformPort.resolveEnvironment()
      val requested = environmentFields.environment
      val inherited = if (environmentFields.inheritEnvironment) machine + requested else requested
      copy(
        environmentFields =
          environmentFields.copy(
            environment =
              requested + scratchSpace.environment(directory, inherited) +
                AgentGradleUserHome.environment(inherited, machine),
          ),
      )
    }

  private fun runGoverned(request: AgentRunProcessRequest): AgentRunProcessResult {
    val approval =
      when (val opened = (request.launch.session ?: request.approval.channel)?.open()) {
        null -> null
        is AgentRunApprovalOpenResult.Refused -> return approvalRefused(opened.reason, request)
        is AgentRunApprovalOpenResult.Opened -> opened.channel
      }
    try {
      val result = runGovernedSpawn(request, approval)
      return if (approval is AgentRunSessionChannel && !result.processStarted) {
        settleSession(result, approval)
      } else {
        result
      }
    } finally {
      approval?.let { channel ->
        catchingNonCancellation { channel.finish() }
          .onFailure { failure -> agentRunTeardownLogger.warning(approvalFinishFailureMessage(failure)) }
      }
    }
  }

  private fun settleSession(
    result: AgentRunProcessResult,
    session: AgentRunSessionChannel,
  ): AgentRunProcessResult {
    val outcome = session.outcome()
    val capture = result.sessionCapture ?: session.capture()
    val failure = sessionFailureMessage(outcome)
    val captureFailure = capture.failureMessage(result.outputCaptureIncomplete)
    val sessionFailureEvidence =
      buildSessionFailureEvidence(
        result.stderr,
        capture,
        failure,
        captureFailure,
      )
    val success = sessionSuccessful(result, outcome, capture)
    val unsettled = result.timedOut || result.interrupted || result.spawnFailed
    return result.copy(
      exitStatus =
        if (unsettled || result.spawnDenied != null) {
          result.exitStatus
        } else if (success) {
          0
        } else {
          1
        },
      stderr =
        listOfNotNull(
          failure,
          result.stderr.takeIf(String::isNotBlank),
          capture.providerDiagnostics,
          captureFailure,
        ).joinToString("\n"),
      sessionOutcome =
        outcome ?: AgentRunSessionOutcome(
          "",
          null,
          null,
          false,
          failure,
        ),
      sessionCapture = capture,
      sessionFailureEvidence = sessionFailureEvidence,
      outputCaptureIncomplete = result.outputCaptureIncomplete || !capture.complete,
      stdoutTruncated = result.stdoutTruncated || capture.truncated,
    )
  }

  private fun sessionSuccessful(
    result: AgentRunProcessResult,
    outcome: AgentRunSessionOutcome?,
    capture: AgentRunSessionCapture,
  ): Boolean =
    listOf(
      outcome?.completed == true,
      outcome?.text?.isNotBlank() == true,
      capture.complete,
      !result.outputCaptureIncomplete,
      !result.stdoutTruncated,
    ).all { it }

  private fun approvalRefused(
    reason: String,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult {
    catchingNonCancellation { request.launch.outputSink.write(AgentRunOutputStream.STDERR, "sona: $reason\n") }
    return AgentRunProcessResult(
      exitStatus = null,
      stdout = "",
      stderr = reason,
      timedOut = false,
      interrupted = false,
      spawnFailed = true,
      processExitObservation = request.processLifecycle?.observation(),
    )
  }

  private fun runGovernedSpawn(
    request: AgentRunProcessRequest,
    approval: AgentRunApprovalChannel?,
  ): AgentRunProcessResult {
    var startedProcess: ProcessStart? = null
    var settled = false
    val authorized =
      try {
        (
          request.review.spawnAuthorization?.withAuthorization {
            startProcess(request).also { startedProcess = it }
          } ?: AgentRunSpawnAuthorizationResult.Authorized(startProcess(request).also { startedProcess = it })
        ).also { settled = true }
      } finally {
        if (!settled) {
          cleanupProcessStart(startedProcess, request.processLifecycle)
        }
      }
    return when (authorized) {
      is AgentRunSpawnAuthorizationResult.Denied -> {
        cleanupProcessStart(startedProcess, request.processLifecycle)
        spawnDenied(authorized, request)
      }
      is AgentRunSpawnAuthorizationResult.Authorized -> settleProcessStart(authorized.value, request, approval)
    }
  }

  private fun spawnDenied(
    denied: AgentRunSpawnAuthorizationResult.Denied,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult =
    AgentRunProcessResult(
      exitStatus = null,
      stdout = "",
      stderr = "",
      timedOut = false,
      interrupted = false,
      spawnFailed = false,
      spawnDenied = denied,
      processExitObservation = request.processLifecycle?.observation(),
    )

  private fun settleProcessStart(
    processStart: ProcessStart,
    request: AgentRunProcessRequest,
    approval: AgentRunApprovalChannel?,
  ): AgentRunProcessResult =
    when (processStart) {
      is ProcessStart.Failed -> spawnFailure(processStart.error, request)
      is ProcessStart.Started ->
        captureLaunchResourceOutcome {
          runStartedProcess(
            tree = processStart.tree,
            stdoutStream = processStart.tree.process.inputStream,
            stderrStream = processStart.tree.process.errorStream,
            request = request,
            approval = approval,
          )
        }.getOrElse { failure ->
          captureLaunchResourceOutcome {
            cleanupProcessStart(processStart, request.processLifecycle)
          }.getOrElse { cleanupFailure ->
            failure.addSuppressed(cleanupFailure)
          }
          throw failure
        }
    }

  companion object {
    private val liveProcesses = ConcurrentHashMap.newKeySet<AgentRunProcessTree>()
    private val liveEndpoints =
      ConcurrentHashMap.newKeySet<GovernedReviewEvidenceEndpointHandle>()

    init {
      Runtime.getRuntime().addShutdownHook(
        object : Thread("skill-bill-agent-run-shutdown") {
          override fun run() {
            reapLiveProcesses(liveProcesses.toList())
            liveEndpoints.toList().forEach { endpoint ->
              liveEndpoints.remove(endpoint)
              cleanupStep(
                onFailure = { failure ->
                  agentRunTeardownLogger.warning(
                    "skillbill agent run: endpoint teardown failed at shutdown; " +
                      "endpoint=${boundedTeardownFailureDetail(failure)}",
                  )
                },
              ) { endpoint.close() }
            }
          }
        },
      )
    }

    internal fun closeEndpoint(request: AgentRunProcessRequest) {
      val endpoint = request.review.reviewEvidenceEndpoint ?: return
      liveEndpoints.remove(endpoint)
      catchingNonCancellation { endpoint.close() }.onFailure { failure ->
        val sinkDelivered =
          catchingNonCancellation {
            request.launch.outputSink.write(
              AgentRunOutputStream.STDERR,
              "governed review evidence endpoint teardown failed: ${failure.message.orEmpty()}\n",
            )
          }
        if (sinkDelivered.isFailure) {
          val sinkFailure = sinkDelivered.exceptionOrNull()
          agentRunTeardownLogger.warning(
            "skillbill agent run: endpoint teardown failed and stderr sink rejected " +
              "the diagnostic; " +
              "endpoint=${boundedTeardownFailureDetail(failure)}; " +
              "sink=${boundedTeardownFailureDetail(sinkFailure ?: failure)}",
          )
        }
      }
    }

    private val agentRunTeardownLogger: Logger =
      Logger.getLogger("skillbill.agent.run.teardown")

    private fun boundedTeardownFailureDetail(failure: Throwable): String {
      val message = failure.message?.takeIf { it.isNotBlank() }
      return (message ?: failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty())
        .take(TEARDOWN_FAILURE_DETAIL_LIMIT)
    }

    private fun approvalFinishFailureMessage(failure: Throwable): String =
      "skillbill agent run: approval generation teardown failed; detail=${boundedTeardownFailureDetail(failure)}"

    private const val TEARDOWN_FAILURE_DETAIL_LIMIT = 240

    internal fun drainLiveProcesses(timeoutMillis: Long): Boolean {
      return settleOwnedTrees(liveProcesses.toList(), timeoutMillis, removeSettled = true)
    }

    internal fun reapLiveProcesses(trees: List<AgentRunProcessTree>) {
      settleOwnedTrees(trees, DESTROY_WAIT_TIMEOUT_MILLIS, removeSettled = true)
    }

    private fun settleOwnedTrees(
      trees: List<AgentRunProcessTree>,
      graceMillis: Long,
      removeSettled: Boolean,
    ): Boolean {
      var interrupted = Thread.interrupted()
      val startedAt = System.nanoTime()
      val budgetNanos = TimeUnit.MILLISECONDS.toNanos(graceMillis.coerceAtLeast(0L))
      val settleNanos = TimeUnit.MILLISECONDS.toNanos(DESTROY_WAIT_TIMEOUT_MILLIS).coerceAtMost(budgetNanos / 2)
      val graceDeadline = startedAt + budgetNanos - settleNanos
      val settleDeadline = startedAt + budgetNanos
      trees.forEach { tree -> treeResult("discovery") { tree.refresh() } }
      trees.forEach { tree -> treeResult("graceful_termination") { tree.signalGracefully() } }
      interrupted = awaitOwnedTrees(trees, graceDeadline, "graceful_wait", interrupted)
      trees.forEach { tree -> treeResult("forced_termination") { tree.signalForcibly() } }
      interrupted = awaitOwnedTrees(trees, settleDeadline, "forced_wait", interrupted)
      val stopped = trees.map { tree -> treeResult("exit_confirmation") { tree.confirmedExited() } == true }
      trees.zip(stopped).forEach { (tree, isStopped) ->
        if (isStopped && removeSettled) liveProcesses.remove(tree)
        if (!isStopped) {
          logTreeFailure(
            "tree_unsettled",
            IllegalStateException("owned process tree remains live or unconfirmed"),
          )
        }
      }
      if (interrupted) Thread.currentThread().interrupt()
      return stopped.all { it }
    }

    private fun awaitOwnedTrees(
      trees: List<AgentRunProcessTree>,
      deadline: Long,
      operation: String,
      alreadyInterrupted: Boolean,
    ): Boolean {
      var interrupted = alreadyInterrupted
      trees.forEach { tree ->
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
        if (!interrupted && remaining > 0L) {
          try {
            catchingNonCancellation { tree.awaitExit(remaining, TimeUnit.NANOSECONDS) }
              .onFailure { failure -> logTreeFailure(operation, failure) }
          } catch (failure: InterruptedException) {
            interrupted = true
            Thread.interrupted()
            logTreeFailure(operation, failure)
          }
        }
      }
      return interrupted
    }

    private fun <T> treeResult(
      operation: String,
      action: () -> T,
    ): T? =
      try {
        catchingNonCancellation(action).onFailure { failure -> logTreeFailure(operation, failure) }.getOrNull()
      } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        logTreeFailure(operation, failure)
        null
      }

    private fun logTreeFailure(
      operation: String,
      failure: Throwable,
    ) {
      agentRunTeardownLogger.warning(
        "skillbill agent run: process tree $operation failed; detail=${boundedTeardownFailureDetail(failure)}",
      )
    }
  }

  private fun runStartedProcess(
    tree: AgentRunProcessTree,
    stdoutStream: InputStream,
    stderrStream: InputStream,
    request: AgentRunProcessRequest,
    approval: AgentRunApprovalChannel?,
  ): AgentRunProcessResult {
    val process = tree.process
    val degradation = ProcessRunDegradationRecorder()
    val outputTracker = OutputObservationTracker(clock)
    val lifecycleEmitter = ProcessLifecycleEmitter(request, degradation)
    val resources =
      createProcessResources(
        ProcessResourceRequest(
          tree,
          stdoutStream,
          stderrStream,
          request,
          outputTracker,
          degradation,
          approval,
        ),
      )
    var mcpStartupObservedAtStart = false
    var waitResult: Result<ProcessWait>? = null
    var primaryFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var buildSampleBinding: AutoCloseable? = null
    try {
      buildSampleBinding = request.probes.buildObserver.bindBuildSamples(tree, degradation)
      mcpStartupObservedAtStart = request.probes.mcpStartupProbe.readStartupObserved(degradation).value == true
      resources.stdout.start()
      resources.stderr.start()
      openStdin(process, request, approval, degradation)
      lifecycleEmitter.emitStarted(process.isAlive)
      waitResult =
        try {
          Result.success(
            ProcessWaitLoop(tree, request, outputTracker, lifecycleEmitter, clock, degradation, approval).wait(),
          )
        } catch (interrupted: InterruptedException) {
          Result.failure(interrupted)
        }
    } catch (failure: IOException) {
      primaryFailure = failure
      rethrow(failure)
    } finally {
      val finishObservation = {
        request.probes.buildObserver.finishBuildObservation(clock.instant(), degradation, buildSampleBinding)
        exportRunDegradationEvidence(degradation, request.launch.outputSink)
      }
      cleanupFailure = releaseStartedProcessResources(resources, approval, waitResult, degradation, finishObservation)
      if (primaryFailure != null && cleanupFailure != null && cleanupFailure !== primaryFailure) {
        primaryFailure.addSuppressed(cleanupFailure)
      }
    }
    if (primaryFailure == null) cleanupFailure?.let(::rethrow)
    return buildRunResult(
      BuildRunResultInput(
        process = process,
        request = request,
        waitResult = requireNotNull(waitResult),
        outputTracker = outputTracker,
        lifecycleEmitter = lifecycleEmitter,
        lifetime = resources.lifetime,
        degradation = degradation,
        mcpStartupObservedAtStart = mcpStartupObservedAtStart,
        sessionExpected = approval is AgentRunSessionChannel,
        sessionOutcome = (approval as? AgentRunSessionChannel)?.outcome(),
        sessionCapture = (approval as? AgentRunSessionChannel)?.capture(),
      ),
    )
  }

  private fun releaseStartedProcessResources(
    resources: ProcessResources,
    approval: AgentRunApprovalChannel?,
    waitResult: Result<ProcessWait>?,
    degradation: ProcessRunDegradationRecorder,
    finishObservation: () -> Unit,
  ): Throwable? {
    var cleanupFailure: Throwable? = null
    try {
      resources.lifetime.drainExitedProcessOutputBeforeFinish()
      approval?.let { channel ->
        catchingNonCancellation { channel.finish() }
          .onFailure { failure -> degradation.recordCleanupFailure("approval_generation_finish", failure) }
      }
      cleanupFailure = catchingNonCancellation { resources.lifetime.release(waitResult) }.exceptionOrNull()
    } finally {
      finishObservation()
    }
    return cleanupFailure
  }

  private fun openStdin(
    process: Process,
    request: AgentRunProcessRequest,
    approval: AgentRunApprovalChannel?,
    degradation: ProcessRunDegradationRecorder,
  ) {
    if (approval == null) {
      writeAndCloseStdin(process, request.launch.stdinText, degradation)
      return
    }
    if (!approval.attach(process)) {
      degradation.recordStdinDeliveryFailure(IOException("approval channel could not write its opening frames"))
      approval.finish()
    }
  }

  private data class ProcessResources(
    val stdout: Utf8Drain,
    val stderr: Utf8Drain,
    val lifetime: ProcessRunLifetime,
  )

  private data class ProcessResourceRequest(
    val tree: AgentRunProcessTree,
    val stdoutStream: InputStream,
    val stderrStream: InputStream,
    val request: AgentRunProcessRequest,
    val outputTracker: OutputObservationTracker,
    val degradation: ProcessRunDegradationRecorder,
    val approval: AgentRunApprovalChannel?,
  )

  private fun createProcessResources(args: ProcessResourceRequest): ProcessResources {
    val stdout =
      Utf8Drain(
        input = args.stdoutStream,
        outputStream = AgentRunOutputStream.STDOUT,
        outputSink =
          if (args.approval is AgentRunSessionChannel) {
            AgentRunOutputSink.NONE
          } else {
            args.request.launch.outputSink
          },
        onChunkRead = { chunk ->
          args.outputTracker.markObserved()
          args.approval?.observe(chunk)
        },
        flushOutputStreamOnEnd = args.request.launch.flushOutputStreamOnEnd,
        onStreamEnd = {
          (args.approval as? AgentRunSessionChannel)?.let { session ->
            catchingNonCancellation { session.finish() }
              .onFailure { failure -> args.degradation.recordCleanupFailure("stdout_session_finish", failure) }
          }
        },
      )
    val stderr =
      Utf8Drain(
        input = args.stderrStream,
        outputStream = AgentRunOutputStream.STDERR,
        outputSink = args.request.launch.outputSink,
        onChunkRead = { args.outputTracker.markObserved() },
        flushOutputStreamOnEnd = args.request.launch.flushOutputStreamOnEnd,
      )
    return ProcessResources(
      stdout = stdout,
      stderr = stderr,
      lifetime =
        ProcessRunLifetime(
          args.tree,
          liveProcesses,
          stdout,
          stderr,
          args.degradation,
          args.request.processLifecycle,
        ),
    )
  }

  private fun rethrow(failure: Throwable): Nothing = throw failure

  private data class BuildRunResultInput(
    val process: Process,
    val request: AgentRunProcessRequest,
    val waitResult: Result<ProcessWait>,
    val outputTracker: OutputObservationTracker,
    val lifecycleEmitter: ProcessLifecycleEmitter,
    val lifetime: ProcessRunLifetime,
    val degradation: ProcessRunDegradationRecorder,
    val mcpStartupObservedAtStart: Boolean,
    val sessionExpected: Boolean,
    val sessionOutcome: AgentRunSessionOutcome?,
    val sessionCapture: AgentRunSessionCapture?,
  )

  private fun buildRunResult(input: BuildRunResultInput): AgentRunProcessResult {
    val release = input.lifetime.cachedRelease()
    val wait = input.waitResult.getOrNull()
    val processOutcome = input.lifetime.terminalOutcome(release, wait)
    val session =
      sessionSettlement(
        input.sessionOutcome,
        input.sessionCapture,
        input.sessionExpected,
        release,
        input.degradation.snapshot(),
      )
    val sessionCapture = session.capture
    val sessionOutcome = session.outcome
    val sessionFailure = session.failed
    val terminalOutcome =
      when {
        release.interrupted -> GoalProgressOutcome.CANCELLED
        wait?.finished != true -> GoalProgressOutcome.TIMED_OUT
        sessionFailure -> GoalProgressOutcome.FAILED
        else -> processOutcome
      }
    input.lifecycleEmitter.emitCompleted(processAlive = false, outcome = terminalOutcome)
    val mcpStartupObserved =
      input.mcpStartupObservedAtStart ||
        input.request.probes.mcpStartupProbe.readStartupObserved(input.degradation).value == true
    if (release.interrupted) {
      return interruptedResult(
        release = release,
        outputTracker = input.outputTracker,
        mcpStartupObserved = mcpStartupObserved,
        degradation = input.degradation,
        session = session,
      ).copy(processExitObservation = input.request.processLifecycle?.observation())
    }
    val stdout = release.stdoutCapture
    val stderr = release.stderrCapture
    val settledWait = requireNotNull(wait)
    val stderrText =
      listOfNotNull(
        session.failure,
        stderr.text.withTimeoutMessage(settledWait, input.request).takeIf(String::isNotBlank),
        sessionCapture?.providerDiagnostics,
        session.captureFailure,
      ).joinToString("\n")
    return AgentRunProcessResult(
      exitStatus = settledExitStatus(input, settledWait, session),
      stdout = stdout.text,
      stdoutBytes = stdout.bytes,
      stderr = input.degradation.appendToStderr(stderrText),
      timedOut = !settledWait.finished,
      interrupted = false,
      spawnFailed = false,
      liveness = settledWait.liveness,
      processStarted = true,
      mcpStartupObserved = mcpStartupObserved,
      stdoutTruncated = sessionCapture?.truncated == true,
      stdoutByteSize = stdout.totalByteSize,
      stdoutSha256 = stdout.sha256,
      outputCaptureIncomplete =
        release.outputCaptureIncomplete || sessionCapture?.complete == false || session.cleanupFailure != null,
      sessionOutcome = sessionOutcome,
      sessionCapture = sessionCapture,
      sessionFailureEvidence = session.failureEvidence,
      processExitObservation = input.request.processLifecycle?.observation(),
    )
  }

  private fun settledExitStatus(
    input: BuildRunResultInput,
    wait: ProcessWait,
    session: ProcessSessionSettlement,
  ): Int? =
    when {
      input.sessionExpected && !wait.finished -> null
      input.sessionExpected -> wait.naturalExitStatus?.takeIf { it != 0 } ?: if (session.failed) 1 else 0
      wait.sessionSettled -> 0
      wait.finished -> input.process.exitValue()
      else -> null
    }

  private fun interruptedResult(
    release: ProcessRunReleaseSnapshot,
    outputTracker: OutputObservationTracker,
    mcpStartupObserved: Boolean,
    degradation: ProcessRunDegradationRecorder,
    session: ProcessSessionSettlement,
  ): AgentRunProcessResult {
    val stdout = release.stdoutCapture
    val stderr = release.stderrCapture
    val interruptMessage = "Agent run interrupted by parent signal before completion."
    val sessionCapture = session.capture
    val stderrBody =
      stderr.text.let { existing ->
        listOfNotNull(
          session.failure,
          existing.takeIf(String::isNotBlank),
          sessionCapture?.providerDiagnostics,
          interruptMessage,
          session.captureFailure,
        )
          .joinToString("\n")
      }
    return AgentRunProcessResult(
      exitStatus = null,
      stdout = stdout.text,
      stdoutBytes = stdout.bytes,
      stderr = degradation.appendToStderr(stderrBody),
      timedOut = false,
      interrupted = true,
      spawnFailed = false,
      processStarted = true,
      mcpStartupObserved = mcpStartupObserved,
      liveness =
        AgentRunLivenessSnapshot(
          phase = "watchdog",
          reason = "parent_interrupted",
          processState = GoalRunnerProcessState.KILLED,
          lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
        ),
      stdoutTruncated = sessionCapture?.truncated == true,
      stdoutByteSize = stdout.totalByteSize,
      stdoutSha256 = stdout.sha256,
      outputCaptureIncomplete = release.outputCaptureIncomplete || sessionCapture?.complete == false,
      sessionOutcome = session.outcome,
      sessionCapture = sessionCapture,
      sessionFailureEvidence = session.failureEvidence,
    )
  }

  private fun spawnFailure(
    error: Exception,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult =
    AgentRunProcessResult(
      exitStatus = null,
      stdout = "",
      stderr = error.message.orEmpty(),
      timedOut = false,
      interrupted = false,
      spawnFailed = true,
      processExitObservation = request.processLifecycle?.observation(),
    )

  private fun startProcess(request: AgentRunProcessRequest): ProcessStart {
    val process =
      try {
        val builder = buildProcess(request)
        request.launch.beforeStart?.invoke()
        builder.start()
      } catch (error: IOException) {
        return ProcessStart.Failed(error)
      } catch (error: SecurityException) {
        return ProcessStart.Failed(error)
      }
    val tree = AgentRunProcessTree(process)
    request.processLifecycle?.started(process.pid())
    liveProcesses.add(tree)
    captureLaunchResourceOutcome {
      treeResult("initial_discovery") { tree.refresh() }
    }.getOrElse { failure ->
      captureLaunchResourceOutcome {
        cleanupProcessStart(ProcessStart.Started(tree), request.processLifecycle)
      }.getOrElse { cleanupFailure ->
        failure.addSuppressed(cleanupFailure)
      }
      throw failure
    }
    return ProcessStart.Started(tree)
  }

  private fun buildProcess(request: AgentRunProcessRequest): ProcessBuilder =
    ProcessBuilder(request.launch.command)
      .directory(request.launch.workingDirectory.toFile())
      .also { configureLaunchEnvironment(it, request, gateJvmResolver) }

  private fun cleanupProcessStart(
    start: ProcessStart?,
    processLifecycle: AgentRunProcessLifecycle?,
  ) {
    when (start) {
      is ProcessStart.Started -> {
        reapLiveProcesses(listOf(start.tree))
        processLifecycle?.exited(start.tree.process.pid(), start.tree.confirmedExited())
      }
      is ProcessStart.Failed, null -> Unit
    }
  }
}

internal fun runAgentRunContinuations(
  request: AgentRunProcessRequest,
  clock: Clock,
  run: (AgentRunProcessRequest) -> AgentRunProcessResult,
): AgentRunProcessResult {
  val startedAt = clock.instant()
  var current = request
  while (true) {
    val result = run(current)
    if (!result.canContinue()) return result
    val next = request.launch.continuation?.invoke(result) ?: return result
    val elapsed = between(startedAt, clock.instant()).toKotlinDuration().coerceAtLeast(Duration.ZERO)
    val timeout = request.timing.timeout?.minus(elapsed)
    val deadline = request.timing.operationDeadline?.minus(elapsed)
    if (timeout?.isPositive() == false || deadline?.isPositive() == false) {
      return result.copy(exitStatus = null, timedOut = true)
    }
    current =
      request.copy(
        launch = next.copy(outputSink = request.launch.outputSink, continuation = request.launch.continuation),
        timing = request.timing.copy(timeout = timeout, operationDeadline = deadline),
      )
  }
}

private fun AgentRunProcessResult.canContinue(): Boolean =
  when {
    timedOut || interrupted -> false
    spawnFailed || spawnDenied != null -> false
    outputCaptureIncomplete || stdoutTruncated -> false
    else -> exitStatus != null && exitStatus != 0
  }
