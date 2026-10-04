package skillbill.application.uninstall

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.install.model.McpProfileOutcome
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
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationResult
import skillbill.ports.install.nativeagent.model.InstallNativeAgentUnlinkOperationResult
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.UninstallPathsPort
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillBillUninstallCooperativeCancellationTest {
  @Test
  fun `cancellation at first MCP agent stops later mutations`() {
    val unregisterCalls = AtomicInteger(0)
    val port =
      object : InstallMcpRegistrationPort {
        override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
          throw UnsupportedOperationException()

        override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult {
          unregisterCalls.incrementAndGet()
          if (request.agent == "claude") throw CancellationException("stop")
          return throw UnsupportedOperationException("later agents must not run")
        }
      }
    val service = uninstallService(port)
    val plan = planWithMcpAgents(listOf("claude", "codex"))

    assertFailsWith<CancellationException> { service.apply(plan) }
    assertEquals(1, unregisterCalls.get())
  }

  @Test
  fun `interruption at MCP seam stops later mutations`() {
    val port =
      object : InstallMcpRegistrationPort {
        override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
          throw UnsupportedOperationException()

        override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
          throw InterruptedException("stop")
      }
    val service = uninstallService(port)
    val plan = planWithMcpAgents(listOf("claude", "codex"))

    assertFailsWith<InterruptedException> { service.apply(plan) }
  }

  @Test
  fun `partial Claude MCP failure keeps prior removals and reports degradation`() {
    val removedProfile = HOME.resolve("claude.json")
    val port =
      object : InstallMcpRegistrationPort {
        override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
          throw UnsupportedOperationException()

        override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
          InstallMcpRegistrationResult(
            McpRegistrationOutcome.ProfilesFailed(
              "one profile was malformed",
              listOf(McpProfileOutcome(removedProfile.toFileLocation(), changed = true)),
            ),
          )
      }

    val result = uninstallService(port).apply(planWithMcpAgents(listOf("claude")))

    assertTrue(result.failed)
    assertEquals(1, result.exitCode)
    assertEquals(listOf(removedProfile.toString()), result.removed)
    assertEquals(1, result.warnings.size)
  }

  private fun uninstallService(mcpPort: InstallMcpRegistrationPort): SkillBillUninstallService =
    SkillBillUninstallService(
      installAgentService = InstallAgentService(StubInstallAgentTargetPort),
      installNativeAgentLinkPort = StubInstallNativeAgentLinkPort,
      installMcpRegistrationPort = mcpPort,
      uninstallFileSystem = AbsentUninstallPathsPort,
      hostPlatform = StubHostPlatformPort,
      diagnostics = NoopRuntimeDiagnostics,
    )

  private fun planWithMcpAgents(agents: List<String>): UninstallPlan =
    UninstallPlan(
      home = HOME,
      stateRoot = STATE_ROOT,
      skillNames = emptyList(),
      legacyNames = emptyList(),
      agentTargets = emptyList(),
      nativeSourceRoots = emptyList(),
      mcpAgents = agents,
      launchers = emptyList(),
      desktop = DesktopRemoval(launcher = null, files = emptyList(), directories = emptyList()),
    )

  private companion object {
    val HOME: Path = Path.of("/tmp/skillbill-uninstall-cancel")
    val STATE_ROOT: Path = HOME.resolve(".skill-bill")
  }
}

private object NoopRuntimeDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private object StubHostPlatformPort : HostPlatformPort {
  override fun resolveUserHome(): Path = Path.of(System.getProperty("user.home"))

  override fun resolveEnvironment(): Map<String, String> = System.getenv()

  override fun resolveJavaHome(): Path = Path.of(System.getProperty("java.home"))

  override fun resolveWorkingDirectory(): Path = Path.of(System.getProperty("user.dir"))

  override fun resolveTemporaryDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"))

  override val osName: String = "Linux"
  override val jvmClassPath: String = ""
  override val javaCommand: String? = null
  override val pathSeparator: String = ":"
}

private object AbsentUninstallPathsPort : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = false

  override fun isSymbolicLink(path: Path): Boolean = false

  override fun readSymbolicLink(path: Path): Path = path

  override fun deleteIfExists(path: Path): Boolean = false

  override fun removeTree(path: Path): List<Path> = emptyList()
}

private object StubInstallAgentTargetPort : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest) =
    InstallAgentPathResult(Path.of("/tmp/skillbill-uninstall-cancel"))

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest) =
    DetectInstallAgentTargetsResult(emptyList())

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest) = ClaudeConfigRootsResult(emptyList())

  override fun codexConfigRoots(request: CodexConfigRootsRequest) = CodexConfigRootsResult(emptyList())

  override fun agentDirectory(request: InstallAgentDirectoryRequest) =
    InstallAgentDirectoryResult(Path.of("/tmp/skillbill-uninstall-cancel"))

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest) =
    InstallAgentTargetCleanupResult(
      InstallCleanupResult(emptyList(), emptyList()),
    )
}

private object StubInstallNativeAgentLinkPort : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult = throw UnsupportedOperationException()

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult = InstallNativeAgentUnlinkOperationResult(emptyList())
}
