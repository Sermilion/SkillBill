package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.testAgentRunProcessRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentRunLauncherLivenessTest {
  @Test
  fun `install scripts refuse process-level execution during goal continuation`() {
    val repoRoot = repoRoot()
    val fixtureRoot = Files.createTempDirectory("skillbill-goal-continuation-install-guard")

    listOf("install.sh", "uninstall.sh").forEach { scriptName ->
      val script = fixtureRoot.resolve(scriptName)
      Files.copy(repoRoot.resolve(scriptName), script)

      val result =
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
          testAgentRunProcessRequest(
            listOf(bashExecutable().toString(), script.toString()),
            fixtureRoot,
          ) {
            environment =
              mapOf(
                "HOME" to fixtureRoot.resolve("home").toString(),
                "SKILL_BILL_GOAL_CONTINUATION" to "1",
              )
            inheritEnvironment = false
          },
        )

      assertEquals(64, result.exitStatus, "$scriptName stdout=${result.stdout} stderr=${result.stderr}")
      assertContains(result.stdout, "Refusing to run $scriptName during skill-bill goal-continuation.")
      assertFalse(result.stdout.contains("Applying install through the runtime plan/apply path."))
      assertFalse(result.stdout.contains("Uninstall complete"))
    }
  }
}
