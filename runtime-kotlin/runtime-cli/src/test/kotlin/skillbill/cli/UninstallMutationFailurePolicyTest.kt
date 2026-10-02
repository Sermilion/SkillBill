package skillbill.cli

import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.SkillBillUninstallService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRunInputs
import skillbill.cli.system.UninstallCommand
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpRegistrationOutcome
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
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UninstallMutationFailurePolicyTest {
  @Test
  fun `uninstall reports a failed mutation as a non-zero exit with a failed status`() {
    val result = runUninstall(ThrowingMcpRegistrationPort)

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("failed_with_degradations", result.payload?.get("status"), result.stdout)
    assertTrue(result.stdout.startsWith("uninstall_status: failed_with_degradations"), result.stdout)
  }

  @Test
  fun `uninstall without a failed mutation stays a zero exit`() {
    val result = runUninstall(SucceedingMcpRegistrationPort)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("completed", result.payload?.get("status"), result.stdout)
  }

  private fun runUninstall(mcpRegistrationPort: InstallMcpRegistrationPort): CliExecutionResult {
    val state = CliRunState(stdinText = null)
    val diagnostics = RecordingRuntimeDiagnostics()
    val uninstallService =
      SkillBillUninstallService(
        installAgentService = InstallAgentService(StubInstallAgentTargetPort),
        installNativeAgentLinkPort = StubInstallNativeAgentLinkPort,
        installMcpRegistrationPort = mcpRegistrationPort,
        uninstallFileSystem = AbsentUninstallPathsPort,
        hostPlatform = StubUninstallHostPlatformPort,
        diagnostics = diagnostics,
      )
    val command =
      UninstallCommand(
        state = state,
        inputs =
          CliRunInputs(
            databasePath = null,
            environment = emptyMap(),
            userHome = HOME,
            repositoryRoot = HOME,
            liveStdout = {},
            liveStderr = {},
          ),
        uninstallService = uninstallService,
      )
    CommandLineParser.parseAndRun(command, listOf("--yes")) { parsed -> parsed.run() }
    return assertNotNull(state.result)
  }

  private companion object {
    val HOME: Path = Path.of("/tmp/skillbill-uninstall-policy")
    val STATE_ROOT: Path = HOME.resolve(".skill-bill")
  }
}

private object ThrowingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")
}

private val ABSENT_PATH: Path = Path.of("/tmp/skillbill-uninstall-policy/absent")

private object AbsentUninstallPathsPort : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = false

  override fun isSymbolicLink(path: Path): Boolean = false

  override fun readSymbolicLink(path: Path): Path = path

  override fun deleteIfExists(path: Path): Boolean = false

  override fun removeTree(path: Path): List<Path> = emptyList()
}

private object StubInstallAgentTargetPort : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult = InstallAgentPathResult(ABSENT_PATH)

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult =
    DetectInstallAgentTargetsResult(emptyList())

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest): ClaudeConfigRootsResult =
    ClaudeConfigRootsResult(emptyList())

  override fun codexConfigRoots(request: CodexConfigRootsRequest): CodexConfigRootsResult =
    CodexConfigRootsResult(emptyList())

  override fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult =
    InstallAgentDirectoryResult(ABSENT_PATH)

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult =
    InstallAgentTargetCleanupResult(InstallCleanupResult(removed = emptyList(), skipped = emptyList()))
}

private object StubInstallNativeAgentLinkPort : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult = throw UnsupportedOperationException("uninstall never links")

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult = InstallNativeAgentUnlinkOperationResult(emptyList())
}

private object StubUninstallHostPlatformPort : HostPlatformPort {
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

private object SucceedingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw UnsupportedOperationException("uninstall never registers")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      McpRegistrationOutcome.Applied(
        McpMutationResult(agent = request.agent, configPath = ABSENT_PATH.toFileLocation(), changed = false),
      ),
    )
}
