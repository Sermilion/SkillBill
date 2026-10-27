package skillbill.engine.featuretask.slot.skeleton

import skillbill.engine.featuretask.slot.PhaseStrategyBinding
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentFixStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qa.QaStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.featuretask.slot.writehistory.GoalChildHistoryDraftStrategy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

object SkeletonStrategyBindings {
  private val standaloneRevision2Bindings: Map<PhaseSlot, PhaseStrategyBinding> =
    sharedBindings() +
      mapOf(
        PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
        PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
        PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID),
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
      SkeletonDefinition.GOAL_CHILD_REVISION_5 to
        goalChildRevision2Bindings + (
          PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(GoalChildHistoryDraftStrategy.ID)
        ),
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
            ),
        ),
      SkeletonDefinition.VALIDATION to
        mapOf(PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID)),
      SkeletonDefinition.QA to mapOf(PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID)),
      SkeletonDefinition.GOAL_QA to
        mapOf(
          PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID),
          PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(PackBuildStrategy.ID),
        ),
      SkeletonDefinition.GOAL_ASSESSMENT to
        mapOf(PhaseSlot.GOAL_ASSESSMENT to PhaseStrategyBinding.Fixed(GoalAssessmentStrategy.ID)),
      SkeletonDefinition.GOAL_ASSESSMENT_FIX to
        mapOf(PhaseSlot.GOAL_ASSESSMENT to PhaseStrategyBinding.Fixed(GoalAssessmentFixStrategy.ID)),
      SkeletonDefinition.GOAL_HISTORY to
        mapOf(PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID)),
      SkeletonDefinition.PLAN to
        mapOf(
          PhaseSlot.INTAKE to PhaseStrategyBinding.Fixed(RuntimeIntakeStrategy.ID),
          PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
          PhaseSlot.PLAN to planBinding(),
        ),
      SkeletonDefinition.PR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
          PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID),
        ),
      SkeletonDefinition.MONITOR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID),
        ),
      SkeletonDefinition.STANDALONE_MONITOR to
        mapOf(
          PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(StandaloneMonitorPublicationStrategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(StandaloneMonitorStrategy.ID),
        ),
    )

  private fun planBinding(): PhaseStrategyBinding = PhaseStrategyBinding.Fixed(PlanFanOutStrategy.ID)

  private fun sharedBindings(): Map<PhaseSlot, PhaseStrategyBinding> =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
      PhaseSlot.PLAN to planBinding(),
      PhaseSlot.IMPLEMENTATION to PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID),
      PhaseSlot.AUDIT to PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { InlineReviewStrategy.ID }),
      PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
    )
}
