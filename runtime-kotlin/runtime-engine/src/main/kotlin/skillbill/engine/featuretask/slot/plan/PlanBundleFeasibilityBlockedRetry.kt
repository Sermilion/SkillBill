package skillbill.engine.featuretask.slot.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.BlockedPhaseRetryArtifacts
import skillbill.application.workflow.model.BlockedPhaseRetryArtifactsRequest
import skillbill.application.workflow.model.BlockedPhaseRetryArtifactsResult
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.reopenBlockedPlanBundleFeasibilityCycle
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
public class PlanBundleFeasibilityBlockedRetry(
  private val codec: PlanBundleFeasibilityStateCodec,
) : BlockedPhaseRetryArtifacts {
  override fun reopen(request: BlockedPhaseRetryArtifactsRequest): BlockedPhaseRetryArtifactsResult {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
    if (request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS ||
      !family.contains(request.artifacts)
    ) {
      return BlockedPhaseRetryArtifactsResult.Unchanged
    }
    val state = codec.decode(family.value(request.artifacts), "${request.workflowId}/${family.label()}")
    val cycleId = "${request.workflowId}:plan_subtasks:retry-${request.retryLedgerSequence}"
    return when {
      state.mode != PlanBundleFeasibilityMode.BLOCKED -> BlockedPhaseRetryArtifactsResult.Unchanged
      state.activeLaunch != null ->
        BlockedPhaseRetryArtifactsResult.Refused(
          "Plan bundle feasibility for '${request.workflowId}' still has an active launch; resume the workflow " +
            "so recovery can settle it before retrying.",
        )
      else ->
        reopenBlockedPlanBundleFeasibilityCycle(state, cycleId, request.retryLedgerSequence, request.blockedRecord)
          ?.let { reopened ->
            val patch = requireNotNull(WorkflowArtifactPatch.from(mapOf(family.entry(codec.encode(reopened)))))
            BlockedPhaseRetryArtifactsResult.Patch(patch)
          }
          ?: BlockedPhaseRetryArtifactsResult.Refused(
            "Plan bundle feasibility for '${request.workflowId}' cannot open a new review cycle " +
              "from its blocked state.",
          )
    }
  }
}
