package skillbill.di.core

import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.process.InstallerProcessPort
import skillbill.ports.process.InstallerScriptFetchPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Duration

data class TransportContext(
  val requester: RemoteTransportPort? = null,
  val connectTimeout: Duration? = null,
  val requestTimeout: Duration? = null,
)

data class WorkflowOpsContext(val workflowGitOperations: WorkflowGitOperations? = null)

data class OptionalCallbacks(
  val agentRunLauncher: AgentRunLauncher? = null,
  val runtimeDiagnostics: RuntimeDiagnostics? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
  val pullRequestIdentityLookup: PullRequestIdentityLookup? = null,
  val pullRequestChecksLookup: PullRequestChecksLookup? = null,
  val executableLookup: ExecutableLookup? = null,
  val runtimeTimingPort: RuntimeTimingPort? = null,
  val hostPlatformPort: HostPlatformPort? = null,
  val installerProcessPort: InstallerProcessPort? = null,
  val installerScriptFetchPort: InstallerScriptFetchPort? = null,
)

data class RuntimeContext(
  val environment: EnvironmentContext,
  val transport: TransportContext,
  val workflowOps: WorkflowOpsContext,
  val callbacks: OptionalCallbacks,
)
