package skillbill.engine.operation.updatecheck

import skillbill.application.system.SystemService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.updatecheck.model.RECOMMENDED_INSTALL_COMMAND
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationConfirmationGate
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationStepRunner
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.model.RuntimeVersion
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.operation.UnavailableOperationProposalRepository
import skillbill.ports.process.ReleaseCatalogPort
import skillbill.ports.process.model.ReleaseCatalogEntry
import skillbill.ports.process.model.ReleaseCatalogResult
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckOperationTest {
  private val home: Path = Files.createTempDirectory("update-check-operation")

  @AfterTest
  fun cleanUp() {
    home.toFile().deleteRecursively()
  }

  @Test
  fun `every update-check status renders the update-check report with no agent step`() {
    assertEquals(
      "status: up_to_date\ninstalled_version: 1.2.3\nlatest_version: v1.2.3\n",
      report(releases("v1.2.3")),
    )
    assertEquals(
      "status: update_available\ninstalled_version: 1.2.3\nlatest_version: v1.3.0\n" +
        "release_url: ${releaseUrl("v1.3.0")}\nrecommended_install_command: $RECOMMENDED_INSTALL_COMMAND\n",
      report(releases("v1.3.0")),
    )
    assertEquals(
      "status: ahead_of_release\ninstalled_version: 1.2.3\nlatest_version: v1.2.2\n" +
        "release_url: ${releaseUrl("v1.2.2")}\n",
      report(releases("v1.2.2")),
    )
    assertEquals(
      "status: unknown\nreason: GitHub rate limit\n",
      report(ReleaseCatalogResult.Failure("GitHub rate limit")),
    )
  }

  @Test
  fun `a newer prerelease is ignored, as skill-bill update-check ignores it by default`() {
    assertEquals(
      "status: up_to_date\ninstalled_version: 1.2.3\nlatest_version: v1.2.3\n",
      report(releases("v1.2.3", "v1.3.0-rc.1")),
    )
  }

  private fun report(catalog: ReleaseCatalogResult): String {
    val service =
      UpdateCheckService(
        SystemService(
          sqliteSessionFactoryForTests(userHome = home, environment = emptyMap()),
          UnusedTelemetrySettings,
          NoopRuntimeDiagnostics,
          RuntimeVersion("1.2.3"),
        ),
        object : ReleaseCatalogPort {
          override fun listReleases(): ReleaseCatalogResult = catalog
        },
      )
    val executor =
      OperationExecutor(
        OperationRegistry(listOf(UpdateCheckOperation(service))),
        OperationConfirmationGate(
          UnavailableOperationProposalRepository,
          NoopWorkflowGitOperations,
          Clock.systemUTC(),
        ),
        OperationStepRunner(UnlaunchablePhaseRunner),
      )
    val result =
      executor.execute(
        OperationRequest(
          operationId = "update-check",
          repoRoot = home,
          invokedAgentId = null,
          arguments = OperationArguments(),
          instructions = null,
        ),
      )
    return assertIs<OperationOutcome.Completed>(result.outcome).text
  }

  private fun releases(vararg tags: String): ReleaseCatalogResult =
    ReleaseCatalogResult.Releases(
      tags.map { tag ->
        ReleaseCatalogEntry.Release(
          tagName = tag,
          url = releaseUrl(tag),
          notes = null,
          prerelease = tag.contains("-"),
          draft = false,
        )
      },
    )

  private fun releaseUrl(tag: String): String = "https://github.com/oila-gmbh/skill-bill/releases/tag/$tag"

  private object UnusedTelemetrySettings : TelemetrySettingsProvider {
    override fun load(materialize: Boolean): TelemetrySettings = error("update-check reads no telemetry settings")
  }

  private object UnlaunchablePhaseRunner : PhaseRunner {
    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput = error("update-check launches no agent step")
  }
}
