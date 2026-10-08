package skillbill.engine.featuretask.slot

import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.audit.opus.AcceptanceAuditOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.opus.DelegatedReviewOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.opus.InlineReviewOpus55Strategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyOpus55Strategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
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
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import java.nio.file.Files
import java.nio.file.Path

fun statusProjectionPhaseStrategies(): PhaseStrategyLookup =
  testPhaseStrategies(
    GoalRunnerSubtaskLauncher { error("Status projection must not launch a phase.") },
    NoopWorkflowGitOperations,
    ApprovingReviewPhaseRunner,
  )

fun testPhaseStrategies(
  launcher: GoalRunnerSubtaskLauncher,
  gitOperations: WorkflowGitOperations,
  reviewRunner: PhaseRunner? = null,
  pullRequestIdentityLookup: PullRequestIdentityLookup = UnavailablePullRequestIdentityLookup,
  readinessEvidence: FeatureTaskRuntimeReadinessEvidencePort = AbsentReadinessEvidence,
  delegatedReviewRunner: ParallelCodeReviewRunner? = null,
  monitoredPullRequestLookup: PullRequestIdentityLookup = OpenPullRequestIdentityLookup,
  pullRequestChecksLookup: PullRequestChecksLookup = PassingPullRequestChecksLookup,
): PhaseStrategyLookup {
  val runner = { DefaultPhaseRunner(launcher, gitOperations) }
  val codeReviewRunner = reviewRunner?.let { reviewRoutingPhaseRunner(it, runner()) } ?: runner()
  val prCanonical =
    PrDescriptionStrategy(
      pullRequestIdentityLookup,
      PullRequestReadinessGate(readinessEvidence, NoopRuntimeDiagnostics),
      LocalPullRequestTemplateFiles,
    )
  val prOpus = PrDescriptionOpus55Strategy(prCanonical)
  val monitorCanonical = MonitorStrategy(monitoredPullRequestLookup, pullRequestChecksLookup)
  val monitorOpus = MonitorOpus55Strategy(monitorCanonical)
  val registry =
    PhaseStrategyRegistry(
      listOfNotNull(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPreplanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyStrategy(), runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditStrategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditOpus55Strategy(), runner()),
        PhaseStrategyRegistration(InlineReviewStrategy(codeReviewRunner), codeReviewRunner),
        PhaseStrategyRegistration(InlineReviewOpus55Strategy(codeReviewRunner), codeReviewRunner),
        PhaseStrategyRegistration(InlineStandaloneReviewStrategy(codeReviewRunner), codeReviewRunner),
        PhaseStrategyRegistration(InlineStandaloneReviewOpus55Strategy(codeReviewRunner), codeReviewRunner),
        delegatedReviewRunner?.let {
          val delegatedRunner = runner()
          PhaseStrategyRegistration(DelegatedStandaloneReviewStrategy(delegatedRunner, it), delegatedRunner)
        },
        delegatedReviewRunner?.let {
          val delegatedRunner = runner()
          PhaseStrategyRegistration(DelegatedStandaloneReviewOpus55Strategy(delegatedRunner, it), delegatedRunner)
        },
        delegatedReviewRunner?.let {
          val delegatedRunner = runner()
          PhaseStrategyRegistration(DelegatedReviewStrategy(delegatedRunner, it), delegatedRunner)
        },
        delegatedReviewRunner?.let {
          val delegatedRunner = runner()
          PhaseStrategyRegistration(DelegatedReviewOpus55Strategy(delegatedRunner, it), delegatedRunner)
        },
        PhaseStrategyRegistration(PackBuildStrategy(), runner()),
        PhaseStrategyRegistration(PackBuildOpus55Strategy(), runner()),
        PhaseStrategyRegistration(PackValidationStrategy(), runner()),
        PhaseStrategyRegistration(PackValidationOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AgentValidateStrategy(), runner()),
        PhaseStrategyRegistration(AgentValidateOpus55Strategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryStrategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryOpus55Strategy(), runner()),
        PhaseStrategyRegistration(RuntimeCommitStrategy(), runner()),
        PhaseStrategyRegistration(prCanonical, runner()),
        PhaseStrategyRegistration(prOpus, runner()),
        PhaseStrategyRegistration(monitorCanonical, runner()),
        PhaseStrategyRegistration(monitorOpus, runner()),
      ),
    )
  val codeReviewStrategyId = delegatedReviewRunner?.let { DelegatedReviewStrategy.ID } ?: InlineReviewStrategy.ID
  val bindings = testPhaseStrategyBindings(codeReviewStrategyId)
  return PhaseStrategyLookup(registry, PhaseStrategySelection(registry, bindings))
}

fun goalPlanningPhaseStrategies(
  launcher: GoalRunnerSubtaskLauncher,
  fanOutPort: BoundedWorkFanOutPort,
  planFanOutCap: Int,
): PhaseStrategyLookup {
  val runner = { DefaultPhaseRunner(launcher, NoopWorkflowGitOperations) }
  val fanOut = GoalPlanFanOutStrategy(fanOutPort, planFanOutCap)
  val registry =
    PhaseStrategyRegistry(
      listOf(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPreplanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanOpus55Strategy(), runner()),
        PhaseStrategyRegistration(fanOut, runner()),
        PhaseStrategyRegistration(GoalPlanFanOutOpus55Strategy(fanOut), runner()),
      ),
    )
  val bindings =
    mapOf(
      SkeletonDefinition.GOAL_PLANNING to
        mapOf(
          PhaseSlot.PREPLAN to
            PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID).withOpus(AgentPreplanOpus55Strategy.ID),
          PhaseSlot.PLAN to
            PhaseStrategyBinding.Fixed(GoalPlanFanOutStrategy.ID).withOpus(GoalPlanFanOutOpus55Strategy.ID),
        ),
    )
  return PhaseStrategyLookup(registry, PhaseStrategySelection(registry, bindings))
}

