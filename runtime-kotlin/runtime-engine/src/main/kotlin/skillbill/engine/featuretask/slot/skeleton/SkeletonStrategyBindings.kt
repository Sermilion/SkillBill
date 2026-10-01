package skillbill.engine.featuretask.slot.skeleton

import skillbill.engine.featuretask.slot.PhaseStrategyBinding
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

object SkeletonStrategyBindings {
  val bindings: Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>> =
    mapOf(
      SkeletonDefinition.STANDALONE to
        sharedBindings() +
        mapOf(
          PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
          PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
        ),
      SkeletonDefinition.GOAL_CHILD to
        sharedBindings() +
        mapOf(
          PhaseSlot.QUALITY_GATE to
            PhaseStrategyBinding.ByFact(
              mapOf(
                FeatureTaskRuntimeQualityGateSelection.BUILD to PackBuildStrategy.ID,
                FeatureTaskRuntimeQualityGateSelection.VALIDATE to AgentValidateStrategy.ID,
              ),
            ),
        ),
      SkeletonDefinition.REVIEW to
        mapOf(
          PhaseSlot.CODE_REVIEW to
            PhaseStrategyBinding.ByFact(
              CodeReviewExecutionMode.entries.associateWith { mode ->
                when (mode) {
                  CodeReviewExecutionMode.DELEGATED -> DelegatedReviewStrategy.ID
                  CodeReviewExecutionMode.AUTO, CodeReviewExecutionMode.INLINE -> InlineReviewStrategy.ID
                }
              },
            ),
        ),
      SkeletonDefinition.VALIDATION to
        mapOf(PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(PackValidationStrategy.ID)),
      SkeletonDefinition.PLAN to
        mapOf(
          PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
          PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(AgentPlanStrategy.ID),
        ),
      SkeletonDefinition.GOAL_PLANNING to
        mapOf(
          PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
          PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(GoalPlanFanOutStrategy.ID),
        ),
      SkeletonDefinition.PR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
          PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
        ),
    )

  private fun sharedBindings(): Map<PhaseSlot, PhaseStrategyBinding> =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
      PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(AgentPlanStrategy.ID),
      PhaseSlot.IMPLEMENTATION to PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID),
      PhaseSlot.AUDIT to PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { InlineReviewStrategy.ID }),
      PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
    )
}
