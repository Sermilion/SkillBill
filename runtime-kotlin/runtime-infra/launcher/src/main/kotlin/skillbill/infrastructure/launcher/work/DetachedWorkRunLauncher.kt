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
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
class DetachedWorkRunLauncher(
  private val environment: EnvironmentContext,
  private val diagnostics: RuntimeDiagnostics,
) : WorkRunLauncher {
  override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
    val agent =
      SupportedAgent.entries.firstOrNull { it.id == request.agentId }
        ?: return refuse("The selected agent is not supported.")
    val executable =
      resolveExecutable(agent)
        ?: return refuse("The ${agent.id} harness is not installed or is not on PATH.")
    val built = workRunHarnessCommand(request, agent, environment.environment)
    return startInstalled(built.copy(command = listOf(executable.toString()) + built.command.drop(1)), request)
  }

  private fun startInstalled(
    built: AgentRunCommand,
    request: WorkRunLaunchRequest,
  ): WorkRunLaunchResult {
    val directory = environment.userHome.resolve(".skill-bill/desktop/runs")
    var input: Path? = null
    var channel: FileChannel? = null
    var handedOff = false
    return try {
      Files.createDirectories(directory)
      val digest =
        MessageDigest.getInstance("SHA-256")
          .digest((request.databasePath.toString() + request.ownershipKey).toByteArray())
          .joinToString("") { "%02x".format(it) }
      channel = FileChannel.open(directory.resolve("$digest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock =
        try {
          channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        }
      if (lock == null) {
        return refuse("A run is already starting or running for this work.")
      }
      val log = directory.resolve("${UUID.randomUUID()}.log")
      input =
        built.stdinText?.let { prompt ->
          Files.createTempFile(directory, "prompt-", ".txt").also { Files.writeString(it, prompt) }
        }
      val process = startProcess(built, request, input, log)
      val ownedChannel = channel
      val ownedInput = input
      process.onExit().whenComplete { finished, error ->
        try {
          if (error != null || finished?.exitValue() != 0) {
            diagnostics.warning("seam=work-run.launch outcome=runner_failed log=$log", error)
          }
        } finally {
          try {
            lock.release()
          } finally {
            ownedChannel.close()
            ownedInput?.let(Files::deleteIfExists)
          }
        }
      }
      handedOff = true
      if (process.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
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
        channel?.close()
        input?.let(Files::deleteIfExists)
      }
    }
  }

  private fun startProcess(
    built: AgentRunCommand,
    request: WorkRunLaunchRequest,
    input: Path?,
    log: Path,
  ): Process =
    ProcessBuilder(launchCommand(built.command))
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
    const val STARTUP_GRACE_MS = 250L
  }

  private fun isWindows(): Boolean = environment.environment["OS"] == "Windows_NT"

  private fun refuse(reason: String): WorkRunLaunchResult.Refused {
    diagnostics.info("seam=work-run.launch outcome=refused reason=$reason")
    return WorkRunLaunchResult.Refused(reason)
  }
}
