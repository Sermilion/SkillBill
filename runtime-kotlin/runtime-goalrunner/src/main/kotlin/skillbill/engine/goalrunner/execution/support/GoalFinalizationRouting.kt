package skillbill.engine.goalrunner.execution.support

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.featuretask.phaserun.StandalonePhaseRegistrationFactory
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.install.model.SupportedAgent

internal class GoalFinalizationRouting(
  val workflowAgentAssignments: ResolvedWorkflowAgentAssignments?,
  val workflowAgentAssignmentsFingerprint: String?,
  val agentAssignmentsWorkflowId: String?,
  val agentAssignmentsFingerprint: String?,
  val openCodeIdentity: OpenCodeLaunchIdentity?,
)

@Inject
public class GoalFinalizationRoutingBinder(
  private val workflowAgentAssignments: WorkflowAgentAssignmentStore,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val registrations: StandalonePhaseRegistrationFactory,
) {
  internal fun bind(
    parentWorkflowId: String,
    request: GoalRunnerRunRequest,
  ): GoalFinalizationRouting {
    val suppliedAssignments = request.workflowAgentAssignments
    val suppliedFingerprint = request.workflowAgentAssignmentsFingerprint
    return when (val inspection = workflowAgentAssignments.inspect(parentWorkflowId)) {
      is WorkflowAgentAssignmentInspection.Present -> {
        requireNoSuppliedConflict(
          suppliedAssignments != null || suppliedFingerprint != null,
          "The supplied goal routing reference conflicts with the accepted parent artifact.",
        ) {
          suppliedAssignments != inspection.assignments || suppliedFingerprint != inspection.fingerprint
        }
        val frozen = inspection.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN
        GoalFinalizationRouting(
          workflowAgentAssignments = inspection.assignments,
          workflowAgentAssignmentsFingerprint = inspection.fingerprint,
          agentAssignmentsWorkflowId = inspection.assignments.admissionWorkflowId.takeIf { frozen },
          agentAssignmentsFingerprint = inspection.fingerprint.takeIf { frozen },
          openCodeIdentity = openCodeIdentity(parentWorkflowId, request, inspection.assignments),
        )
      }

      WorkflowAgentAssignmentInspection.Legacy -> {
        requireNoSuppliedConflict(
          suppliedAssignments != null || suppliedFingerprint != null,
          "The goal request supplies routing that the accepted parent does not contain.",
        ) { true }
        GoalFinalizationRouting(
          workflowAgentAssignments = null,
          workflowAgentAssignmentsFingerprint = null,
          agentAssignmentsWorkflowId = null,
          agentAssignmentsFingerprint = null,
          openCodeIdentity = openCodeIdentity(parentWorkflowId, request, null),
        )
      }

      is WorkflowAgentAssignmentInspection.Problem ->
        throw SkillBillRuntimeException(inspection.failureCode, inspection.recoveryMessage)
    }
  }

  private inline fun requireNoSuppliedConflict(
    supplied: Boolean,
    message: String,
    conflict: () -> Boolean,
  ) {
    if (supplied && conflict()) {
      throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, message)
    }
  }

  private fun openCodeIdentity(
    parentWorkflowId: String,
    request: GoalRunnerRunRequest,
    assignments: ResolvedWorkflowAgentAssignments?,
  ): OpenCodeLaunchIdentity? {
    val providers =
      buildSet {
        assignments?.phaseAssignments?.values?.forEach { add(it.agentId.wireValue) }
        assignments?.invokedAgentId?.wireValue?.let(::add)
        assignments?.configuredAgentOverrideId?.wireValue?.let(::add)
        add(request.invokedAgentId)
        request.configuredAgentOverrideId?.let(::add)
        request.agentAssignment.override?.let(::add)
        addAll(request.agentAssignment.perPhaseAgentIds.values)
      }
    if (SupportedAgent.OPENCODE.wireValue !in providers) return null
    return statusPublisherFactory.retainedOpenCodeIdentity(parentWorkflowId)
      ?: registrations.openCodeIdentity(request.repoRoot, SupportedAgent.OPENCODE.wireValue)
  }
}
