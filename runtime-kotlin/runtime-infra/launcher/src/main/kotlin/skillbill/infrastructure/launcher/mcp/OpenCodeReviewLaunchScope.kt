package skillbill.infrastructure.launcher.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessExitState
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLifecycle
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessTree
import skillbill.infrastructure.launcher.process.launch.captureLaunchResourceOutcome
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal class OpenCodeReviewLaunchScope(
  val root: Path,
  val cwd: Path,
  val environment: Map<String, String>,
  val logicalWorkerName: String,
  private val managedSources: List<Path>,
  val processLifecycle: AgentRunProcessLifecycle = AgentRunProcessLifecycle(),
) : AutoCloseable {
  fun verifyManagedSources() {
    managedSourceRefusal(managedSources)?.let { reason ->
      throw SkillBillRuntimeException(GovernedReviewFailureCode.LAUNCH_CAPABILITY, reason)
    }
  }

  fun supportsPinnedExecutable(executable: String): Boolean =
    try {
      verifyManagedSources()
      val versionOutput = root.resolve("version-output")
      writeOwnerOnly(versionOutput, ByteArray(0))
      val process =
        ProcessBuilder(executable, "--version").apply {
          directory(cwd.toFile())
          redirectErrorStream(true)
          redirectOutput(versionOutput.toFile())
          environment().clear()
          environment().putAll(this@OpenCodeReviewLaunchScope.environment)
        }.start()
      processLifecycle.started(process.pid())
      val tree = AgentRunProcessTree(process)
      var primaryFailure: Throwable? = null
      try {
        captureLaunchResourceOutcome {
          tree.refresh()
          val finished = process.waitFor(VERSION_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          if (!finished) {
            tree.refresh()
            tree.signalForcibly()
            tree.awaitExit(VERSION_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          }
          tree.refresh()
          val exited = tree.confirmedExited()
          processLifecycle.exited(process.pid(), exited)
          if (finished && exited) {
            val output = Files.newInputStream(versionOutput).use { it.readNBytes(MAX_VERSION_BYTES + 1) }
            process.exitValue() == 0 && output.size <= MAX_VERSION_BYTES &&
              output.toString(Charsets.UTF_8).trim() == PINNED_VERSION
          } else {
            false
          }
        }.getOrElse { failure ->
          primaryFailure = failure
          throw failure
        }
      } finally {
        cleanupVersionProbe(tree, primaryFailure)
      }
    } catch (_: IOException) {
      false
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      val cancellation = CancellationException(failure.message)
      throw cancellation
    }

  private fun cleanupVersionProbe(
    tree: AgentRunProcessTree,
    primaryFailure: Throwable?,
  ) {
    if (tree.confirmedExited()) return
    val failures = mutableListOf<Throwable>()
    var interrupted = false
    listOf<() -> Unit>(
      { tree.refresh() },
      { tree.signalForcibly() },
      { tree.awaitExit(VERSION_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) },
    ).forEach { cleanup ->
      captureLaunchResourceOutcome {
        cleanup()
      }.getOrElse { failure ->
        failures += failure
        interrupted = interrupted || failure is InterruptedException
        CLEANUP_LOGGER.warning(
          "seam=opencode_review_version_cleanup outcome=failed failure_type=${failure.javaClass.simpleName}",
        )
      }
    }
    processLifecycle.exited(tree.process.pid(), tree.confirmedExited())
    if (interrupted) Thread.currentThread().interrupt()
    val first = failures.firstOrNull() ?: return
    if (primaryFailure != null) {
      failures.filter { it !== primaryFailure }.forEach(primaryFailure::addSuppressed)
    } else {
      failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
      throw first
    }
  }

  override fun close() {
    release(null, AgentRunOutputSink.NONE)
  }

  fun release(
    diagnostics: RuntimeDiagnostics?,
    outputSink: AgentRunOutputSink,
  ) {
    if (releaseState == ReleaseState.RETAINED) {
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.LAUNCH_CAPABILITY,
        "The OpenCode review scope remains retained for owned process exit recovery.",
      )
    }
    retainUnconfirmedProcess(diagnostics, outputSink)
    if (releaseState == ReleaseState.REMOVED) return
    if (!root.toFile().deleteRecursively()) {
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.LAUNCH_CAPABILITY,
        "The private OpenCode review scope could not be removed.",
      )
    }
    releaseState = ReleaseState.REMOVED
  }

  private fun retainUnconfirmedProcess(
    diagnostics: RuntimeDiagnostics?,
    outputSink: AgentRunOutputSink,
  ) {
    val observation = processLifecycle.observation()
    if (observation.state == AgentRunProcessExitState.UNCONFIRMED) {
      releaseState = ReleaseState.RETAINED
      val evidence =
        "seam=opencode_review_cleanup disposition=retained lane=$logicalWorkerName root=$root " +
          "owned_process_ids=${observation.processIds.joinToString(",")} " +
          "action=confirm_owned_process_tree_exit_before_removing_root"
      val diagnosticFailure = captureLaunchResourceOutcome { diagnostics?.warning(evidence) }.exceptionOrNull()
      val sinkFailure =
        captureLaunchResourceOutcome {
          outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: $evidence\n")
        }.exceptionOrNull()
      if (diagnostics == null || diagnosticFailure != null || sinkFailure != null) {
        CLEANUP_LOGGER.warning("skill-bill OpenCode review cleanup evidence: $evidence")
      }
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.LAUNCH_CAPABILITY,
        "The OpenCode review scope was retained because owned process exit is unconfirmed.",
      )
    }
  }

  private enum class ReleaseState {
    OWNED,
    RETAINED,
    REMOVED,
  }

  @Volatile
  private var releaseState = ReleaseState.OWNED

  companion object {
    private const val VERSION_PROBE_TIMEOUT_SECONDS = 5L
    private const val PINNED_VERSION = "1.15.3"
    private const val MAX_VERSION_BYTES = 128
    private val mapper = ObjectMapper()
    private val CLEANUP_LOGGER = Logger.getLogger("skillbill.opencode.review.cleanup")

    fun prepare(
      request: SkillRunRequest,
      inheritedEnvironment: Map<String, String>,
      userHome: Path,
      hostPlatform: HostPlatformPort?,
    ): OpenCodeReviewPreparation {
      val host =
        hostPlatform
          ?: return OpenCodeReviewPreparation.Refused("The OpenCode host identity cannot be verified.")
      val managedSources =
        managedSourcePaths(host, inheritedEnvironment)
          ?: return OpenCodeReviewPreparation.Refused("This host has no verified OpenCode managed-policy semantics.")
      val refusal = managedSourceRefusal(managedSources)
      return if (refusal != null) {
        OpenCodeReviewPreparation.Refused(refusal)
      } else {
        prepareWorker(request, inheritedEnvironment, userHome, host, managedSources)
      }
    }

    private fun prepareWorker(
      request: SkillRunRequest,
      inheritedEnvironment: Map<String, String>,
      userHome: Path,
      host: HostPlatformPort,
      managedSources: List<Path>,
    ): OpenCodeReviewPreparation {
      val worker =
        readSelectedWorker(request, inheritedEnvironment, userHome)
          ?: return OpenCodeReviewPreparation.Refused(
            "The selected OpenCode API credential or native worker is unavailable.",
          )
      val base =
        Files.createTempDirectory(
          host.resolveTemporaryDirectory(),
          "skill-bill-opencode-review-",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
      captureLaunchResourceOutcome {
        if (base.toRealPath().startsWith(request.repoRoot.toRealPath())) {
          removePrivateRoot(base)
          return OpenCodeReviewPreparation.Refused("The private OpenCode review scope must be outside the repository.")
        }
        val environment = preparePrivateFiles(base, request, worker, inheritedEnvironment)
        return OpenCodeReviewPreparation.Ready(
          OpenCodeReviewLaunchScope(base, base.resolve("cwd"), environment, worker.name, managedSources),
        )
      }.getOrElse { failure ->
        captureLaunchResourceOutcome {
          removePrivateRoot(base)
        }.getOrElse { cleanupFailure ->
          failure.addSuppressed(cleanupFailure)
        }
        throw failure
      }
    }

    private fun readSelectedWorker(
      request: SkillRunRequest,
      inheritedEnvironment: Map<String, String>,
      userHome: Path,
    ): OpenCodeReviewWorker? {
      val workerName = requireNotNull(request.nativeReviewWorkerName)
      val providerName = requireNotNull(request.modelOverride).substringBefore('/')
      val identity = requireNotNull(request.openCodeIdentity)
      val sourceAuth = Path.of(identity.effectiveDataHome).resolve("opencode/auth.json")
      val auth = readSelectedApiAuth(sourceAuth, providerName) ?: return null
      val inventory = NativeAgentLinkInventory.read(userHome, emptyList(), environment = inheritedEnvironment)
      val entry =
        inventory.singleOrNull {
          it.provider == NativeAgentProvider.OpenCode.name.lowercase() && it.logicalName == workerName
        } ?: return null
      val bytes = NativeAgentLinkInventory.readSelectedArtifact(entry)
      val restrictedWorker = restrictWorker(bytes, workerName) ?: return null
      return OpenCodeReviewWorker(workerName, providerName, auth, restrictedWorker)
    }

    private fun preparePrivateFiles(
      base: Path,
      request: SkillRunRequest,
      worker: OpenCodeReviewWorker,
      inheritedEnvironment: Map<String, String>,
    ): Map<String, String> {
      val home = createPrivateDirectory(base.resolve("home"))
      createPrivateDirectory(base.resolve("cwd"))
      val configHome = createPrivateDirectory(base.resolve("config"))
      val dataHome = createPrivateDirectory(base.resolve("data"))
      val cacheHome = createPrivateDirectory(base.resolve("cache"))
      val stateHome = createPrivateDirectory(base.resolve("state"))
      val tempHome = createPrivateDirectory(base.resolve("tmp"))
      val configDir = createPrivateDirectory(configHome.resolve("opencode"))
      val workerDir = createPrivateDirectory(configDir.resolve("agents"))
      writePrivate(workerDir.resolve("${worker.name}.md"), worker.content)
      writePrivate(
        dataHome.resolve("opencode/auth.json"),
        mapper.writeValueAsBytes(mapOf(worker.provider to worker.auth)),
      )
      OpenCodeReviewConfigWriter.write(configDir.resolve("opencode.json"), request)
      return buildMap {
        OpenCodeReviewEnvironmentKeys.STARTUP_KEYS.forEach { key ->
          inheritedEnvironment[key]?.let { put(key, it) }
        }
        put(OpenCodeEnvironmentKeys.HOME, home.toString())
        put(OpenCodeEnvironmentKeys.XDG_CONFIG_HOME, configHome.toString())
        put(OpenCodeEnvironmentKeys.XDG_DATA_HOME, dataHome.toString())
        put(OpenCodeEnvironmentKeys.XDG_CACHE_HOME, cacheHome.toString())
        put(OpenCodeEnvironmentKeys.XDG_STATE_HOME, stateHome.toString())
        put(AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR, configDir.toString())
        put(OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG, "true")
        put(OpenCodeReviewEnvironmentKeys.DISABLE_EXTERNAL_SKILLS, "true")
        put(OpenCodeReviewEnvironmentKeys.DISABLE_CLAUDE_CODE, "true")
        put(OpenCodeReviewEnvironmentKeys.DISABLE_DEFAULT_PLUGINS, "true")
        put(OpenCodeReviewEnvironmentKeys.PURE_MODE, "true")
        put(OpenCodeReviewEnvironmentKeys.TMPDIR, tempHome.toString())
      }
    }

    private fun removePrivateRoot(base: Path) {
      if (!base.toFile().deleteRecursively()) {
        throw SkillBillRuntimeException(
          GovernedReviewFailureCode.LAUNCH_CAPABILITY,
          "The incomplete private OpenCode review scope could not be removed.",
        )
      }
    }

    private fun readSelectedApiAuth(
      path: Path,
      provider: String,
    ): Map<String, String>? {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) return null
      val root =
        try {
          mapper.readTree(Files.readAllBytes(path))
        } catch (_: IOException) {
          return null
        }
      val selected = root?.takeIf { it.isObject }?.get(provider)?.takeIf { it.isObject } ?: return null
      val type = selected.get(OpenCodeReviewConfigKeys.TYPE)?.takeIf { it.isTextual }?.asText()
      val key =
        selected.get(OpenCodeReviewConfigKeys.AUTH_KEY)?.takeIf { it.isTextual }?.asText()
          ?.takeIf(String::isNotBlank)
      return if (type == "api" && key != null) {
        mapOf(OpenCodeReviewConfigKeys.TYPE to type, OpenCodeReviewConfigKeys.AUTH_KEY to key)
      } else {
        null
      }
    }

    private fun createPrivateDirectory(path: Path): Path {
      Files.createDirectory(path)
      privateDirectory(path)
      return path
    }

    private fun privateDirectory(path: Path) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    private fun writePrivate(
      path: Path,
      bytes: ByteArray,
    ) {
      Files.createDirectories(path.parent)
      privateDirectory(path.parent)
      writeOwnerOnly(path, bytes)
    }

    private fun restrictWorker(
      bytes: ByteArray,
      workerName: String,
    ): ByteArray? {
      val text = bytes.toString(Charsets.UTF_8)
      if (!text.startsWith(FRONTMATTER_OPEN)) return null
      val end = text.indexOf(FRONTMATTER_CLOSE, FRONTMATTER_OPEN.length)
      if (end < 0) return null
      val header = text.substring(FRONTMATTER_OPEN.length, end)
      if (header.lineSequence().none { it == "name: $workerName" } ||
        header.lineSequence().none { it == "mode: primary" }
      ) {
        return null
      }
      val body = text.substring(end + FRONTMATTER_CLOSE.length)
      val lines = header.lines()
      val retained = mutableListOf<String>()
      var skipPermission = false
      for (line in lines) {
        if (line.startsWith("permission:")) {
          skipPermission = true
        } else if (skipPermission && (line.startsWith(" ") || line.isBlank())) {
          continue
        } else {
          skipPermission = false
          retained += line
        }
      }
      val server = GovernedReviewEvidenceContracts.SERVER_NAME
      val policy =
        listOf(
          "${OpenCodeReviewConfigKeys.PERMISSION}:",
          "  \"${OpenCodeReviewConfigKeys.DENY_ALL_TOOL_KEY}\": ${OpenCodeReviewConfigKeys.DENY}",
        ) +
          GovernedReviewEvidenceContracts.OPERATIONS.map { operation ->
            "  \"${qualifiedToolName(server, operation)}\": ${OpenCodeReviewConfigKeys.ALLOW}"
          }
      val updated = (listOf("---") + retained + policy + listOf("---", "", body.trimStart())).joinToString("\n")
      return updated.toByteArray(Charsets.UTF_8)
    }
  }
}

