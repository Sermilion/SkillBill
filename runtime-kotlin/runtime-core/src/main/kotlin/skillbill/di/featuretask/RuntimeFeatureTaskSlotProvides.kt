package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategyRegistration
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.engine.featuretask.slot.PhaseStrategySelection
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewRubricResolver
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentFixStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorService
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qa.QaStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.featuretask.slot.writehistory.GoalChildHistoryDraftStrategy
import skillbill.engine.goalrunner.plan.FeatureTaskRuntimePlanIntake
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import skillbill.model.EnvironmentContext
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.featuretask.PhaseInstructionFileSource
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.MergeStatePullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
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
  fun planningPhaseStrategies(
    fanOutPort: BoundedWorkFanOutPort,
    planIntake: () -> FeatureTaskRuntimePlanIntake,
  ): PlanningPhaseStrategies =
    PlanningPhaseStrategies(
      RuntimeIntakeStrategy(planIntake),
      PlanFanOutStrategy(fanOutPort, GOAL_PLANNING_WAVE_CAP),
    )

  @Provides
  fun prDescriptionStrategy(
    pullRequestIdentityLookup: PullRequestIdentityLookup,
    templateFiles: PullRequestTemplateFiles,
  ): PrDescriptionStrategy =
    PrDescriptionStrategy(
      pullRequestIdentityLookup,
      templateFiles,
    )

  @Provides
  fun monitorStrategy(
    pullRequestIdentityLookup: MergeStatePullRequestIdentityLookup,
    pullRequestChecksLookup: PullRequestChecksLookup,
  ): MonitorStrategy = MonitorStrategy(pullRequestIdentityLookup, pullRequestChecksLookup)

  @Provides
  fun pullRequestPhaseStrategies(
    prDescription: PrDescriptionStrategy,
    monitor: MonitorStrategy,
    standaloneMonitor: StandaloneMonitorService,
  ): PullRequestPhaseStrategies =
    PullRequestPhaseStrategies(
      prDescription,
      monitor,
      StandaloneMonitorStrategy(standaloneMonitor),
      StandaloneMonitorPublicationStrategy(standaloneMonitor),
    )

  @Provides
  fun phaseStrategyRegistry(
    runner: PhaseRunner,
    reviewRunner: ParallelCodeReviewRunner,
    planning: PlanningPhaseStrategies,
    pullRequest: PullRequestPhaseStrategies,
    qa: QaStrategy,
  ): PhaseStrategyRegistry {
    val inlineRubricResolver = InlineReviewRubricResolver(reviewRunner::resolveInlineReviewRubrics)
    return PhaseStrategyRegistry(
      listOf(
        planning.intake,
        AgentPreplanStrategy(),
        planning.fanOut,
        ImplementThenSimplifyStrategy(),
        AcceptanceAuditStrategy(),
        InlineReviewStrategy(runner, inlineRubricResolver),
        InlineStandaloneReviewStrategy(runner, inlineRubricResolver),
        DelegatedStandaloneReviewStrategy(runner, reviewRunner),
        PackBuildStrategy(),
        AgentValidateStrategy(),
        BoundaryHistoryStrategy(),
        GoalChildHistoryDraftStrategy(),
        RuntimeCommitStrategy(),
        pullRequest.prDescription,
        pullRequest.monitor,
        pullRequest.standaloneMonitor,
        pullRequest.standalonePublication,
        qa,
        GoalAssessmentStrategy(),
        GoalAssessmentFixStrategy(),
      ).map { strategy -> PhaseStrategyRegistration(strategy, runner) },
    )
  }

  @Provides
  fun phaseStrategySelection(registry: PhaseStrategyRegistry): PhaseStrategySelection =
    PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings)

  @Provides
  fun phaseInstructionFileSource(adapter: FileSystemPhaseInstructionFileSource): PhaseInstructionFileSource = adapter

  @Provides
  fun phaseStrategyLookup(
    registry: PhaseStrategyRegistry,
    selection: PhaseStrategySelection,
    environmentContext: EnvironmentContext,
    phaseInstructionSource: PhaseInstructionFileSource,
  ): PhaseStrategyLookup =
    PhaseStrategyLookup(
      registry,
      selection,
      ModelDirectiveSupplementsSetting.resolve(environmentContext.environment),
      phaseInstructionSource,
    )
}
