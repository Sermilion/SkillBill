
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.scaffold.payload.detectKind
import skillbill.infrastructure.skills.scaffold.payload.validatePayloadVersion
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.ports.install.platformpack.ExternalPlatformPackSourceConfigPort
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ScaffoldResult
import java.io.Closeable
import java.nio.file.Path

internal data class ManifestSnapshot(
  val manifestPath: Path,
  val originalBytes: ByteArray,
)

internal data class ScaffoldTransaction(
  val createdPaths: MutableList<Path> = mutableListOf(),
  val createdDirs: MutableList<Path> = mutableListOf(),
  val createdSymlinks: MutableList<Path> = mutableListOf(),
  val manifestSnapshots: MutableList<ManifestSnapshot> = mutableListOf(),
  val installTargets: MutableList<Path> = mutableListOf(),
  var registeredExternalPackRoot: Path? = null,
  var externalPackConfigHome: Path? = null,
  var externalPackConfigEnvironment: Map<String, String> = emptyMap(),
  var packSourceConfig: ExternalPlatformPackSourceConfigPort? = null,
)

internal data class ScaffoldPlan(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val skillFile: Path,
  val contentFile: Path?,
  val family: String,
  val platform: String,
  val area: String,
  val isShelled: Boolean,
  val notes: List<String>,
  val displayName: String = "",
  val description: String = "",
  val manifestPath: Path? = null,
  val routingSignals: List<String> = emptyList(),
  val tieBreakers: List<String> = emptyList(),
  val specialistAreas: List<String> = emptyList(),
  val specialistAreaMetadata: Map<String, String> = emptyMap(),
  val specialistSkillNames: Map<String, String> = emptyMap(),
  val specialistSkillPaths: Map<String, Path> = emptyMap(),
  val baselineSkillName: String = "",
  val baselineSkillPath: Path? = null,
  val installPaths: List<Path> = emptyList(),
  val createdFiles: List<Path> = emptyList(),
  val contentBody: String? = null,
  val addonBody: String? = null,
  val addonConsumerSkillDirs: List<String> = emptyList(),
  val agentIds: List<String> = emptyList(),
  val agentAddonConsumers: List<String> = emptyList(),
  val externalAddonLocationPath: Path? = null,
  val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  val subagentSpecialists: List<String> = emptyList(),
  val subagentDescriptions: Map<String, String> = emptyMap(),
  val bodyBasedSubagents: Set<String> = emptySet(),
  val subagentsSuppressed: Boolean = false,
  val externalPackRoot: Path? = null,
  val externalPackRegistrationMode: String? = null,
)

internal data class ScaffoldExecutionResult(
  val createdFiles: List<Path>,
  val manifestEdits: List<Path>,
  val symlinks: List<Path>,
  val installTargets: List<Path>,
  val notes: List<String>,
)

internal data class ScaffoldRuntimeContext(
  val userHome: Path,
  val environment: Map<String, String> = emptyMap(),
  val catalogLoader: PlatformPackCatalogLoader? = null,
  val packSourceConfig: ExternalPlatformPackSourceConfigPort? = null,
)

internal fun scaffoldWithAdapters(
  payload: Map<String, Any?>,
  dryRun: Boolean,
  adapters: ScaffoldAdapterSeams,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  runtime: ScaffoldRuntimeContext = ScaffoldRuntimeContext(resolveUserHome(null, hostPlatform)),
): ScaffoldResult {
  require(payload.isNotEmpty()) {
    "Scaffold payload must be a JSON object mapping string keys to values."
  }

  validatePayloadVersion(payload)
  val kind = detectKind(payload)
  val repoRoot = resolveRepoRoot(payload, hostPlatform)
  val plan = planScaffold(payload, repoRoot, kind, adapters, runtime.userHome)
  return if (dryRun) {
    renderDryRunResult(plan, repoRoot)
  } else {
    runScaffold(plan, repoRoot, adapters, runtime)
  }
}

internal data class ScaffoldAdapterSeams(
  val validateScaffold: (ScaffoldPlan, Path) -> Unit,
  val optionalBaselineLayers: (Map<String, Any?>, Path, String) -> List<CodeReviewBaselineLayer>,
  val resolveAddonConsumerSkillDirs: (
    Map<String, Any?>,
    Path,
    PlatformManifest,
  ) -> List<String>,
  val performInstall: (ScaffoldTransaction, ScaffoldPlan, Path) -> Pair<List<Path>, List<String>>,
  val rollbackInstallTargets: (ScaffoldTransaction, MutableList<String>) -> Unit,
)

internal fun renderDryRunResult(
  plan: ScaffoldPlan,
  repoRoot: Path,
): ScaffoldResult =
  ScaffoldResult(
    kind = plan.kind,
    skillName = plan.skillName,
    skillPath = plan.skillPath.toFileLocation(),
    createdFiles = previewCreatedFiles(plan).map { entry -> entry.toFileLocation() },
    manifestEdits = previewManifestEdits(plan, repoRoot).map { entry -> entry.toFileLocation() },
    manifestPreviews = previewManifestPreviews(plan, repoRoot).mapKeys { (path, _) -> path.toFileLocation() },
    symlinks = emptyList(),
    installTargets = emptyList(),
    notes = plan.notes + listOf("Dry run - no filesystem changes applied."),
  )

internal fun runScaffold(
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
  runtime: ScaffoldRuntimeContext = ScaffoldRuntimeContext(resolveUserHome(null)),
): ScaffoldResult {
  val txn = ScaffoldTransaction()
  var committed = false
  return Closeable {
    if (!committed) {
      rollback(txn, adapters)
    }
  }.use {
    val execution =
      executeScaffold(
        txn,
        plan,
        repoRoot,
        adapters,
        runtime,
      )
    committed = true
    ScaffoldResult(
      kind = plan.kind,
      skillName = plan.skillName,
      skillPath = plan.skillPath.toFileLocation(),
      createdFiles = execution.createdFiles.map { entry -> entry.toFileLocation() },
      manifestEdits = execution.manifestEdits.map { entry -> entry.toFileLocation() },
      symlinks = execution.symlinks.map { entry -> entry.toFileLocation() },
      installTargets = execution.installTargets.map { entry -> entry.toFileLocation() },
      notes = plan.notes + execution.notes,
    )
  }
}
