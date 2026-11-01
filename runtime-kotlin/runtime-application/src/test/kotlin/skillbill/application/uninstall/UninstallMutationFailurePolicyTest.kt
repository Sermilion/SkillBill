package skillbill.application.uninstall

import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.LauncherRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.system.UninstallPathsPort
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UninstallMutationFailurePolicyTest {
  @Test
  fun `failed launcher removal records a degradation`() {
    val diagnostics = RecordingDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    removeLauncher(
      fileSystem = ThrowingUninstallPathsPort("deleteIfExists"),
      launcher = LauncherRemoval(LAUNCHER_PATH, RUNTIME_BIN_PATH),
      removed = mutableListOf(),
      skipped = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(1, recorder.failureMessages().size)
    assertEquals(1, diagnostics.errors.size)
    assertTrue(diagnostics.errors.single().contains(LAUNCHER_PATH.toString()))
  }

  @Test
  fun `failed state tree removal records a degradation`() {
    val diagnostics = RecordingDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    removeRecursively(
      fileSystem = ThrowingUninstallPathsPort("removeTree"),
      path = STATE_ROOT,
      removed = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(1, diagnostics.errors.size)
    assertTrue(diagnostics.errors.single().contains(STATE_ROOT.toString()))
  }

  @Test
  fun `failed MCP unregistration records a degradation per agent`() {
    val diagnostics = RecordingDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    cleanupMcpRegistrations(
      plan = uninstallPlan(),
      installMcpRegistrationPort = ThrowingMcpRegistrationPort,
      removed = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(2, recorder.failureMessages().size)
    assertTrue(diagnostics.errors.any { it.contains("claude") })
    assertTrue(diagnostics.errors.any { it.contains("codex") })
  }

  private fun uninstallPlan(): UninstallPlan =
    UninstallPlan(
      home = HOME,
      stateRoot = STATE_ROOT,
      skillNames = emptyList(),
      legacyNames = emptyList(),
      agentTargets = emptyList(),
      nativeSourceRoots = emptyList(),
      mcpAgents = listOf("claude", "codex"),
      launchers = emptyList(),
      desktop = DesktopRemoval(launcher = null, files = emptyList(), directories = emptyList()),
    )

  private companion object {
    val HOME: Path = Path.of("/tmp/skillbill-uninstall-policy")
    val STATE_ROOT: Path = HOME.resolve(".skill-bill")
    val LAUNCHER_PATH: Path = HOME.resolve(".local/bin/skill-bill")
    val RUNTIME_BIN_PATH: Path = STATE_ROOT.resolve("runtime/runtime-cli/bin/runtime-cli")
  }
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  val errors = mutableListOf<String>()

  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    errors += message
  }
}

private class ThrowingUninstallPathsPort(
  private val failingOperation: String,
) : UninstallPathsPort {
  override fun <T> withConfigTransaction(
    userHome: Path,
    environment: Map<String, String>,
    action: () -> T,
  ): T = action()

  override fun configLockFilePaths(
    userHome: Path,
    environment: Map<String, String>,
  ): List<Path> = emptyList()

  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = true

  override fun isSymbolicLink(path: Path): Boolean = true

  override fun readSymbolicLink(path: Path): Path = RUNTIME_BIN_TARGET

  override fun deleteIfExists(path: Path): Boolean =
    if (failingOperation == "deleteIfExists") throw IOException("permission denied") else true

  override fun removeTree(path: Path): List<Path> =
    if (failingOperation == "removeTree") throw IOException("directory not empty") else emptyList()

  override fun removeTreeExcept(
    path: Path,
    retainedPaths: Collection<Path>,
  ): List<Path> = removeTree(path)
}

private object ThrowingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")
}

private val RUNTIME_BIN_TARGET: Path =
  Path.of("/tmp/skillbill-uninstall-policy/.skill-bill/runtime/runtime-cli/bin/runtime-cli")
