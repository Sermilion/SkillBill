package skillbill.cli

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.cli.scaffold.payload.NativeScaffoldPayloadRun
import skillbill.cli.scaffold.payload.NativeScaffoldRunOptions
import skillbill.model.FileLocation
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliScaffoldPartialOutcomeTest {
  @Test
  fun `registration failure preserves written scaffold and returns nonzero`() {
    val repositoryRoot = Files.createTempDirectory("skillbill-cli-scaffold-partial")
    val gateway = RecordingScaffoldGateway(repositoryRoot)
    val inputs =
      CliRunInputs(
        databasePath = null,
        environment = emptyMap(),
        userHome = repositoryRoot,
        repositoryRoot = repositoryRoot,
        liveStdout = {},
        liveStderr = {},
      )
    val state = CliRunState(null)
    NativeScaffoldPayloadRun(
      state = state,
      inputs = inputs,
      clock = Clock.systemUTC(),
      scaffoldGateway = gateway,
      externalAddonOverlayService = ExternalAddonOverlayService(FailingSourceConfigPort(), NoopOverlayPort()),
    ).runPayload(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "add-on",
        "name" to "partial-addon",
        "platform" to "kotlin",
        "addon_location_path" to repositoryRoot.resolve("external-addons").toString(),
      ),
      NativeScaffoldRunOptions(dryRun = false, format = CliFormat.JSON, withExternalAddonOverlay = true),
    )
    val result = assertNotNull(state.result)

    val payload = assertNotNull(result.payload)
    assertEquals(1, result.exitCode)
    assertEquals("partial", payload["status"])
    assertEquals(repositoryRoot.resolve("skills/partial-addon").toString(), payload["skill_path"])
    assertTrue(payload["registration_error"].toString().contains("registration failed"))
  }
}

private class RecordingScaffoldGateway(
  private val repositoryRoot: Path,
) : ScaffoldGateway {
  override fun list(
    repoRoot: Path,
    skillNames: List<String>,
  ) = error("unused")

  override fun show(
    repoRoot: Path,
    skillName: String,
    contentMode: String,
  ) = error("unused")

  override fun explain(
    repoRoot: Path,
    skillName: String?,
  ) = error("unused")

  override fun validate(
    repoRoot: Path,
    skillNames: List<String>,
  ) = error("unused")

  override fun upgrade(
    repoRoot: Path,
    skillNames: List<String>,
    validate: Boolean,
  ) = error("unused")

  override fun fill(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ) = error("unused")

  override fun saveExactContent(
    repoRoot: Path,
    skillName: String,
    content: String,
  ) = error("unused")

  override fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ) = error("unused")

  override fun scaffold(
    request: ScaffoldCommandRequest,
    dryRun: Boolean,
  ): ScaffoldResult =
    ScaffoldResult(
      kind = "add-on",
      skillName = "partial-addon",
      skillPath = FileLocation(repositoryRoot.resolve("skills/partial-addon").toString()),
      createdFiles = listOf(FileLocation(repositoryRoot.resolve("skills/partial-addon/content.md").toString())),
    )

  override fun render(
    repoRoot: Path,
    skillName: String,
  ): ScaffoldRenderResult = error("unused")
}

private class FailingSourceConfigPort : ExternalAddonSourceConfigPort {
  override fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult =
    ExternalAddonSourceConfigResult()

  override fun registerExternalAddonSource(
    request: ExternalAddonSourceRegistrationRequest,
  ): ExternalAddonSourceConfigResult = error("registration failed")
}

private class NoopOverlayPort : ExternalAddonOverlayPort {
  override fun applyOverlay(request: ExternalAddonOverlayRequest): ExternalAddonOverlayResult =
    ExternalAddonOverlayResult()
}