private class OpenCodeReviewWorker(
  val name: String,
  val provider: String,
  val auth: Map<String, String>,
  val content: ByteArray,
)

internal sealed interface OpenCodeReviewPreparation {
  data class Ready(val scope: OpenCodeReviewLaunchScope) : OpenCodeReviewPreparation

  data class Refused(val reason: String) : OpenCodeReviewPreparation
}

internal object OpenCodeReviewConfigKeys {
  const val ALLOW = "allow"
  const val ARGS = "args"
  const val AUTH_KEY = "key"
  const val COMMAND = "command"
  const val DENY = "deny"
  const val DENY_ALL_TOOL_KEY = "*"
  const val ENVIRONMENT = "environment"
  const val INPUT_ENVIRONMENT = "env"
  const val MCP = "mcp"
  const val MCP_SERVERS = "mcpServers"
  const val MODEL = "model"
  const val PERMISSION = "permission"
  const val TYPE = "type"
}

internal object OpenCodeReviewEnvironmentKeys {
  const val DISABLE_CLAUDE_CODE = "OPENCODE_DISABLE_CLAUDE_CODE"
  const val DISABLE_DEFAULT_PLUGINS = "OPENCODE_DISABLE_DEFAULT_PLUGINS"
  const val DISABLE_EXTERNAL_SKILLS = "OPENCODE_DISABLE_EXTERNAL_SKILLS"
  const val PROGRAM_DATA = "ProgramData"
  const val PROGRAM_DATA_UPPER = "PROGRAMDATA"
  const val PURE_MODE = "OPENCODE_PURE"
  const val TMPDIR = "TMPDIR"
  const val USER = "USER"
  val STARTUP_KEYS = listOf("PATH", USER, "LOGNAME", "SHELL", "LANG", "LC_ALL", "JAVA_HOME")
}

private const val FRONTMATTER_OPEN = "---\n"
private const val FRONTMATTER_CLOSE = "\n---\n"
