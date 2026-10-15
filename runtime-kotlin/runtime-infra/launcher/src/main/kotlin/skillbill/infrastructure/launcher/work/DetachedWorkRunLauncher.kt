package skillbill.infrastructure.launcher.work

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.infrastructure.launcher.agentrun.AgentRunCommand
import skillbill.infrastructure.launcher.agentrun.workRunHarnessCommand
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class DetachedWorkRunLauncher internal constructor(
  private val environment: EnvironmentContext,
  private val diagnostics: RuntimeDiagnostics,
  private val startupGrace: Duration,
) : WorkRunLauncher {
  @Inject
  constructor(environment: EnvironmentContext, diagnostics: RuntimeDiagnostics) :
    this(environment, diagnostics, STARTUP_GRACE)

  override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
    val agent =
      SupportedAgent.entries.firstOrNull { it.id == request.agentId }
        ?: return refuse("The selected agent is not supported.")
    val executable =
      resolveExecutable(agent)
        ?: return refuse("The ${agent.id} harness is not installed or is not on PATH.")
    val built = workRunHarnessCommand(request, agent, environment.environment)
    val ownership = ownership()
    return try {
      ownership.admitted(
        request.databasePath,
        request.ownershipKey,
        { refuse("A run is already starting or running for this work.") },
      ) { owner ->
        if (ownership.running(owner)) {
          refuse("A run is already starting or running for this work.")
        } else {
          startInstalled(built.copy(command = listOf(executable.toString()) + built.command.drop(1)), request, owner)
        }
      }
    } catch (failure: IOException) {
      diagnostics.warning("seam=work-run.launch outcome=ownership_failed", failure)
      refuse("The harness ownership could not be read or saved.")
    }
  }

  override fun stop(request: WorkRunStopRequest): WorkRunStopResult =
    try {
      ownership().admitted(
        request.databasePath,
        request.ownershipKey,
        { WorkRunStopResult.Refused("The harness launch is still being admitted. Try Cancel again.") },
      ) {
        ownership().stop(it)
      }
    } catch (failure: IOException) {
      diagnostics.warning("seam=work-run.cancel outcome=ownership_failed", failure)
      WorkRunStopResult.Refused("The harness ownership could not be read or stopped.")
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      diagnostics.warning("seam=work-run.cancel outcome=interrupted", failure)
      WorkRunStopResult.Refused("Stopping the harness was interrupted.")
    }

  private fun ownership() = WorkHarnessOwnership(environment.userHome.resolve(".skill-bill/desktop/runs"), diagnostics)

  private fun startInstalled(
    built: AgentRunCommand,
    request: WorkRunLaunchRequest,
    owner: Path,
  ): WorkRunLaunchResult {
    val directory = environment.userHome.resolve(".skill-bill/desktop/runs")
    var input: Path? = null
    var handedOff = false
    return try {
      Files.createDirectories(directory)
      val log = directory.resolve("${UUID.randomUUID()}.log")
      input =
        built.stdinText?.let { prompt ->
          Files.createTempFile(directory, "prompt-", ".txt").also { Files.writeString(it, prompt) }
        }
      val command = launchCommand(built.command)
      val process = startProcess(built.copy(command = command), request, input, log)
      val unit = command.firstOrNull { it.startsWith("--unit=") }?.removePrefix("--unit=")?.plus(".scope")
      try {
        ownership().save(owner, process, unit)
      } catch (failure: IOException) {
        ownership().stopStarted(process, unit)
        throw failure
      }
      var failedEarly = false
      try {
        failedEarly = process.waitFor(startupGrace.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() != 0
      } finally {
        if (!failedEarly) {
          releaseOnExit(process, request, log, input)
          handedOff = true
        }
      }
      if (failedEarly) {
        diagnostics.warning("seam=work-run.launch outcome=runner_failed log=$log")
        ownership().clear(owner, process.pid())
        return refuse("The runner exited before starting. See $log")
      }
      diagnostics.info("seam=work-run.launch outcome=started log=$log")
      WorkRunLaunchResult.Started(log)
    } catch (failure: IOException) {
      diagnostics.warning("seam=work-run.launch outcome=failed", failure)
      WorkRunLaunchResult.Refused("The runner could not start: ${failure.message}")
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      diagnostics.warning("seam=work-run.launch outcome=interrupted", failure)
      WorkRunLaunchResult.Refused("Starting the runner was interrupted.")
    } finally {
      if (!handedOff) {
        input?.let(Files::deleteIfExists)
      }
    }
  }

  private fun releaseOnExit(
    process: Process,
    request: WorkRunLaunchRequest,
    log: Path,
    input: Path?,
  ) {
    process.onExit().whenComplete { finished, error ->
      try {
        if (error != null || finished?.exitValue() != 0) {
          diagnostics.warning("seam=work-run.launch outcome=runner_failed log=$log", error)
        }
      } finally {
        try {
          ownership().admitted(
            request.databasePath,
            request.ownershipKey,
            { diagnostics.info("seam=work-run.launch outcome=owner_cleanup_deferred") },
          ) {
            ownership().clear(it, process.pid())
          }
          input?.let(Files::deleteIfExists)
        } catch (failure: IOException) {
          diagnostics.warning("seam=work-run.launch outcome=owner_cleanup_failed", failure)
        }
      }
    }
  }

  private fun startProcess(
    built: AgentRunCommand,
    request: WorkRunLaunchRequest,
    input: Path?,
    log: Path,
  ): Process =
    ProcessBuilder(built.command)
      .directory(request.repositoryRoot.toFile())
      .redirectInput(ProcessBuilder.Redirect.from((input ?: Path.of(if (isWindows()) "NUL" else "/dev/null")).toFile()))
      .redirectErrorStream(true).redirectOutput(log.toFile())
      .apply {
        environment().putAll(this@DetachedWorkRunLauncher.environment.environment)
        environment().putAll(built.environment)
        environment()[RuntimeDatabaseEnvironmentKeys.DATABASE_PATH] = request.databasePath.toString()
      }.start()

  private fun launchCommand(command: List<String>): List<String> {
    val scopeLauncher =
      if (environment.environment["XDG_RUNTIME_DIR"].isNullOrBlank()) {
        null
      } else {
        environment.environment["PATH"].orEmpty().split(':').filter(String::isNotBlank)
          .map { Path.of(it).resolve("systemd-run") }.firstOrNull { Files.isExecutable(it) && Files.isRegularFile(it) }
      }
    return scopeLauncher?.let {
      listOf(it.toString(), "--user", "--scope", "--quiet", "--unit=skillbill-run-${UUID.randomUUID()}") + command
    } ?: command
  }

  private fun resolveExecutable(agent: SupportedAgent): Path? {
    val directories =
      listOf(environment.userHome.resolve(".local/bin")) +
        environment.environment["PATH"].orEmpty().split(if (isWindows()) ';' else ':')
          .filter(String::isNotBlank).map(Path::of)
    val executables = requireNotNull(AGENT_LAUNCHER_CLIS[agent]).executables
    return executables.flatMap { name -> directories.map { it.resolve(name) } }
      .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
  }

  private companion object {
    val STARTUP_GRACE: Duration = Duration.ofMillis(250)
  }

  private fun isWindows(): Boolean = environment.environment["OS"] == "Windows_NT"

  private fun refuse(reason: String): WorkRunLaunchResult.Refused {
    diagnostics.info("seam=work-run.launch outcome=refused reason=$reason")
    return WorkRunLaunchResult.Refused(reason)
  }
}
