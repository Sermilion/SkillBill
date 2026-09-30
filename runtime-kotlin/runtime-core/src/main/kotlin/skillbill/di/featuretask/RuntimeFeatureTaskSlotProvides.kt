package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategyRegistration
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.engine.featuretask.slot.PhaseStrategySelection
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.pullrequest.PullRequestReadinessGate
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.workflow.gitops.WorkflowGitOperations

internal interface RuntimeFeatureTaskSlotProvides {
  @Provides
  fun phaseRunner(
    launcher: GoalRunnerSubtaskLauncher,
    gitOperations: WorkflowGitOperations,
  ): PhaseRunner = DefaultPhaseRunner(launcher, gitOperations)

  @Provides
  fun goalPlanFanOutStrategy(
    fanOutPort: BoundedWorkFanOutPort,
    burstSchedule: GoalPlanningBurstSchedule,
  ): GoalPlanFanOutStrategy = GoalPlanFanOutStrategy(fanOutPort, burstSchedule.planFanOutCap)

  @Provides
  fun prDescriptionStrategy(
    pullRequestIdentityLookup: PullRequestIdentityLookup,
    readinessEvidence: FeatureTaskRuntimeReadinessEvidencePort,
    diagnostics: RuntimeDiagnostics,
    templateFiles: PullRequestTemplateFiles,
  ): PrDescriptionStrategy =
    PrDescriptionStrategy(
      pullRequestIdentityLookup,
      PullRequestReadinessGate(readinessEvidence, diagnostics),
      templateFiles,
    )

  @Provides
  fun phaseStrategyRegistry(
    runner: () -> PhaseRunner,
    reviewRunner: ParallelCodeReviewRunner,
    goalPlanFanOut: GoalPlanFanOutStrategy,
    prDescription: PrDescriptionStrategy,
  ): PhaseStrategyRegistry {
    val inlineReviewRunner = runner()
    val delegatedReviewRunner = runner()
    return PhaseStrategyRegistry(
      listOf(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(goalPlanFanOut, runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyStrategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditStrategy(), runner()),
        PhaseStrategyRegistration(InlineReviewStrategy(inlineReviewRunner), inlineReviewRunner),
        PhaseStrategyRegistration(
          DelegatedReviewStrategy(delegatedReviewRunner, reviewRunner),
          delegatedReviewRunner,
        ),
        PhaseStrategyRegistration(PackBuildStrategy(), runner()),
        PhaseStrategyRegistration(PackValidationStrategy(), runner()),
        PhaseStrategyRegistration(AgentValidateStrategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryStrategy(), runner()),
        PhaseStrategyRegistration(RuntimeCommitStrategy(), runner()),
        PhaseStrategyRegistration(prDescription, runner()),
      ),
    )
  }

  @Provides
  fun phaseStrategySelection(registry: PhaseStrategyRegistry): PhaseStrategySelection =
    PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings)

  @Provides
  fun phaseStrategyLookup(
    registry: PhaseStrategyRegistry,
    selection: PhaseStrategySelection,
  ): PhaseStrategyLookup = PhaseStrategyLookup(registry, selection)
}
