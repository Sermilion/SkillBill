package skillbill.cli.scaffold.commands

import kotlinx.serialization.json.JsonObject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import java.time.Clock

internal data class NativeScaffoldRunArgs(
  val dryRun: Boolean,
  val format: CliFormat,
  val state: CliRunState,
  val inputs: CliRunInputs,
  val clock: Clock,
  val scaffoldGateway: ScaffoldGateway,
  val externalAddonOverlayService: ExternalAddonOverlayService? = null,
)

internal data class AssistedScaffoldWizardArgs(
  val run: NativeScaffoldRunArgs,
  val scaffoldCatalogGateway: ScaffoldCatalogGateway,
  val installAgentService: InstallAgentService,
)

internal data class ScaffoldWizardArgs(
  val run: NativeScaffoldRunArgs,
  val scaffoldCatalogGateway: ScaffoldCatalogGateway,
)

internal data class NativeScaffoldPayloadPathArgs(
  val payloadPath: String?,
  val run: NativeScaffoldRunArgs,
  val transform: (JsonObject) -> JsonObject = { it },
)

internal data class CreateAndFillContentArgs(
  val payload: String?,
  val interactive: Boolean,
  val body: String?,
  val bodyFile: String?,
  val editor: Boolean,
)

internal data class CreateAndFillArgs(
  val content: CreateAndFillContentArgs,
  val dryRun: Boolean,
  val format: CliFormat,
  val state: CliRunState,
  val inputs: CliRunInputs,
  val clock: Clock,
  val scaffoldGateway: ScaffoldGateway,
)

internal data class NewAddonPayloadArgs(
  val platform: String?,
  val name: String?,
  val body: String?,
  val bodyFile: String?,
  val addonLocationPath: String?,
  val consumerSkillDirs: List<String>,
  val state: CliRunState,
)
