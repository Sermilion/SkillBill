package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategyBinding
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.engine.featuretask.slot.PhaseStrategySelection
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.pullrequest.PullRequestReadinessGate
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition

internal interface RuntimeFeatureTaskSlotProvides {
  @Provides
  fun phaseRunner(
    launcher: GoalRunnerSubtaskLauncher,
    gitOperations: WorkflowGitOperations,
  ): PhaseRunner = DefaultPhaseRunner(launcher, gitOperations)

  @Provides
  fun phaseStrategyRegistry(
    runner: () -> PhaseRunner,
    pullRequestIdentityLookup: PullRequestIdentityLookup,
    readinessEvidence: FeatureTaskRuntimeReadinessEvidencePort,
    diagnostics: RuntimeDiagnostics,
  ): PhaseStrategyRegistry =
    PhaseStrategyRegistry(
      listOf(
        AgentPreplanStrategy(runner()),
        AgentPlanStrategy(runner()),
        ImplementThenSimplifyStrategy(runner()),
        AcceptanceAuditStrategy(runner()),
        InlineReviewStrategy(runner()),
        PackBuildStrategy(runner()),
        AgentValidateStrategy(runner()),
        BoundaryHistoryStrategy(runner()),
        RuntimeCommitStrategy(runner()),
        PrDescriptionStrategy(
          runner(),
          pullRequestIdentityLookup,
          PullRequestReadinessGate(readinessEvidence, diagnostics),
        ),
      ),
    )

  @Provides
  fun phaseStrategySelection(registry: PhaseStrategyRegistry): PhaseStrategySelection =
    PhaseStrategySelection(
      registry,
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
      ),
    )

  @Provides
  fun phaseStrategyLookup(
    registry: PhaseStrategyRegistry,
    selection: PhaseStrategySelection,
  ): PhaseStrategyLookup = PhaseStrategyLookup(registry, selection)
}

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
