package skillbill.infrastructure.launcher.work

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationCardEnvironmentKeys
import skillbill.contracts.operationcard.OperationCardExitCodes
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentSelection
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostAgentHomesPort
import skillbill.ports.work.LaunchAgentHomesPort
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunExit
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
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

class DetachedWorkRunLauncher internal constructor(
  private val environment: EnvironmentContext,
  private val diagnostics: RuntimeDiagnostics,
  private val startupGrace: Duration,
  private val agentHomes: HostAgentHomesPort,
  private val awaitStartupExit: (Process, Duration) -> Boolean = { process, grace ->
    process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)
  },
) : WorkRunLauncher,
  LaunchAgentHomesPort {
  @Inject
  constructor(
    environment: EnvironmentContext,
    diagnostics: RuntimeDiagnostics,
    agentHomes: HostAgentHomesPort,
  ) : this(environment, diagnostics, STARTUP_GRACE, agentHomes)

  override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
    openCodeIdentityConflict(request)?.let { return refuse(it) }
    val launchEnvironment = launchEnvironment(request)
    val launch =
      when (val resolved = installedLaunch(request)) {
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

  private fun openCodeIdentityConflict(request: WorkRunLaunchRequest): String? {
    val accepted = request.openCodeIdentity?.takeIf { request.validateOpenCodeIdentity } ?: return null
    val currentEnvironment = environment.environment + agentHomes.current().filterValues(String::isNotBlank)
    val cleared = agentHomes.current().filterValues(String::isBlank).keys
    val pinnedProfile =
      request.pinnedAgentHomes?.takeIf { AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR in it }
        ?.get(AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR)
    val current =
      OpenCodeLaunchIdentity.capture(
        currentEnvironment - cleared,
        environment.userHome,
        request.repositoryRoot,
        pinnedProfile,
      )
    val conflict = accepted.conflictsWith(current)
    val inline = request.inlineOpenCodeConfig ?: environment.environment[OpenCodeEnvironmentKeys.CONFIG_CONTENT]
    val refusal =
      when {
        conflict != null ->
          "The accepted OpenCode identity conflicts at $conflict. Restore the accepted setting and resume again."
        accepted.inlineConfigPresent && inline == null ->
          "The accepted OpenCode inline configuration is unavailable. " +
            "Restore OPENCODE_CONFIG_CONTENT and resume again."
        else -> null
      }
    if (refusal != null) return refusal
    if (accepted.inlineConfigPresent && request.inlineOpenCodeConfig != null) {
      val supplied =
        OpenCodeLaunchIdentity.capture(
          currentEnvironment + (OpenCodeEnvironmentKeys.CONFIG_CONTENT to requireNotNull(request.inlineOpenCodeConfig)),
          environment.userHome,
          request.repositoryRoot,
        )
      if (accepted.inlineConfigFingerprint != supplied.inlineConfigFingerprint) {
        return "The accepted OpenCode inline configuration changed. Restore OPENCODE_CONFIG_CONTENT and resume again."
      }
    }
    return null
  }

  override fun agentHomes(): Map<String, String> =
    mergedEnvironment(emptyMap()).let { merged -> AgentHomeEnvironmentKeys.ALL.associateWith { merged[it].orEmpty() } }

  private fun launchEnvironment(request: WorkRunLaunchRequest): Map<String, String> {
    val pinned = request.pinnedAgentHomes?.filterKeys { it in AgentHomeEnvironmentKeys.ALL }
    val base =
      if (pinned == null) {
        mergedEnvironment(request.agentEnvironment)
      } else {
        environment.environment + pinned.filterValues(String::isNotBlank) - pinned.filterValues(String::isBlank).keys
      }
    val pinnedIdentity =
      request.openCodeIdentity?.let { applyOpenCodeIdentity(base, it, request.inlineOpenCodeConfig) } ?: base
    return pinnedIdentity + ("PATH" to searchDirectories().joinToString(pathSeparator().toString()))
  }

  private fun applyOpenCodeIdentity(
    base: Map<String, String>,
    identity: OpenCodeLaunchIdentity,
    inlineContent: String?,
  ): Map<String, String> =
    buildMap {
      putAll(base)
      setSelection(OpenCodeEnvironmentKeys.HOME, identity.home)
      setSelection(OpenCodeEnvironmentKeys.XDG_CONFIG_HOME, identity.configHome)
      setSelection(OpenCodeEnvironmentKeys.XDG_DATA_HOME, identity.dataHome)
      setSelection(OpenCodeEnvironmentKeys.XDG_CACHE_HOME, identity.cacheHome)
      setSelection(OpenCodeEnvironmentKeys.XDG_STATE_HOME, identity.stateHome)
      setSelection(
        AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR,
        identity.configDirectory,
      )
      setSelection(OpenCodeEnvironmentKeys.CONFIG_FILE, identity.configFile)
      setSelection(OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG, identity.disableProjectConfig)
      if (identity.inlineConfigPresent) {
        put(
          OpenCodeEnvironmentKeys.CONFIG_CONTENT,
          inlineContent ?: environment.environment[OpenCodeEnvironmentKeys.CONFIG_CONTENT]!!,
        )
      } else {
        remove(OpenCodeEnvironmentKeys.CONFIG_CONTENT)
      }
    }

  private fun MutableMap<String, String>.setSelection(
    key: String,
    selection: OpenCodeEnvironmentSelection,
  ) {
    if (selection.supplied) put(key, requireNotNull(selection.value)) else remove(key)
  }

  private fun mergedEnvironment(recorded: Map<String, String>): Map<String, String> {
    val hostChoice = this.agentHomes.current().filterKeys { it in AgentHomeEnvironmentKeys.ALL }
    val kept = recorded.filter { (key, value) -> key in AgentHomeEnvironmentKeys.ALL && value.isNotBlank() }
    val cleared = hostChoice.filterValues(String::isBlank).keys
    return environment.environment + kept + hostChoice.filterValues(String::isNotBlank) - cleared
  }

  private fun installedLaunch(request: WorkRunLaunchRequest): InstalledLaunch =
    when (val kind = request.kind) {
      is WorkRunLaunchKind.RuntimeCommand ->
        resolveExecutable(listOf(RUNTIME_EXECUTABLE))
          ?.let { InstalledLaunch.Ready(listOf(it.toString()) + kind.arguments) }
          ?: InstalledLaunch.Refused("The $RUNTIME_EXECUTABLE runtime is not installed or is not on PATH.")
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
      var exitedEarly: Int? = null
      var exit: CompletionStage<Process>? = null
      try {
        exitedEarly = process.takeIf { awaitStartupExit(it, startupGrace) }?.exitValue()
      } finally {
        if (exitedEarly == null || exitedEarly == 0) {
          exit = releaseOnExit(process, request, log, input)
          handedOff = true
        }
      }
      if (exitedEarly == OperationCardExitCodes.AWAITING_CONFIRMATION) {
        diagnostics.info("seam=work-run.launch outcome=awaiting_confirmation log=$log")
        ownership().clear(owner, process.pid())
        return WorkRunLaunchResult.AwaitingConfirmation(log)
      }
      if (exitedEarly != null && exitedEarly != 0) {
        diagnostics.warning("seam=work-run.launch outcome=runner_failed log=$log")
        ownership().clear(owner, process.pid())
        return refuse(
          "${failureLogLines(
            log,
          ).lastOrNull { it.isNotBlank() } ?: "The runner exited before starting."} Full log: $log",
        )
      }
      diagnostics.info("seam=work-run.launch outcome=started log=$log")
      WorkRunLaunchResult.Started(log, requireNotNull(exit).thenApply { workRunExit(it.exitValue(), log) })
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

  private fun workRunExit(
    code: Int,
    log: Path,
  ): WorkRunExit = WorkRunExit(code, if (code == 0) emptyList() else failureLogLines(log))

  private fun failureLogLines(log: Path): List<String> =
    try {
      String(Files.readAllBytes(log), Charsets.UTF_8).lines()
    } catch (failure: IOException) {
      diagnostics.warning("seam=work-run.launch outcome=failure_log_unreadable log=$log", failure)
      emptyList()
    }

  private fun releaseOnExit(
    process: Process,
    request: WorkRunLaunchRequest,
    log: Path,
    input: Path?,
  ): CompletionStage<Process> =
    process.onExit().whenComplete { finished, error ->
      try {
        if (error != null || finished?.exitValue() !in SUCCESSFUL_EXITS) {
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
        AgentHomeEnvironmentKeys.ALL
          .filterNot(launchEnvironment::containsKey)
          .forEach(environment()::remove)
        if (request.openCodeIdentity != null) {
          OpenCodeEnvironmentKeys.ALL
            .filterNot(launchEnvironment::containsKey)
            .forEach(environment()::remove)
        }
        environment().putAll(built.environment)
        environment()[RuntimeDatabaseEnvironmentKeys.DATABASE_PATH] = request.databasePath.toString()
        environment().remove(OperationCardEnvironmentKeys.LAUNCH_ID)
        request.operationLaunchId?.let { environment()[OperationCardEnvironmentKeys.LAUNCH_ID] = it }
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
    const val RUNTIME_EXECUTABLE = "sona"
    val SUCCESSFUL_EXITS = setOf(0, OperationCardExitCodes.AWAITING_CONFIRMATION)
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
