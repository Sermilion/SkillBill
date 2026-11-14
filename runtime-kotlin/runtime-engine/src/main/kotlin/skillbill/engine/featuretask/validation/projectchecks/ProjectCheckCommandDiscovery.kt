package skillbill.engine.featuretask.validation.projectchecks

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.wireValue
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
public class ProjectCheckCommandDiscovery(
  private val launcher: AgentRunLauncher,
  private val validator: ProjectCheckDiscoveryValidator,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun discover(invocation: ProjectCheckCommandDiscoveryInvocation): ProjectCheckCommandDiscoveryOutcome {
    val launch = invocation.launch
    val prompt =
      ProjectCheckCommandDiscoveryPrompt.build(
        invocation.projectId,
        invocation.checkoutRoot.toString(),
        launch.packSlug,
        invocation.missingCategories,
        launch.validationGate,
      )
    val skillRun =
      SkillRunRequest(
        issueKey = launch.issueKey,
        repoRoot = invocation.checkoutRoot,
        timeout = launch.timeout,
        progressIdleTimeout = launch.timeout,
        progressProbe = launch.progressProbe,
        progressEmitter = launch.progressEmitter,
        outputSink = launch.outputSink,
        promptOverride = prompt,
        readOnlyPhase = true,
        modelOverride = launch.modelOverride,
        effortOverride = launch.effortOverride,
        approval = AgentRunApprovalRequirement.OutsideApproval,
      )
    val outcome =
      catchingNonCancellation {
        launcher.launch(AgentRunLaunchRequest(launch.agentId, skillRun))
      }.getOrElse { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=project_check_discovery outcome=launch_failed project=${invocation.projectId} " +
            "categories=${invocation.missingCategories.joinToString { it.wireValue }}",
          failure,
        )
        return ProjectCheckCommandDiscoveryOutcome.Unavailable(
          ProjectCheckCommandDiscoveryUnavailableReason.LAUNCH_FAILURE,
          null,
        )
      }
    return when (outcome) {
      is AgentRunLaunchDenied ->
        unavailable(
          invocation,
          ProjectCheckCommandDiscoveryUnavailableReason.DENIED,
          null,
        )
      is UnsupportedAgentRunLaunch ->
        unavailable(
          invocation,
          ProjectCheckCommandDiscoveryUnavailableReason.UNSUPPORTED_AGENT,
          null,
        )
      is AgentRunLaunchFacts -> parseFacts(invocation, outcome)
    }
  }

  private fun parseFacts(
    invocation: ProjectCheckCommandDiscoveryInvocation,
    facts: AgentRunLaunchFacts,
  ): ProjectCheckCommandDiscoveryOutcome {
    val stdout = facts.stdout
    val unavailableReason =
      when {
        !facts.processStarted -> ProjectCheckCommandDiscoveryUnavailableReason.SPAWN_FAILED
        facts.stdoutTruncated -> ProjectCheckCommandDiscoveryUnavailableReason.TRUNCATED_OUTPUT
        else ->
          when (val termination = facts.termination) {
            AgentRunTermination.TimedOut -> ProjectCheckCommandDiscoveryUnavailableReason.TIMED_OUT
            AgentRunTermination.Interrupted -> ProjectCheckCommandDiscoveryUnavailableReason.INTERRUPTED
            AgentRunTermination.SpawnFailed -> ProjectCheckCommandDiscoveryUnavailableReason.SPAWN_FAILED
            is AgentRunTermination.Exited ->
              if (termination.code == 0) {
                null
              } else {
                ProjectCheckCommandDiscoveryUnavailableReason.NON_ZERO_EXIT
              }
          }
      }
    if (unavailableReason != null) {
      return unavailable(invocation, unavailableReason, stdout)
    }
    return catchingNonCancellation {
      validator.validate(
        stdout.trim(),
        invocation.projectId,
        invocation.missingCategories,
        "agent stdout",
      )
    }.fold(
      onSuccess = { response -> ProjectCheckCommandDiscoveryOutcome.Succeeded(response) },
      onFailure = { failure ->
        val rejected = failure as? SkillBillRuntimeException ?: throw failure
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=project_check_discovery outcome=contract_rejected project=${invocation.projectId} " +
            "categories=${invocation.missingCategories.joinToString { it.wireValue }}",
          rejected,
        )
        ProjectCheckCommandDiscoveryOutcome.ContractRejected(rejected)
      },
    )
  }

  private fun unavailable(
    invocation: ProjectCheckCommandDiscoveryInvocation,
    reason: ProjectCheckCommandDiscoveryUnavailableReason,
    retainedOutput: String?,
  ): ProjectCheckCommandDiscoveryOutcome {
    diagnostics.warning(
      "seam=project_check_discovery outcome=unavailable reason=$reason project=${invocation.projectId} " +
        "categories=${invocation.missingCategories.joinToString { it.wireValue }}",
    )
    return ProjectCheckCommandDiscoveryOutcome.Unavailable(reason, retainedOutput)
  }
}
