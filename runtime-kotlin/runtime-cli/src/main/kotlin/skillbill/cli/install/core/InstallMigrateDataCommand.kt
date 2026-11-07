package skillbill.cli.install.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallDatabasePreparation
import skillbill.cli.install.apply.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs

@Inject
class InstallMigrateDataCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val databasePreparation: InstallDatabasePreparation,
) : DocumentedCliCommand(
    "migrate-data",
    "Migrate persisted runtime data before activating an installation, preserving existing runs and history.",
  ) {
  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "migrate-data")) return
    val issue = databasePreparation.prepareUpgrade()
    state.completeText(
      issue?.let { "${it.message}\n${it.guidance.orEmpty()}\n" } ?: "Persisted runtime data is ready.\n",
      emptyMap(),
      exitCode = if (issue == null) 0 else 1,
    )
  }
}
