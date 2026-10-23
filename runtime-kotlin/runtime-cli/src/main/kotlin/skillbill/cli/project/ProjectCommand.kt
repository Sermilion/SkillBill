package skillbill.cli.project

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
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
import skillbill.ports.projectstore.ProjectStorePort

object ProjectCommandPayloadKeys {
  const val PROJECT_ID: String = "project_id"
  const val STORE_ROOT: String = "store_root"
  const val ERROR: String = "error"
}

@Inject
class ProjectCommand(
  linkCommand: ProjectLinkCommand,
) : DocumentedNoOpCliCommand(
    "project",
    "Manage the project store that holds Skill Bill state for the current checkout.",
  ) {
  init {
    subcommands(linkCommand)
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
