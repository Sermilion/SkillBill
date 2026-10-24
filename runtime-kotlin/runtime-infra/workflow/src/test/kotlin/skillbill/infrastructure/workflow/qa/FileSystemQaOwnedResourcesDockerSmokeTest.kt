package skillbill.infrastructure.workflow.qa

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.infrastructure.workflow.monitor.FakeMonitorProcessInspector
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import skillbill.ports.qa.model.qaEnvironmentName
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemQaOwnedResourcesDockerSmokeTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-qa-docker").also(::gitInit)
  private val stateDir: Path = Files.createTempDirectory("skillbill-qa-docker-state")
  private val fixtureDir: Path = Files.createTempDirectory("skillbill-qa-docker-fixture")
  private val environment =
    EnvironmentContext(environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()), userHome = stateDir)
  private val inspector = FakeMonitorProcessInspector(FeatureTaskRuntimeProcessInspection.ExactLive)
  private val resources =
    FileSystemQaOwnedResources(
      inspector,
      Clock.systemUTC(),
      NoopRuntimeDiagnostics,
      environment,
      QaOwnedResourceTeardown(ProcessQaResourceCommandRunner),
    )
  private val suffix = UUID.randomUUID().toString().take(8)
  private val first = "phr-smoke-$suffix-a"
  private val second = "phr-smoke-$suffix-b"
  private val composeFile = fixtureDir.resolve("compose.yaml")

  @AfterTest
  fun cleanUp() {
    listOf(first, second).forEach { docker("compose", "-p", qaEnvironmentName(it), "down", "--volumes") }
    listOf(repoRoot, stateDir, fixtureDir).forEach { it.toFile().deleteRecursively() }
  }

  @Test
  fun `releaseAllFor removes one project container and volume and leaves the other project`() {
    assumeTrue(docker("info").exitCode == 0, "docker daemon is not available; skipping the real-Docker smoke test")
    assumeTrue(docker("pull", IMAGE).exitCode == 0, "docker cannot pull $IMAGE; skipping the real-Docker smoke test")
    Files.writeString(composeFile, COMPOSE)
    val workspaces = FileSystemQaWorkspace(inspector, Clock.systemUTC(), environment)
    listOf(first, second).forEach { invocation ->
      workspaces.open(repoRoot, invocation)
      val project = qaEnvironmentName(invocation)
      assertEquals(0, docker("compose", "-p", project, "-f", composeFile.toString(), "up", "-d").exitCode)
      resources.register(repoRoot, invocation, QaOwnedResource(QaOwnedResourceKind.COMPOSE_PROJECT, project))
    }

    val outcomes = resources.releaseAllFor(repoRoot, first)

    assertTrue(outcomes.single() is QaResourceReleaseOutcome.Released, "teardown outcomes: $outcomes")
    assertFalse(containers(first))
    assertFalse(volumes(first))
    assertTrue(containers(second))
    assertTrue(volumes(second))
  }

  private fun containers(invocation: String): Boolean = listed("ps", "-a", "-q", invocation = invocation)

  private fun volumes(invocation: String): Boolean = listed("volume", "ls", "-q", invocation = invocation)

  private fun listed(
    vararg command: String,
    invocation: String,
  ): Boolean {
    val filter = "label=com.docker.compose.project=${qaEnvironmentName(invocation)}"
    return docker(*command, "--filter", filter).output.isNotBlank()
  }

  private fun docker(vararg arguments: String) =
    BoundedExternalProcessRunner.run(
      BoundedExternalProcessRequest(argv = listOf("docker") + arguments, deadlineSeconds = DEADLINE_SECONDS),
    )

  private companion object {
    const val IMAGE = "alpine"
    const val DEADLINE_SECONDS = 180L
    const val COMPOSE = """
services:
  app:
    image: alpine
    command: ["sleep", "300"]
    volumes:
      - data:/data
volumes:
  data: {}
"""

    fun gitInit(directory: Path) {
      val process = ProcessBuilder("git", "init", "-q", directory.toString()).redirectErrorStream(true).start()
      check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git init failed in $directory" }
    }
  }
}
