package skillbill.cli.model

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.process.InstallerProcessPort
import skillbill.ports.process.InstallerScriptFetchPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

data class CliRuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = EnvironmentContext.UnspecifiedEnvironment,
  val userHome: Path = EnvironmentContext.UnspecifiedUserHome,
  val requester: RemoteTransportPort? = null,
  val runtimeDiagnostics: RuntimeDiagnostics? = null,
  val workflowGitOperations: WorkflowGitOperations? = null,
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
  val executableLookup: ExecutableLookup? = null,
  val runtimeTimingPort: RuntimeTimingPort? = null,
  val hostPlatformPort: HostPlatformPort? = null,
  val installerProcessPort: InstallerProcessPort? = null,
  val installerScriptFetchPort: InstallerScriptFetchPort? = null,
  val repositoryRoot: Path? = null,
  val featureTaskRuntimeRunOverride: ((FeatureTaskRuntimeRunInput) -> FeatureTaskRuntimeRunReport)? = null,
  val liveStdout: (String) -> Unit = {},
  val liveStderr: (String) -> Unit = {},
) {
  fun toRuntimeContext(
    dbPathOverride: String? = this.dbPathOverride,
    userHome: Path = this.userHome,
  ): RuntimeContext =
    RuntimeContext(
      environment =
        EnvironmentContext(
          dbPathOverride = dbPathOverride,
          stdinText = stdinText,
          environment = environment,
          userHome = userHome,
          repositoryRoot = repositoryRoot ?: EnvironmentContext.UnspecifiedRepositoryRoot,
        ),
      transport = TransportContext(requester),
      workflowOps = WorkflowOpsContext(workflowGitOperations),
      callbacks =
        OptionalCallbacks(
          agentRunLauncher = agentRunLauncher,
          runtimeDiagnostics = runtimeDiagnostics,
          goalPullRequestPort = goalPullRequestPort,
          executableLookup = executableLookup,
          runtimeTimingPort = runtimeTimingPort,
          hostPlatformPort = hostPlatformPort,
          installerProcessPort = installerProcessPort,
          installerScriptFetchPort = installerScriptFetchPort,
        ),
    )
}
