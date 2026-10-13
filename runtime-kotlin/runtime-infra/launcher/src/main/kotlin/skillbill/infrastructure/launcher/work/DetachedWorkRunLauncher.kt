package skillbill.infrastructure.launcher.work

import me.tatarka.inject.annotations.Inject
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
    val executable =
      resolveExecutable()
        ?: return refuse("The Skill Bill runtime is not installed. Install it before starting a run.")
    return startInstalled(executable, request)
  }

  private fun startInstalled(
    executable: Path,
    request: WorkRunLaunchRequest,
  ): WorkRunLaunchResult {
    val directory = environment.userHome.resolve(".skill-bill/desktop/runs")
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
      val process =
        ProcessBuilder(
          launchCommand(executable, request),
        ).directory(request.repositoryRoot.toFile())
          .redirectInput(ProcessBuilder.Redirect.from(Path.of(if (isWindows()) "NUL" else "/dev/null").toFile()))
          .redirectErrorStream(true).redirectOutput(log.toFile())
          .apply { environment().putAll(this@DetachedWorkRunLauncher.environment.environment) }
          .start()
      val ownedChannel = channel
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
      if (!handedOff) channel?.close()
    }
  }

  private fun launchCommand(
    executable: Path,
    request: WorkRunLaunchRequest,
  ): List<String> {
    val command = listOf(executable.toString(), "--db", request.databasePath.toString()) + request.arguments
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

  private fun resolveExecutable(): Path? {
    val home = environment.userHome
    val installed =
      home.resolve(
        ".skill-bill/runtime/runtime-cli/bin/${if (isWindows()) "runtime-cli.bat" else "runtime-cli"}",
      )
    if (Files.isRegularFile(installed) && Files.isExecutable(installed)) return installed
    val candidates =
      listOf(home.resolve(".local/bin/skill-bill")) +
        environment.environment["PATH"].orEmpty().split(if (isWindows()) ';' else ':')
          .filter(String::isNotBlank).map { Path.of(it).resolve("skill-bill") }
    return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.also {
      diagnostics.info("seam=work-run.launch outcome=launcher_fallback executable=$it")
    }
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
