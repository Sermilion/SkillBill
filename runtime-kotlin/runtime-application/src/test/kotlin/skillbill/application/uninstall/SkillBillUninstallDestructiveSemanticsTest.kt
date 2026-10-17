package skillbill.application.uninstall

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpRegistrationOutcome
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.ClaudeConfigRootsRequest
import skillbill.ports.install.agent.model.ClaudeConfigRootsResult
import skillbill.ports.install.agent.model.CodexConfigRootsRequest
import skillbill.ports.install.agent.model.CodexConfigRootsResult
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsResult
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryResult
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentPathResult
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupResult
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationResult
import skillbill.ports.install.nativeagent.model.InstallNativeAgentUnlinkOperationResult
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.UninstallPathsPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillBillUninstallDestructiveSemanticsTest {
  @Test
  fun `T3-04 application uninstall removes state root and does not use shell preservation`() {
    val home = Files.createTempDirectory("skillbill-app-uninstall-home")
    val stateRoot = home.resolve(".skill-bill")
    val stateDb = stateRoot.resolve("review-metrics.db")
    Files.createDirectories(stateRoot.resolve("runtime"))
    Files.writeString(stateDb, "durable\n")
    val fileSystem = LocalUninstallPathsPort(home)
    val service =
      SkillBillUninstallService(
        installAgentService = InstallAgentService(DestructiveSemanticsInstallAgentTargetPort),
        installNativeAgentLinkPort = DestructiveSemanticsInstallNativeAgentLinkPort,
        installMcpRegistrationPort = SucceedingMcpRegistrationPort,
        uninstallFileSystem = fileSystem,
        hostPlatform = DestructiveSemanticsHostPlatformPort,
        diagnostics = NoopDestructiveSemanticsDiagnostics,
      )
    val plan =
      UninstallPlan(
        home = home,
        stateRoot = stateRoot,
        skillNames = emptyList(),
        legacyNames = emptyList(),
        agentTargets = emptyList(),
        nativeSourceRoots = emptyList(),
        mcpAgents = emptyList(),
        launchers = emptyList(),
        desktop = DesktopRemoval(launcher = null, files = emptyList(), directories = emptyList()),
      )

    val result = service.apply(plan)

    assertFalse(result.failed)
    assertTrue(fileSystem.removedStateRoot)
    assertFalse(Files.exists(stateRoot))
    assertFalse(Files.exists(stateDb))
  }

  @Test
  fun `T3-04 failed state root removal reports degradation instead of silent success`() {
    val home = Files.createTempDirectory("skillbill-app-uninstall-partial")
    val stateRoot = home.resolve(".skill-bill")
    Files.createDirectories(stateRoot)
    Files.writeString(stateRoot.resolve("review-metrics.db"), "durable\n")
    val fileSystem = FailingStateRootUninstallPathsPort(home)
    val service =
      SkillBillUninstallService(
        installAgentService = InstallAgentService(DestructiveSemanticsInstallAgentTargetPort),
        installNativeAgentLinkPort = DestructiveSemanticsInstallNativeAgentLinkPort,
        installMcpRegistrationPort = SucceedingMcpRegistrationPort,
        uninstallFileSystem = fileSystem,
        hostPlatform = DestructiveSemanticsHostPlatformPort,
        diagnostics = NoopDestructiveSemanticsDiagnostics,
      )
    val plan =
      UninstallPlan(
        home = home,
        stateRoot = stateRoot,
        skillNames = emptyList(),
        legacyNames = emptyList(),
        agentTargets = emptyList(),
        nativeSourceRoots = emptyList(),
        mcpAgents = emptyList(),
        launchers = emptyList(),
        desktop = DesktopRemoval(launcher = null, files = emptyList(), directories = emptyList()),
      )

    val result = service.apply(plan)

    assertTrue(result.failed)
    assertTrue(Files.exists(stateRoot))
    assertTrue(result.warnings.any { it.contains(stateRoot.toString()) })
  }
}

private class LocalUninstallPathsPort(
  private val home: Path,
) : UninstallPathsPort {
  var removedStateRoot = false

  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = Files.exists(path)

  override fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

  override fun readSymbolicLink(path: Path): Path = Files.readSymbolicLink(path)

  override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

  override fun removeTree(path: Path): List<Path> {
    if (path == home.resolve(".skill-bill")) {
      removedStateRoot = true
    }
    if (!Files.exists(path)) {
      return emptyList()
    }
    val removed = mutableListOf<Path>()
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach {
        Files.deleteIfExists(it)
        removed.add(it)
      }
    }
    return removed
  }
}

private class FailingStateRootUninstallPathsPort(
  private val home: Path,
) : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = Files.exists(path)

  override fun isSymbolicLink(path: Path): Boolean = false

  override fun readSymbolicLink(path: Path): Path = path

  override fun deleteIfExists(path: Path): Boolean = true

  override fun removeTree(path: Path): List<Path> {
    if (path == home.resolve(".skill-bill")) {
      throw IOException("state root not removable")
    }
    return emptyList()
  }
}

private object NoopDestructiveSemanticsDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private object SucceedingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw UnsupportedOperationException("uninstall never registers")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      McpRegistrationOutcome.Applied(
        McpMutationResult(
          agent = request.agent,
          configPath = Path.of("/tmp/skillbill-destructive-semantics/mcp.json").toFileLocation(),
          changed = false,
        ),
      ),
    )
}

private object DestructiveSemanticsInstallAgentTargetPort : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult =
    InstallAgentPathResult(Path.of("/tmp/skillbill-destructive-semantics"))

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult =
    DetectInstallAgentTargetsResult(emptyList())

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest): ClaudeConfigRootsResult =
    ClaudeConfigRootsResult(emptyList())

  override fun codexConfigRoots(request: CodexConfigRootsRequest): CodexConfigRootsResult =
    CodexConfigRootsResult(emptyList())

  override fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult =
    InstallAgentDirectoryResult(Path.of("/tmp/skillbill-destructive-semantics"))

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult =
    InstallAgentTargetCleanupResult(InstallCleanupResult(emptyList(), emptyList()))
}

private object DestructiveSemanticsInstallNativeAgentLinkPort : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult =
    InstallNativeAgentLinkOperationResult(NativeAgentLinkOutcome(emptyList(), emptyList()))

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult = InstallNativeAgentUnlinkOperationResult(emptyList())
}

private object DestructiveSemanticsHostPlatformPort : HostPlatformPort {
  override val osName: String = "Linux"
  override val jvmClassPath: String = ""
  override val javaCommand: String? = null
  override val pathSeparator: String = ":"

  override fun resolveUserHome(): Path = Path.of(System.getProperty("user.home"))

  override fun resolveEnvironment(): Map<String, String> = System.getenv()

  override fun resolveJavaHome(): Path = Path.of(System.getProperty("java.home"))

  override fun resolveWorkingDirectory(): Path = Path.of(System.getProperty("user.dir"))

  override fun resolveTemporaryDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"))
}
