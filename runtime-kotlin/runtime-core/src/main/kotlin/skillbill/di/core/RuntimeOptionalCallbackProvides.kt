package skillbill.di.core
import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.host.process.InstallerProcessAdapter
import skillbill.infrastructure.http.HttpInstallerScriptFetchAdapter
import skillbill.infrastructure.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.infrastructure.launcher.agentrun.PathExecutableLookup
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.infrastructure.workflow.git.goal.FileSystemPullRequestTemplateFiles
import skillbill.infrastructure.workflow.github.GhGoalPullRequestPort
import skillbill.infrastructure.workflow.github.GhPullRequestIdentityLookup
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.process.InstallerProcessPort
import skillbill.ports.process.InstallerScriptFetchPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations

internal interface RuntimeOptionalCallbackProvides {
  @Provides
  fun workflowGitOperations(workflowOps: WorkflowOpsContext): WorkflowGitOperations =
    workflowOps.workflowGitOperations ?: GitWorkflowGitOperations()

  @Provides
  fun goalPullRequestPort(callbacks: OptionalCallbacks): GoalPullRequestPort =
    callbacks.goalPullRequestPort ?: GhGoalPullRequestPort()

  @Provides
  fun pullRequestIdentityLookup(): PullRequestIdentityLookup = GhPullRequestIdentityLookup()

  @Provides
  fun pullRequestTemplateFiles(): PullRequestTemplateFiles = FileSystemPullRequestTemplateFiles()

  @Provides
  fun agentRunLauncher(
    callbacks: OptionalCallbacks,
    adapter: FileSystemAgentRunLauncher,
  ): AgentRunLauncher = callbacks.agentRunLauncher ?: adapter

  @Provides
  fun executableLookup(callbacks: OptionalCallbacks): ExecutableLookup =
    callbacks.executableLookup ?: PathExecutableLookup()

  @Provides
  fun installerProcessPort(
    callbacks: OptionalCallbacks,
    adapter: InstallerProcessAdapter,
  ): InstallerProcessPort = callbacks.installerProcessPort ?: adapter

  @Provides
  fun installerScriptFetchPort(
    callbacks: OptionalCallbacks,
    adapter: HttpInstallerScriptFetchAdapter,
  ): InstallerScriptFetchPort = callbacks.installerScriptFetchPort ?: adapter
}
