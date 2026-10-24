package skillbill.cli.core

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject
import skillbill.cli.agentaddon.AgentAddonCommand
import skillbill.cli.codereview.CodeReviewCommand
import skillbill.cli.config.ConfigCommand
import skillbill.cli.featuretask.FeatureTaskCommands
import skillbill.cli.featuretask.FeatureTaskRuntimeDeprecatedCommand
import skillbill.cli.goal.core.GoalRunCommand
import skillbill.cli.install.core.InstallTopLevelCommands
import skillbill.cli.learning.LearningsCommand
import skillbill.cli.operation.OperationCommand
import skillbill.cli.phase.PhaseCommand
import skillbill.cli.project.ProjectCommand
import skillbill.cli.qa.QaCommand
import skillbill.cli.repovalidation.RepoValidationCliCommands
import skillbill.cli.review.ReviewTopLevelCommands
import skillbill.cli.scaffold.commands.ScaffoldTopLevelCommands
import skillbill.cli.skillremove.RemoveCliCommand
import skillbill.cli.system.DoctorCliCommand
import skillbill.cli.system.UninstallCommand
import skillbill.cli.system.UpdateCheckCommand
import skillbill.cli.system.UpdateCommand
import skillbill.cli.system.VersionCommand
import skillbill.cli.telemetry.TelemetryCommand
import skillbill.cli.work.WorkTopLevelCommands
import skillbill.cli.workflow.WorkflowTopLevelCommands

@Inject
class CliReviewCommands(
  reviewCommands: ReviewTopLevelCommands,
  learningsCommand: LearningsCommand,
  telemetryCommand: TelemetryCommand,
) {
  val commands: List<CliktCommand> =
    reviewCommands.commands +
      listOf(
        learningsCommand,
        telemetryCommand,
      )
}

@Inject
class CliScaffoldCommands(
  scaffoldCommands: ScaffoldTopLevelCommands,
  installCommands: InstallTopLevelCommands,
) {
  val commands: List<CliktCommand> = scaffoldCommands.commands + installCommands.command
}

@Inject
class CliWorkflowCommands(
  workflowCommands: WorkflowTopLevelCommands,
  repoValidationCommands: RepoValidationCliCommands,
  goalRunCommand: GoalRunCommand,
  featureTaskCommands: FeatureTaskCommands,
  featureTaskRuntimeDeprecatedCommand: FeatureTaskRuntimeDeprecatedCommand,
) {
  val commands: List<CliktCommand> =
    workflowCommands.commands +
      repoValidationCommands.commands +
      listOf(
        goalRunCommand,
        featureTaskCommands.command,
        featureTaskRuntimeDeprecatedCommand,
      )
}

@Inject
class CliSystemCommands(
  versionCommand: VersionCommand,
  updateCommand: UpdateCommand,
  updateCheckCommand: UpdateCheckCommand,
  uninstallCommand: UninstallCommand,
  doctorCommand: DoctorCliCommand,
  removeCommand: RemoveCliCommand,
) {
  val commands: List<CliktCommand> =
    listOf(
      versionCommand,
      updateCommand,
      updateCheckCommand,
      uninstallCommand,
      doctorCommand,
      removeCommand,
    )
}

@Inject
class CliMiscCommands(
  codeReviewCommand: CodeReviewCommand,
  phaseCommand: PhaseCommand,
  operationCommand: OperationCommand,
  configCommand: ConfigCommand,
  projectCommand: ProjectCommand,
  workCommands: WorkTopLevelCommands,
  agentAddonCommand: AgentAddonCommand,
  qaCommand: QaCommand,
) {
  val commands: List<CliktCommand> =
    listOf(
      codeReviewCommand,
      phaseCommand,
      operationCommand,
      configCommand,
      projectCommand,
      workCommands.command,
      agentAddonCommand,
      qaCommand,
    )
}

@Inject
class CliCommandProvider(
  reviewCommands: CliReviewCommands,
  scaffoldCommands: CliScaffoldCommands,
  workflowCommands: CliWorkflowCommands,
  systemCommands: CliSystemCommands,
  miscCommands: CliMiscCommands,
) {
  val commands: List<CliktCommand> =
    reviewCommands.commands +
      scaffoldCommands.commands +
      workflowCommands.commands +
      systemCommands.commands +
      miscCommands.commands
}
