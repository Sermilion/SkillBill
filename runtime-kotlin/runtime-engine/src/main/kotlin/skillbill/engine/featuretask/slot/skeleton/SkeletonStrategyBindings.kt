package skillbill.engine.featuretask.slot.skeleton

import skillbill.engine.featuretask.slot.PhaseStrategyBinding
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.audit.opus.AcceptanceAuditOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.opus.InlineReviewOpus55Strategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyOpus55Strategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutOpus55Strategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanOpus55Strategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionOpus55Strategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qa.QaStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.withOpus
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryOpus55Strategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

object SkeletonStrategyBindings {
  private val standaloneRevision2Bindings: Map<PhaseSlot, PhaseStrategyBinding> =
    sharedBindings() +
      mapOf(
        PhaseSlot.QUALITY_GATE to
          PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID).withOpus(AgentValidateOpus55Strategy.ID),
        PhaseSlot.PULL_REQUEST to
          PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID).withOpus(PrDescriptionOpus55Strategy.ID),
        PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID).withOpus(MonitorOpus55Strategy.ID),
      )

  private val goalChildRevision2Bindings: Map<PhaseSlot, PhaseStrategyBinding> =
    sharedBindings() +
      mapOf(
        PhaseSlot.QUALITY_GATE to
          PhaseStrategyBinding.ByFact(
            mapOf(
              FeatureTaskRuntimeQualityGateSelection.BUILD to PackBuildStrategy.ID,
              FeatureTaskRuntimeQualityGateSelection.VALIDATE to AgentValidateStrategy.ID,
            ),
          ).withOpus(
            mapOf(
              PackBuildStrategy.ID to PackBuildOpus55Strategy.ID,
              AgentValidateStrategy.ID to AgentValidateOpus55Strategy.ID,
            ),
          ),
      )

  val bindings: Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>> =
    mapOf(
      SkeletonDefinition.STANDALONE_REVISION_2 to standaloneRevision2Bindings,
      SkeletonDefinition.GOAL_CHILD_REVISION_2 to goalChildRevision2Bindings,
      SkeletonDefinition.STANDALONE to
        standaloneRevision2Bindings + (PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID)),
      SkeletonDefinition.GOAL_CHILD_REVISION_3 to
        goalChildRevision2Bindings + (PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID)),
      SkeletonDefinition.GOAL_CHILD to goalChildRevision2Bindings,
      SkeletonDefinition.REVIEW to
        mapOf(
          PhaseSlot.STANDALONE_REVIEW to
            PhaseStrategyBinding.ByFact(
              CodeReviewExecutionMode.entries.associateWith { mode ->
                when (mode) {
                  CodeReviewExecutionMode.DELEGATED -> DelegatedStandaloneReviewStrategy.ID
                  CodeReviewExecutionMode.AUTO, CodeReviewExecutionMode.INLINE -> InlineStandaloneReviewStrategy.ID
                }
              },
            ).withOpus(
              mapOf(
                DelegatedStandaloneReviewStrategy.ID to DelegatedStandaloneReviewOpus55Strategy.ID,
                InlineStandaloneReviewStrategy.ID to InlineStandaloneReviewOpus55Strategy.ID,
              ),
            ),
        ),
      SkeletonDefinition.VALIDATION to
        mapOf(
          PhaseSlot.QUALITY_GATE to
            PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID).withOpus(AgentValidateOpus55Strategy.ID),
        ),
      SkeletonDefinition.QA to mapOf(PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID)),
      SkeletonDefinition.GOAL_QA to
        mapOf(
          PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID),
          PhaseSlot.QUALITY_GATE to
            PhaseStrategyBinding.Fixed(PackBuildStrategy.ID).withOpus(PackBuildOpus55Strategy.ID),
        ),
      SkeletonDefinition.PLAN to
        mapOf(
          PhaseSlot.INTAKE to PhaseStrategyBinding.Fixed(RuntimeIntakeStrategy.ID),
          PhaseSlot.PREPLAN to
            PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID).withOpus(AgentPreplanOpus55Strategy.ID),
          PhaseSlot.PLAN to planBinding(),
        ),
      SkeletonDefinition.PR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
          PhaseSlot.PULL_REQUEST to
            PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID).withOpus(PrDescriptionOpus55Strategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID).withOpus(MonitorOpus55Strategy.ID),
        ),
      SkeletonDefinition.MONITOR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID).withOpus(MonitorOpus55Strategy.ID),
        ),
      SkeletonDefinition.STANDALONE_MONITOR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(StandaloneMonitorPublicationStrategy.ID),
          PhaseSlot.MONITOR to
            PhaseStrategyBinding.Fixed(StandaloneMonitorStrategy.ID).withOpus(StandaloneMonitorOpus55Strategy.ID),
        ),
    )

  private fun planBinding(): PhaseStrategyBinding =
    PhaseStrategyBinding.Fixed(PlanFanOutStrategy.ID).withOpus(PlanFanOutOpus55Strategy.ID)

  private fun sharedBindings(): Map<PhaseSlot, PhaseStrategyBinding> =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID).withOpus(AgentPreplanOpus55Strategy.ID),
      PhaseSlot.PLAN to planBinding(),
      PhaseSlot.IMPLEMENTATION to
        PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID).withOpus(ImplementThenSimplifyOpus55Strategy.ID),
      PhaseSlot.AUDIT to
        PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID).withOpus(AcceptanceAuditOpus55Strategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { InlineReviewStrategy.ID })
          .withOpus(InlineReviewOpus55Strategy.ID),
      PhaseSlot.WRITE_HISTORY to
        PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID).withOpus(BoundaryHistoryOpus55Strategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
    )
}
