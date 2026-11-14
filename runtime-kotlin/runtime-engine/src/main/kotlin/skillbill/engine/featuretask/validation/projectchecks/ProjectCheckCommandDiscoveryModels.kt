package skillbill.engine.featuretask.validation.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryResponse
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.scaffold.model.ValidationGateDeclaration
import java.nio.file.Path
import kotlin.time.Duration

public data class ProjectCheckCommandDiscoveryRequest(
  public val packSlug: String,
  public val validationGate: ValidationGateDeclaration,
  public val issueKey: String,
  public val agentId: String,
  public val modelOverride: String?,
  public val effortOverride: String?,
  public val timeout: Duration?,
  public val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  public val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  public val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
)

public data class ProjectCheckCommandDiscoveryInvocation(
  public val projectId: String,
  public val checkoutRoot: Path,
  public val missingCategories: Set<ProjectCheckCategory>,
  public val launch: ProjectCheckCommandDiscoveryRequest,
)

public enum class ProjectCheckCommandDiscoveryUnavailableReason {
  DENIED,
  UNSUPPORTED_AGENT,
  NON_ZERO_EXIT,
  TIMED_OUT,
  INTERRUPTED,
  SPAWN_FAILED,
  TRUNCATED_OUTPUT,
  LAUNCH_FAILURE,
}

public sealed interface ProjectCheckCommandDiscoveryOutcome {
  public data class Succeeded(val response: ProjectCheckDiscoveryResponse) : ProjectCheckCommandDiscoveryOutcome

  public data class Unavailable(
    val reason: ProjectCheckCommandDiscoveryUnavailableReason,
    val retainedOutput: String?,
  ) : ProjectCheckCommandDiscoveryOutcome

  public data class ContractRejected(val failure: SkillBillRuntimeException) : ProjectCheckCommandDiscoveryOutcome
}

public data class ProjectCheckCommandResolverRequest(
  public val workingDirectory: Path,
  public val requestedCategories: Set<ProjectCheckCategory>,
  public val discovery: ProjectCheckCommandDiscoveryRequest,
)
