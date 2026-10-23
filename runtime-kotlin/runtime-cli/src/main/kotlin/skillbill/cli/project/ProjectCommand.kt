package skillbill.cli.project

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
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
  const val ERROR: String = "error"
}

@Inject
class ProjectCommand(
  linkCommand: ProjectLinkCommand,
  pathCommand: ProjectPathCommand,
) : DocumentedNoOpCliCommand(
    "project",
    "Manage the project store that holds Skill Bill state for the current checkout.",
  ) {
  init {
    subcommands(linkCommand, pathCommand)
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
