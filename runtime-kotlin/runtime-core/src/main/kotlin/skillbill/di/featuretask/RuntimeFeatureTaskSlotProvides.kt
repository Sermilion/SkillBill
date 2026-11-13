package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.engine.featuretask.phaserun.LegacySpecReplanSource
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategyRegistration
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.engine.featuretask.slot.PhaseStrategySelection
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.ParallelReviewInlineRubricResolver
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentFixStrategy
import skillbill.engine.featuretask.slot.goalassessment.GoalAssessmentStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.intake.FeatureTaskRuntimePlanIntake
import skillbill.engine.featuretask.slot.intake.LegacySpecReplanIntake
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.RuntimeMonitorRepairPublicationGate
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
import skillbill.engine.featuretask.validation.check.ValidationLiveChecks
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.featuretask.PhaseInstructionFileSource
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.workflow.gitops.WorkflowGitOperations

internal interface RuntimeFeatureTaskSlotProvides {
  @Provides
  fun phaseRunner(
    launcher: GoalRunnerSubtaskLauncher,
    gitOperations: WorkflowGitOperations,
    liveChecks: ValidationLiveChecks,
  ): PhaseRunner = DefaultPhaseRunner(launcher, gitOperations, liveChecks)

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
  fun pullRequestPhaseStrategies(
    prDescription: PrDescriptionStrategy,
    monitor: MonitorStrategy,
    standaloneMonitor: StandaloneMonitorService,
    publicationGate: RuntimeMonitorRepairPublicationGate,
  ): PullRequestPhaseStrategies =
    PullRequestPhaseStrategies(
      prDescription,
      monitor,
      StandaloneMonitorStrategy(standaloneMonitor, publicationGate),
      StandaloneMonitorPublicationStrategy(standaloneMonitor),
    )

  @Provides
  fun reviewPhaseStrategies(
    reviewRunner: ParallelCodeReviewRunner,
    comments: DiffCommentService,
  ): (PhaseRunner) -> ReviewPhaseStrategies =
    { runner ->
      val inlineRubricResolver = ParallelReviewInlineRubricResolver(reviewRunner)
      ReviewPhaseStrategies(
        InlineReviewStrategy(runner, inlineRubricResolver, comments),
        InlineStandaloneReviewStrategy(runner, inlineRubricResolver, comments),
        DelegatedStandaloneReviewStrategy(runner, reviewRunner, comments),
      )
    }

  @Provides
  fun phaseStrategyRegistry(
    runner: PhaseRunner,
    review: (PhaseRunner) -> ReviewPhaseStrategies,
    planning: PlanningPhaseStrategies,
    pullRequest: PullRequestPhaseStrategies,
    qa: QaStrategy,
  ): PhaseStrategyRegistry {
    val reviewStrategies = review(runner)
    return PhaseStrategyRegistry(
      listOf(
        planning.intake,
        AgentPreplanStrategy(),
        planning.fanOut,
        ImplementThenSimplifyStrategy(),
        AcceptanceAuditStrategy(),
        reviewStrategies.inline,
        reviewStrategies.inlineStandalone,
        reviewStrategies.delegatedStandalone,
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
  fun legacySpecReplanSource(intake: LegacySpecReplanIntake): LegacySpecReplanSource = intake

  @Provides
  fun phaseStrategyLookup(
    registry: PhaseStrategyRegistry,
    selection: PhaseStrategySelection,
    phaseInstructionSource: PhaseInstructionFileSource,
  ): PhaseStrategyLookup =
    PhaseStrategyLookup(
      registry,
      selection,
      phaseInstructionSource,
    )
}
