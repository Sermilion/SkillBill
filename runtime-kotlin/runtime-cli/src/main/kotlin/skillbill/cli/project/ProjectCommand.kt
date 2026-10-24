package skillbill.cli.project

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.intake.LegacySpecImport
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.projectstore.ProjectStoreFailureCode
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import java.nio.file.Path

object ProjectCommandPayloadKeys {
  const val PROJECT_ID: String = "project_id"
  const val STORE_ROOT: String = "store_root"
  const val BUNDLE_PATH: String = "bundle_path"
  const val SOURCE_ROOT: String = "source_root"
  const val SPECS_ROOT: String = "specs_root"
  const val IMPORTED: String = "imported"
  const val ALREADY_IN_STORE: String = "already_in_store"
  const val IN_FLIGHT: String = "in_flight"
  const val FAILED: String = "failed"
  const val ERROR: String = "error"
}

@Inject
class ProjectCommand(
  linkCommand: ProjectLinkCommand,
  pathCommand: ProjectPathCommand,
  importSpecsCommand: ProjectImportSpecsCommand,
) : DocumentedNoOpCliCommand(
    "project",
    "Manage the project store that holds Skill Bill state for the current checkout.",
  ) {
  init {
    subcommands(linkCommand, pathCommand, importSpecsCommand)
  }
}

@Inject
class ProjectPathCommand(
  private val projectStore: ProjectStorePort,
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "path",
    "Print the project store path, or the spec bundle directory of [ISSUE] when given.",
  ) {
  private val issueKey by argument("issue", help = "Issue key of the spec bundle to locate.").optional()

  override fun run() {
    val repoRoot = resolveCliRepositoryRoot(null, inputs)
    val storeRoot = projectStore.resolve(repoRoot).root.toString()
    val issue = issueKey
    if (issue == null) {
      state.completeText(
        "$storeRoot\n",
        mapOf(SharedPayloadKeys.STATUS to CliPayloadStatus.OK, ProjectCommandPayloadKeys.STORE_ROOT to storeRoot),
      )
      return
    }
    when (val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issue, null, repoRoot))) {
      is FeatureSpecPathResolveResult.Explicit -> completeBundle(storeRoot, resolved.specPath)
      is FeatureSpecPathResolveResult.SingleMatch -> completeBundle(storeRoot, resolved.specPath)
      is FeatureSpecPathResolveResult.NoMatch ->
        fail("No spec bundle for ${resolved.issueKey} under ${resolved.specsRoot}", storeRoot)
      is FeatureSpecPathResolveResult.Ambiguous ->
        fail(
          "More than one spec bundle matches ${resolved.issueKey}:\n" +
            resolved.matches.joinToString("\n") { match -> "  ${match.parent}" },
          storeRoot,
        )
    }
  }

  private fun completeBundle(
    storeRoot: String,
    specPath: String,
  ) {
    val bundlePath = requireNotNull(Path.of(specPath).parent).toString()
    state.completeText(
      "$bundlePath\n",
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        ProjectCommandPayloadKeys.STORE_ROOT to storeRoot,
        ProjectCommandPayloadKeys.BUNDLE_PATH to bundlePath,
      ),
    )
  }

  private fun fail(
    message: String,
    storeRoot: String,
  ) {
    state.completeText(
      "$message\n",
      mapOf(
        SharedPayloadKeys.STATUS to "failed",
        ProjectCommandPayloadKeys.STORE_ROOT to storeRoot,
        ProjectCommandPayloadKeys.ERROR to message,
      ),
      exitCode = 1,
    )
  }
}

@Inject
class ProjectLinkCommand(
  private val projectStore: ProjectStorePort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "link",
    "Attach the current checkout to the existing project store <id>.",
  ) {
  private val projectId by argument("id", help = "Project id of the existing store to attach this checkout to.")

  override fun run() {
    val location =
      try {
        projectStore.link(resolveCliRepositoryRoot(null, inputs), projectId)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == ProjectStoreFailureCode.PROJECT_STORE_TARGET_UNKNOWN)
        state.completeText(
          "${error.message}\n",
          mapOf(SharedPayloadKeys.STATUS to "failed", ProjectCommandPayloadKeys.ERROR to error.message.orEmpty()),
          exitCode = 1,
        )
        return
      }
    state.completeText(
      "Linked this checkout to project ${location.projectId} (${location.root}).\n",
      mapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        ProjectCommandPayloadKeys.PROJECT_ID to location.projectId,
        ProjectCommandPayloadKeys.STORE_ROOT to location.root.toString(),
      ),
    )
  }
}

@Inject
class ProjectImportSpecsCommand(
  private val legacySpecImport: LegacySpecImport,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "import-specs",
    "Copy legacy .feature-specs/ bundles, including done/, into the project store. The source is never changed; " +
      "bundles already in the store and bundles of in-progress goals are skipped.",
  ) {
  private val from by option("--from", help = "Legacy .feature-specs directory to import instead of the checkout's.")

  override fun run() {
    val result = legacySpecImport.import(resolveCliRepositoryRoot(null, inputs), from?.let(Path::of))
    val text =
      buildString {
        append("Imported ${result.imported.size} bundle(s) from ${result.sourceRoot} into ${result.specsRoot}.\n")
        appendBundles("Already in the store", result.alreadyInStore)
        appendBundles("Left in place for in-progress goals", result.inFlight)
        appendBundles("Failed", result.failed)
      }
    state.completeText(
      text,
      mapOf(
        SharedPayloadKeys.STATUS to if (result.failed.isEmpty()) CliPayloadStatus.OK else "failed",
        ProjectCommandPayloadKeys.SOURCE_ROOT to result.sourceRoot.toString(),
        ProjectCommandPayloadKeys.SPECS_ROOT to result.specsRoot.toString(),
        ProjectCommandPayloadKeys.IMPORTED to result.imported,
        ProjectCommandPayloadKeys.ALREADY_IN_STORE to result.alreadyInStore,
        ProjectCommandPayloadKeys.IN_FLIGHT to result.inFlight,
        ProjectCommandPayloadKeys.FAILED to result.failed,
      ),
      exitCode = if (result.failed.isEmpty()) 0 else 1,
    )
  }

  private fun StringBuilder.appendBundles(
    heading: String,
    bundles: List<String>,
  ) {
    if (bundles.isEmpty()) return
    append("$heading:\n")
    bundles.forEach { append("  $it\n") }
  }
}
