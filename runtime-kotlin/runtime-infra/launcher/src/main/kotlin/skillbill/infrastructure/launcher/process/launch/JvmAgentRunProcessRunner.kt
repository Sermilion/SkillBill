package skillbill.infrastructure.launcher.process.launch

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.failureCodeLabel
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.infrastructure.host.jvm.GateJvmResolver
import skillbill.infrastructure.launcher.process.waitloop.ProcessLifecycleEmitter
import skillbill.infrastructure.launcher.process.waitloop.ProcessWait
import skillbill.infrastructure.launcher.process.waitloop.ProcessWaitLoop
import skillbill.infrastructure.launcher.process.waitloop.readStartupObserved
import skillbill.infrastructure.launcher.process.waitloop.writeAndCloseStdin
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@Inject
class JvmAgentRunProcessRunner(
  private val clock: Clock,
  private val gateJvmResolver: GateJvmResolver,
) : AgentRunProcessRunner {
  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    request.review.reviewEvidenceEndpoint?.let(liveEndpoints::add)
    return try {
      runGoverned(request)
    } finally {
      closeEndpoint(request)
    }
  }

  private fun runGoverned(request: AgentRunProcessRequest): AgentRunProcessResult {
    var startedProcess: ProcessStart? = null
    val authorized =
      runCatching {
        request.review.spawnAuthorization?.withAuthorization {
          startProcess(request).also { startedProcess = it }
        } ?: AgentRunSpawnAuthorizationResult.Authorized(startProcess(request).also { startedProcess = it })
      }.getOrElse { failure ->
        cleanupProcessStart(startedProcess)
        throw failure
      }
    return when (authorized) {
      is AgentRunSpawnAuthorizationResult.Denied -> spawnDenied(authorized)
      is AgentRunSpawnAuthorizationResult.Authorized -> settleProcessStart(authorized.value, request)
    }
  }

  private fun spawnDenied(denied: AgentRunSpawnAuthorizationResult.Denied): AgentRunProcessResult =
    AgentRunProcessResult(
      exitStatus = null,
      stdout = "",
      stderr = "",
      timedOut = false,
      interrupted = false,
      spawnFailed = false,
      spawnDenied = denied,
    )

  private fun settleProcessStart(
    processStart: ProcessStart,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult =
    when (processStart) {
      is ProcessStart.Failed -> spawnFailure(processStart.error)
      is ProcessStart.Started ->
        runStartedProcess(
          process = processStart.process,
          stdoutStream = processStart.process.inputStream,
          stderrStream = processStart.process.errorStream,
          request = request,
        )
    }

  companion object {
    private val liveProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val liveEndpoints =
      ConcurrentHashMap.newKeySet<GovernedReviewEvidenceEndpointHandle>()

    init {
      Runtime.getRuntime().addShutdownHook(
        object : Thread("skill-bill-agent-run-shutdown") {
          override fun run() {
            reapLiveProcesses(liveProcesses.toList())
            liveEndpoints.toList().forEach { endpoint ->
              liveEndpoints.remove(endpoint)
              runCatching { endpoint.close() }
            }
          }
        },
      )
    }

    internal fun closeEndpoint(request: AgentRunProcessRequest) {
      val endpoint = request.review.reviewEvidenceEndpoint ?: return
      liveEndpoints.remove(endpoint)
      runCatching { endpoint.close() }.onFailure { failure ->
        val sinkDelivered =
          runCatching {
            request.launch.outputSink.write(
              AgentRunOutputStream.STDERR,
              "governed review evidence endpoint teardown failed: ${failure.message.orEmpty()}\n",
            )
          }
        if (sinkDelivered.isFailure) {
          val sinkFailure = sinkDelivered.exceptionOrNull()
          agentRunTeardownLogger.warning(
            "skillbill agent run: endpoint teardown failed and stderr sink rejected the diagnostic; " +
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

    private const val TEARDOWN_FAILURE_DETAIL_LIMIT = 240

    internal fun reapLiveProcesses(processes: List<Process>) {
      processes.forEach { process -> runCatching { process.destroy() } }
      processes.forEach { process ->
        runCatching { process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
      }
    }
  }

  private fun runStartedProcess(
    process: Process,
    stdoutStream: InputStream,
    stderrStream: InputStream,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult {
    val degradation = ProcessRunDegradationRecorder()
    val outputTracker = OutputObservationTracker(clock)
    val lifecycleEmitter = ProcessLifecycleEmitter(request, degradation)
    val resources =
      createProcessResources(
        ProcessResourceRequest(
          process,
          stdoutStream,
          stderrStream,
          request,
          outputTracker,
          degradation,
        ),
      )
    var mcpStartupObservedAtStart = false
    var waitResult: Result<ProcessWait>? = null
    var primaryFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    try {
      mcpStartupObservedAtStart = request.probes.mcpStartupProbe.readStartupObserved(degradation).value == true
      resources.stdout.start()
      resources.stderr.start()
      writeAndCloseStdin(process, request.launch.stdinText, degradation)
      lifecycleEmitter.emitStarted(process.isAlive)
      waitResult =
        runCatching {
          waitForProcess(process, request, outputTracker, lifecycleEmitter, degradation)
        }
      waitResult.exceptionOrNull()
        ?.takeUnless { it is InterruptedException }
        ?.let(::rethrow)
    } catch (failure: IOException) {
      primaryFailure = failure
      rethrow(failure)
    } finally {
      cleanupFailure = runCatching { resources.lifetime.release(waitResult) }.exceptionOrNull()
      exportRunDegradationEvidence(degradation, request.launch.outputSink)
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
      ),
    )
  }

  private data class ProcessResources(
    val stdout: Utf8Drain,
    val stderr: Utf8Drain,
    val lifetime: ProcessRunLifetime,
  )

  private data class ProcessResourceRequest(
    val process: Process,
    val stdoutStream: InputStream,
    val stderrStream: InputStream,
    val request: AgentRunProcessRequest,
    val outputTracker: OutputObservationTracker,
    val degradation: ProcessRunDegradationRecorder,
  )

  private fun createProcessResources(args: ProcessResourceRequest): ProcessResources {
    val stdout =
      Utf8Drain(
        input = args.stdoutStream,
        outputStream = AgentRunOutputStream.STDOUT,
        outputSink = args.request.launch.outputSink,
        onChunkRead = { args.outputTracker.markObserved() },
      )
    val stderr =
      Utf8Drain(
        input = args.stderrStream,
        outputStream = AgentRunOutputStream.STDERR,
        outputSink = args.request.launch.outputSink,
        onChunkRead = { args.outputTracker.markObserved() },
      )
    return ProcessResources(
      stdout = stdout,
      stderr = stderr,
      lifetime = ProcessRunLifetime(args.process, liveProcesses, stdout, stderr, args.degradation),
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
  )

  private fun buildRunResult(input: BuildRunResultInput): AgentRunProcessResult {
    val release = input.lifetime.cachedRelease()
    val wait = input.waitResult.getOrNull()
    val terminalOutcome = input.lifetime.terminalOutcome(release, wait)
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
      )
    }
    val stdout = release.stdoutCapture
    val stderr = release.stderrCapture
    val settledWait = requireNotNull(wait)
    val stderrText = stderr.text.withTimeoutMessage(settledWait, input.request)
    return AgentRunProcessResult(
      exitStatus = if (settledWait.finished) input.process.exitValue() else null,
      stdout = stdout.text,
      stdoutBytes = stdout.bytes,
      stderr = input.degradation.appendToStderr(stderrText),
      timedOut = !settledWait.finished,
      interrupted = false,
      spawnFailed = false,
      liveness = settledWait.liveness,
      processStarted = true,
      mcpStartupObserved = mcpStartupObserved,
      stdoutTruncated = false,
      stdoutByteSize = stdout.totalByteSize,
      stdoutSha256 = stdout.sha256,
      outputCaptureIncomplete = release.outputCaptureIncomplete,
    )
  }

  private fun interruptedResult(
    release: ProcessRunReleaseSnapshot,
    outputTracker: OutputObservationTracker,
    mcpStartupObserved: Boolean,
    degradation: ProcessRunDegradationRecorder,
  ): AgentRunProcessResult {
    val stdout = release.stdoutCapture
    val stderr = release.stderrCapture
    val interruptMessage = "Agent run interrupted by parent signal before completion."
    val stderrBody =
      stderr.text.let { existing ->
        if (existing.isBlank()) {
          interruptMessage
        } else {
          "$existing\n$interruptMessage"
        }
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
      stdoutTruncated = false,
      stdoutByteSize = stdout.totalByteSize,
      stdoutSha256 = stdout.sha256,
      outputCaptureIncomplete = release.outputCaptureIncomplete,
    )
  }

  private fun waitForProcess(
    process: Process,
    request: AgentRunProcessRequest,
    outputTracker: OutputObservationTracker,
    lifecycleEmitter: ProcessLifecycleEmitter,
    degradation: ProcessRunDegradationRecorder,
  ): ProcessWait = ProcessWaitLoop(process, request, outputTracker, lifecycleEmitter, clock, degradation).wait()

  private fun spawnFailure(error: Exception): AgentRunProcessResult =
    AgentRunProcessResult(
      exitStatus = null,
      stdout = "",
      stderr = error.message.orEmpty(),
      timedOut = false,
      interrupted = false,
      spawnFailed = true,
    )

  private fun startProcess(request: AgentRunProcessRequest): ProcessStart =
    try {
      ProcessStart.Started(buildProcess(request).start())
    } catch (error: IOException) {
      ProcessStart.Failed(error)
    } catch (error: SecurityException) {
      ProcessStart.Failed(error)
    }

  private fun buildProcess(request: AgentRunProcessRequest): ProcessBuilder =
    ProcessBuilder(request.launch.command)
      .directory(request.launch.workingDirectory.toFile())
      .also { configureLaunchEnvironment(it, request, gateJvmResolver) }

  private fun cleanupProcessStart(start: ProcessStart?) {
    when (start) {
      is ProcessStart.Started -> reapLiveProcesses(listOf(start.process))
      is ProcessStart.Failed, null -> Unit
    }
  }
}
