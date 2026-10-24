package skillbill.infrastructure.launcher.work

import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DetachedWorkRunLauncherTest {
  @Test
  fun `a missing harness refuses launch without falling back to runtime cli`() {
    val home = Files.createTempDirectory("missing-harness")
    val request = harnessRequest(home, "Resume")
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("claude harness"))
  }

  @Test
  fun `a missing runtime refuses a runtime command without falling back to the agent harness`() {
    val home = Files.createTempDirectory("missing-runtime")
    executable(home, "#!/bin/sh\ntouch harness-was-started\n")
    val request =
      WorkRunLaunchRequest(
        home,
        home.resolve("metrics.db"),
        "work",
        WorkRunLaunchKind.RuntimeCommand(listOf("goal", "SKILL-9", "--agent", "claude")),
      )
    val refused = assertIs<WorkRunLaunchResult.Refused>(launcher(home).start(request))
    assertTrue(refused.reason.contains("skill-bill runtime is not installed"), refused.reason)
    assertFalse(Files.exists(home.resolve("harness-was-started")))
  }

  private fun executable(
    home: Path,
    script: String,
  ): Path {
    val executable = home.resolve(".local/bin/claude")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, script + "\n")
    assertTrue(executable.toFile().setExecutable(true))
    return executable
  }

  private fun harnessRequest(
    home: Path,
    prompt: String,
  ) = WorkRunLaunchRequest(home, home.resolve("metrics.db"), "work", WorkRunLaunchKind.AgentHarness("claude", prompt))

  private fun launcher(home: Path) =
    DetachedWorkRunLauncher(
      EnvironmentContext(environment = TRAY_ENVIRONMENT, userHome = home),
      NoopRuntimeDiagnostics,
      FixedProjectStorePort(home.resolve("project-store")),
    )

  private companion object {
    val TRAY_ENVIRONMENT = mapOf("PATH" to "/usr/bin:/bin")
  }
}
