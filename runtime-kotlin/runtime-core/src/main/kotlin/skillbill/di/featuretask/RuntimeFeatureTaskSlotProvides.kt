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
import skillbill.engine.featuretask.slot.audit.opus.AcceptanceAuditOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.opus.DelegatedReviewOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.opus.InlineReviewOpus55Strategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyOpus55Strategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanOpus55Strategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutOpus55Strategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanOpus55Strategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionOpus55Strategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.pullrequest.PullRequestReadinessGate
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryOpus55Strategy
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
  fun goalPlanFanOutOpus55Strategy(goalPlanFanOut: GoalPlanFanOutStrategy): GoalPlanFanOutOpus55Strategy =
    GoalPlanFanOutOpus55Strategy(goalPlanFanOut)

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
  fun prDescriptionOpus55Strategy(prDescription: PrDescriptionStrategy): PrDescriptionOpus55Strategy =
    PrDescriptionOpus55Strategy(prDescription)

  @Provides
  fun phaseStrategyRegistry(
    runner: () -> PhaseRunner,
    reviewRunner: ParallelCodeReviewRunner,
    goalPlanFanOut: GoalPlanFanOutStrategy,
    prDescription: PrDescriptionStrategy,
  ): PhaseStrategyRegistry {
    val inlineReviewRunner = runner()
    val delegatedReviewRunner = runner()
    val standaloneInlineRunner = runner()
    val standaloneDelegatedRunner = runner()
    val opusInlineReviewRunner = runner()
    val opusDelegatedReviewRunner = runner()
    val opusStandaloneInlineRunner = runner()
    val opusStandaloneDelegatedRunner = runner()
    return PhaseStrategyRegistry(
      listOf(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPreplanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(goalPlanFanOut, runner()),
        PhaseStrategyRegistration(GoalPlanFanOutOpus55Strategy(goalPlanFanOut), runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyStrategy(), runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditStrategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditOpus55Strategy(), runner()),
        PhaseStrategyRegistration(InlineReviewStrategy(inlineReviewRunner), inlineReviewRunner),
        PhaseStrategyRegistration(InlineReviewOpus55Strategy(opusInlineReviewRunner), opusInlineReviewRunner),
        PhaseStrategyRegistration(
          DelegatedReviewStrategy(delegatedReviewRunner, reviewRunner),
          delegatedReviewRunner,
        ),
        PhaseStrategyRegistration(
          DelegatedReviewOpus55Strategy(opusDelegatedReviewRunner, reviewRunner),
          opusDelegatedReviewRunner,
        ),
        PhaseStrategyRegistration(InlineStandaloneReviewStrategy(standaloneInlineRunner), standaloneInlineRunner),
        PhaseStrategyRegistration(
          InlineStandaloneReviewOpus55Strategy(opusStandaloneInlineRunner),
          opusStandaloneInlineRunner,
        ),
        PhaseStrategyRegistration(
          DelegatedStandaloneReviewStrategy(standaloneDelegatedRunner, reviewRunner),
          standaloneDelegatedRunner,
        ),
        PhaseStrategyRegistration(
          DelegatedStandaloneReviewOpus55Strategy(opusStandaloneDelegatedRunner, reviewRunner),
          opusStandaloneDelegatedRunner,
        ),
        PhaseStrategyRegistration(PackBuildStrategy(), runner()),
        PhaseStrategyRegistration(PackBuildOpus55Strategy(), runner()),
        PhaseStrategyRegistration(PackValidationStrategy(), runner()),
        PhaseStrategyRegistration(PackValidationOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AgentValidateStrategy(), runner()),
        PhaseStrategyRegistration(AgentValidateOpus55Strategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryStrategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryOpus55Strategy(), runner()),
        PhaseStrategyRegistration(RuntimeCommitStrategy(), runner()),
        PhaseStrategyRegistration(prDescription, runner()),
        PhaseStrategyRegistration(PrDescriptionOpus55Strategy(prDescription), runner()),
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
