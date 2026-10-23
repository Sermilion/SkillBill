package skillbill.infrastructure.launcher.work

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.agentrun.workRunHarnessCommand
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
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
  private val projectStore: ProjectStorePort,
) : WorkRunLauncher {
  @Inject
  constructor(environment: EnvironmentContext, diagnostics: RuntimeDiagnostics, projectStore: ProjectStorePort) :
    this(environment, diagnostics, STARTUP_GRACE, projectStore)

  override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
    val launchEnvironment = launchEnvironment(request)
    val launch =
      when (val resolved = installedLaunch(request, launchEnvironment)) {
        is InstalledLaunch.Refused -> return refuse(resolved.reason)
        is InstalledLaunch.Ready -> resolved
      }
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
          startInstalled(launch, request, owner, launchEnvironment)
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

  private fun launchEnvironment(request: WorkRunLaunchRequest): Map<String, String> =
    environment.environment + request.agentEnvironment.filterKeys { it in AgentHomeEnvironmentKeys.ALL } +
      ("PATH" to searchDirectories().joinToString(pathSeparator().toString()))

  private fun installedLaunch(
    request: WorkRunLaunchRequest,
    launchEnvironment: Map<String, String>,
  ): InstalledLaunch =
    when (val kind = request.kind) {
      is WorkRunLaunchKind.AgentHarness -> harnessLaunch(request, kind, launchEnvironment)
      is WorkRunLaunchKind.RuntimeCommand ->
        resolveExecutable(listOf(RUNTIME_EXECUTABLE))
          ?.let { InstalledLaunch.Ready(listOf(it.toString()) + kind.arguments) }
          ?: InstalledLaunch.Refused("The $RUNTIME_EXECUTABLE runtime is not installed or is not on PATH.")
    }

  private fun harnessLaunch(
    request: WorkRunLaunchRequest,
    harness: WorkRunLaunchKind.AgentHarness,
    launchEnvironment: Map<String, String>,
  ): InstalledLaunch {
    val agent =
      SupportedAgent.entries.firstOrNull { it.id == harness.agentId }
        ?: return InstalledLaunch.Refused("The selected agent is not supported.")
    val executable =
      resolveExecutable(requireNotNull(AGENT_LAUNCHER_CLIS[agent]).executables)
        ?: return InstalledLaunch.Refused("The ${agent.id} harness is not installed or is not on PATH.")
    val built =
      workRunHarnessCommand(
        request,
        harness,
        agent,
        launchEnvironment,
        projectStore.resolve(request.repositoryRoot).root,
      )
    return InstalledLaunch.Ready(
      listOf(executable.toString()) + built.command.drop(1),
      built.stdinText,
      built.environment,
    )
  }

  private fun startInstalled(
    built: InstalledLaunch.Ready,
    request: WorkRunLaunchRequest,
    owner: Path,
    launchEnvironment: Map<String, String>,
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
      val process = startProcess(built.copy(command = command), request, input, log, launchEnvironment)
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
    built: InstalledLaunch.Ready,
    request: WorkRunLaunchRequest,
    input: Path?,
    log: Path,
    launchEnvironment: Map<String, String>,
  ): Process =
    ProcessBuilder(built.command)
      .directory(request.repositoryRoot.toFile())
      .redirectInput(ProcessBuilder.Redirect.from((input ?: Path.of(if (isWindows()) "NUL" else "/dev/null")).toFile()))
      .redirectErrorStream(true).redirectOutput(log.toFile())
      .apply {
        environment().putAll(launchEnvironment)
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

  private fun resolveExecutable(executables: List<String>): Path? {
    val directories = searchDirectories()
    return executables.flatMap { name -> directories.map { it.resolve(name) } }
      .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
  }

  private fun searchDirectories(): List<Path> =
    (
      listOf(environment.userHome.resolve(".local/bin")) +
        environment.environment["PATH"].orEmpty().split(pathSeparator()).filter(String::isNotBlank).map(Path::of)
    ).distinct()

  private fun pathSeparator(): Char = if (isWindows()) ';' else ':'

  private companion object {
    val STARTUP_GRACE: Duration = Duration.ofMillis(250)
    const val RUNTIME_EXECUTABLE = "skill-bill"
  }

  private fun isWindows(): Boolean = environment.environment["OS"] == "Windows_NT"

  private fun refuse(reason: String): WorkRunLaunchResult.Refused {
    diagnostics.info("seam=work-run.launch outcome=refused reason=$reason")
    return WorkRunLaunchResult.Refused(reason)
  }
}

private sealed interface InstalledLaunch {
  data class Ready(
    val command: List<String>,
    val stdinText: String? = null,
    val environment: Map<String, String> = emptyMap(),
  ) : InstalledLaunch

  data class Refused(val reason: String) : InstalledLaunch
}
