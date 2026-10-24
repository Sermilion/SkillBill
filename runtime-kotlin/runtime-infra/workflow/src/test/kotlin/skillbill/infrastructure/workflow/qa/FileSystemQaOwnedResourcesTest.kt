package skillbill.infrastructure.workflow.qa

import skillbill.infrastructure.host.process.BoundedExternalProcessResult
import skillbill.infrastructure.workflow.monitor.FakeMonitorProcessInspector
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import skillbill.ports.qa.model.qaEnvironmentName
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemQaOwnedResourcesTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-qa-resources").also(::gitInit)
  private val stateDir: Path = Files.createTempDirectory("skillbill-qa-resources-state")
  private val environment =
    EnvironmentContext(environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()), userHome = stateDir)
  private val inspector = FakeMonitorProcessInspector(FeatureTaskRuntimeProcessInspection.ExactLive)
  private val commands = mutableListOf<List<String>>()
  private var nextResult = BoundedExternalProcessResult(exitCode = 0)
  private var interruptOnRun = false
  private val resources =
    FileSystemQaOwnedResources(
      inspector,
      Clock.systemUTC(),
      NoopRuntimeDiagnostics,
      environment,
      QaOwnedResourceTeardown(
        QaResourceCommandRunner { argv, _, _ ->
          if (interruptOnRun) throw InterruptedException("cancelled")
          commands += argv
          nextResult
        },
      ),
    )
  private val qaRoot: Path

  init {
    val workspaces = FileSystemQaWorkspace(inspector, Clock.systemUTC(), environment)
    workspaces.open(repoRoot, FIRST)
    workspaces.open(repoRoot, SECOND)
    qaRoot = QaPaths.qaRoot(environment, repoRoot)
  }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    stateDir.toFile().deleteRecursively()
  }

  @Test
  fun `another invocation cannot take a recorded resource whether its owner is live or dead`() {
    val volume = QaOwnedResource(QaOwnedResourceKind.VOLUME, "${qaEnvironmentOf(FIRST)}-db")
    assertEquals(QaResourceRegistration.Registered, resources.register(repoRoot, FIRST, volume))
    assertEquals(QaResourceRegistration.Registered, resources.register(repoRoot, FIRST, volume))
    val file = QaPaths.resourceFile(qaRoot, volume)
    val before = Files.readAllBytes(file)

    assertEquals(QaResourceRegistration.Conflict(FIRST), resources.register(repoRoot, SECOND, volume))
    inspector.inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    assertEquals(QaResourceRegistration.Conflict(FIRST), resources.register(repoRoot, SECOND, volume))

    assertContentEquals(before, Files.readAllBytes(file))
  }

  @Test
  fun `a docker failure that does not name the resource keeps the record while a named not-found releases it`() {
    val compose = QaOwnedResource(QaOwnedResourceKind.COMPOSE_PROJECT, qaEnvironmentOf(FIRST))
    val network = QaOwnedResource(QaOwnedResourceKind.NETWORK, "${qaEnvironmentOf(FIRST)}-net")
    resources.register(repoRoot, FIRST, compose)
    resources.register(repoRoot, FIRST, network)

    nextResult = BoundedExternalProcessResult(exitCode = 1, output = "no configuration file provided: not found")
    assertIs<QaResourceReleaseOutcome.Failed>(resources.release(repoRoot, FIRST, compose))
    assertTrue(Files.exists(QaPaths.resourceFile(qaRoot, compose)))

    nextResult = BoundedExternalProcessResult(exitCode = 1, output = "network ${network.identity} not found")
    assertIs<QaResourceReleaseOutcome.Released>(resources.release(repoRoot, FIRST, network))
    assertFalse(Files.exists(QaPaths.resourceFile(qaRoot, network)))
  }

  @Test
  fun `a directory registration naming an existing file of the invocation is rejected`() {
    val invocationDirectory = QaPaths.invocationDirectory(qaRoot, FIRST).toAbsolutePath().normalize()
    val evidence = invocationDirectory.resolve("report.md").also { Files.writeString(it, "evidence") }

    val file = QaOwnedResource(QaOwnedResourceKind.DIRECTORY, evidence.toString())

    assertIs<QaResourceRegistration.Rejected>(resources.register(repoRoot, FIRST, file))
    assertTrue(Files.exists(evidence))
  }

  @Test
  fun `a docker identity outside the invocation environment name is rejected without echoing it`() {
    val foreign = QaOwnedResource(QaOwnedResourceKind.VOLUME, "operator-dev-database")

    val rejected = assertIs<QaResourceRegistration.Rejected>(resources.register(repoRoot, FIRST, foreign))

    assertFalse(rejected.reason.contains("operator-dev-database"))
    assertFalse(Files.exists(QaPaths.resourceFile(qaRoot, foreign)))
  }

  @Test
  fun `releaseAllFor tears down only its own records in kind order and never prunes`() {
    val first = qaEnvironmentOf(FIRST)
    val owned =
      listOf(
        QaOwnedResource(QaOwnedResourceKind.VOLUME, "$first-data"),
        QaOwnedResource(QaOwnedResourceKind.CONTAINER, "$first-api"),
        QaOwnedResource(QaOwnedResourceKind.COMPOSE_PROJECT, first),
        QaOwnedResource(QaOwnedResourceKind.NETWORK, "$first-net"),
      )
    owned.forEach { resources.register(repoRoot, FIRST, it) }
    val other = QaOwnedResource(QaOwnedResourceKind.CONTAINER, "${qaEnvironmentOf(SECOND)}-api")
    resources.register(repoRoot, SECOND, other)

    val outcomes = resources.releaseAllFor(repoRoot, FIRST)

    assertTrue(outcomes.all { it is QaResourceReleaseOutcome.Released })
    assertEquals(
      listOf(
        listOf("docker", "compose", "-p", first, "down", "--volumes", "--remove-orphans", "--timeout", "10"),
        listOf("docker", "rm", "--force", "--volumes", "$first-api"),
        listOf("docker", "network", "rm", "$first-net"),
        listOf("docker", "volume", "rm", "--force", "$first-data"),
      ),
      commands,
    )
    assertTrue(commands.none { "prune" in it })
    assertTrue(Files.exists(QaPaths.resourceFile(qaRoot, other)), "the other invocation's record must stay")
    assertTrue(owned.none { Files.exists(QaPaths.resourceFile(qaRoot, it)) })
  }

  @Test
  fun `a failing teardown reports failed and keeps the record`() {
    val container = QaOwnedResource(QaOwnedResourceKind.CONTAINER, "${qaEnvironmentOf(FIRST)}-api")
    resources.register(repoRoot, FIRST, container)
    nextResult = BoundedExternalProcessResult(exitCode = 1, output = "daemon unreachable for ${container.identity}")

    val failed = assertIs<QaResourceReleaseOutcome.Failed>(resources.releaseAllFor(repoRoot, FIRST).single())

    assertEquals(container, failed.resource)
    assertFalse(failed.detail.contains(container.identity), "the detail must not echo the identity")
    assertTrue(Files.exists(QaPaths.resourceFile(qaRoot, container)))
  }

  @Test
  fun `reclaimAbandoned removes records of a dead owner and leaves a live owner untouched`() {
    val volume = QaOwnedResource(QaOwnedResourceKind.VOLUME, "${qaEnvironmentOf(FIRST)}-data")
    resources.register(repoRoot, FIRST, volume)

    assertTrue(resources.reclaimAbandoned(repoRoot).isEmpty())
    assertTrue(commands.isEmpty(), "a live owner must not trigger any command")

    inspector.inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    val outcomes = resources.reclaimAbandoned(repoRoot)

    assertEquals(listOf<QaResourceReleaseOutcome>(QaResourceReleaseOutcome.Released(volume)), outcomes)
    assertFalse(Files.exists(QaPaths.resourceFile(qaRoot, volume)))
  }

  @Test
  fun `an interrupt during teardown propagates and every record stays`() {
    val first = qaEnvironmentOf(FIRST)
    val owned =
      listOf(
        QaOwnedResource(QaOwnedResourceKind.CONTAINER, "$first-api"),
        QaOwnedResource(QaOwnedResourceKind.VOLUME, "$first-data"),
      )
    owned.forEach { resources.register(repoRoot, FIRST, it) }
    interruptOnRun = true

    assertFailsWith<InterruptedException> { resources.releaseAllFor(repoRoot, FIRST) }

    assertTrue(owned.all { Files.exists(QaPaths.resourceFile(qaRoot, it)) })
  }

  private fun qaEnvironmentOf(invocationId: String): String = qaEnvironmentName(invocationId)

  private companion object {
    const val FIRST = "phr-first"
    const val SECOND = "phr-second"

    fun gitInit(directory: Path) {
      val process = ProcessBuilder("git", "init", "-q", directory.toString()).redirectErrorStream(true).start()
      check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git init failed in $directory" }
    }
  }
}