object AbsentReadinessEvidence : FeatureTaskRuntimeReadinessEvidencePort {
  override fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence? = null

  override fun persistReadinessEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  ) = error("Absent readiness evidence cannot persist evidence.")
}

object UnavailablePullRequestIdentityLookup : PullRequestIdentityLookup {
  override fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity = PullRequestIdentity.Unavailable("test runs do not reach GitHub")
}

object OpenPullRequestIdentityLookup : PullRequestIdentityLookup {
  override fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity = PullRequestIdentity.Found(url = "https://github.com/example/repo/pull/1", number = 1)
}

object PassingPullRequestChecksLookup : PullRequestChecksLookup {
  override fun lookup(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestChecks =
    PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.PASS, "https://ci.example/build")))
}

object LocalPullRequestTemplateFiles : PullRequestTemplateFiles {
  override fun regularFile(path: Path): Path? = path.takeIf { Files.isRegularFile(it) }?.toRealPath()

  override fun markdownFiles(directory: Path): List<Path> =
    if (Files.isDirectory(directory)) {
      Files.list(directory).use { entries -> entries.toList() }
        .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".md") }
        .map { entry -> entry.toRealPath() }
        .sortedBy { entry -> entry.fileName.toString() }
    } else {
      emptyList()
    }

  override fun readText(file: Path): String = Files.readString(file)
}

fun testPhaseStrategyBindings(
  codeReviewStrategyId: String = InlineReviewStrategy.ID,
): Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>> {
  val codeReviewOpusId =
    if (codeReviewStrategyId == DelegatedReviewStrategy.ID) {
      DelegatedReviewOpus55Strategy.ID
    } else {
      InlineReviewOpus55Strategy.ID
    }
  val standaloneDelegatedId =
    if (codeReviewStrategyId == DelegatedReviewStrategy.ID) {
      DelegatedStandaloneReviewStrategy.ID
    } else {
      codeReviewStrategyId
    }
  val standaloneDelegatedOpusId =
    if (codeReviewStrategyId == DelegatedReviewStrategy.ID) {
      DelegatedStandaloneReviewOpus55Strategy.ID
    } else {
      InlineStandaloneReviewOpus55Strategy.ID
    }
  val shared =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID).withOpus(AgentPreplanOpus55Strategy.ID),
      PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(AgentPlanStrategy.ID).withOpus(AgentPlanOpus55Strategy.ID),
      PhaseSlot.IMPLEMENTATION to
        PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID).withOpus(ImplementThenSimplifyOpus55Strategy.ID),
      PhaseSlot.AUDIT to
        PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID).withOpus(AcceptanceAuditOpus55Strategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { codeReviewStrategyId })
          .withOpus(codeReviewOpusId),
      PhaseSlot.WRITE_HISTORY to
        PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID).withOpus(BoundaryHistoryOpus55Strategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
    )
  return mapOf(
    SkeletonDefinition.STANDALONE to
      shared +
      mapOf(
        PhaseSlot.QUALITY_GATE to
          PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID).withOpus(AgentValidateOpus55Strategy.ID),
        PhaseSlot.PULL_REQUEST to
          PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID).withOpus(PrDescriptionOpus55Strategy.ID),
        PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID).withOpus(MonitorOpus55Strategy.ID),
      ),
    SkeletonDefinition.GOAL_CHILD to
      shared +
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
      ),
    SkeletonDefinition.REVIEW to
      mapOf(
        PhaseSlot.STANDALONE_REVIEW to
          reviewStandaloneBinding(standaloneDelegatedId, standaloneDelegatedOpusId),
      ),
    SkeletonDefinition.VALIDATION to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.VALIDATION),
    SkeletonDefinition.PLAN to shared.filterKeys { slot -> slot == PhaseSlot.PREPLAN || slot == PhaseSlot.PLAN },
    SkeletonDefinition.PR to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.PR),
    SkeletonDefinition.MONITOR to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.MONITOR),
  )
}

private fun reviewStandaloneBinding(
  delegatedId: String,
  delegatedOpusId: String,
): PhaseStrategyBinding {
  val facts =
    PhaseStrategyBinding.ByFact(
      CodeReviewExecutionMode.entries.associateWith { mode ->
        if (mode == CodeReviewExecutionMode.DELEGATED) delegatedId else InlineStandaloneReviewStrategy.ID
      },
    )
  return if (delegatedId == InlineStandaloneReviewStrategy.ID) {
    facts.withOpus(InlineStandaloneReviewOpus55Strategy.ID)
  } else {
    facts.withOpus(
      mapOf(
        InlineStandaloneReviewStrategy.ID to InlineStandaloneReviewOpus55Strategy.ID,
        delegatedId to delegatedOpusId,
      ),
    )
  }
}
