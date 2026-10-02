package skillbill.engine.featuretask.slot

import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
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
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
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
): PhaseStrategyLookup {
  val runner = { DefaultPhaseRunner(launcher, gitOperations) }
  val codeReviewRunner = reviewRunner?.let { reviewRoutingPhaseRunner(it, runner()) } ?: runner()
  val registry =
    PhaseStrategyRegistry(
      listOfNotNull(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(ImplementThenSimplifyStrategy(), runner()),
        PhaseStrategyRegistration(AcceptanceAuditStrategy(), runner()),
        PhaseStrategyRegistration(InlineReviewStrategy(codeReviewRunner), codeReviewRunner),
        delegatedReviewRunner?.let {
          val delegatedRunner = runner()
          PhaseStrategyRegistration(DelegatedReviewStrategy(delegatedRunner, it), delegatedRunner)
        },
        PhaseStrategyRegistration(PackBuildStrategy(), runner()),
        PhaseStrategyRegistration(PackValidationStrategy(), runner()),
        PhaseStrategyRegistration(AgentValidateStrategy(), runner()),
        PhaseStrategyRegistration(BoundaryHistoryStrategy(), runner()),
        PhaseStrategyRegistration(RuntimeCommitStrategy(), runner()),
        PhaseStrategyRegistration(
          PrDescriptionStrategy(
            pullRequestIdentityLookup,
            PullRequestReadinessGate(readinessEvidence, NoopRuntimeDiagnostics),
            LocalPullRequestTemplateFiles,
          ),
          runner(),
        ),
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
  val registry =
    PhaseStrategyRegistry(
      listOf(
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner()),
        PhaseStrategyRegistration(AgentPlanStrategy(), runner()),
        PhaseStrategyRegistration(GoalPlanFanOutStrategy(fanOutPort, planFanOutCap), runner()),
      ),
    )
  val bindings =
    mapOf(
      SkeletonDefinition.GOAL_PLANNING to
        mapOf(
          PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
          PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(GoalPlanFanOutStrategy.ID),
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
  val shared =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
      PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(AgentPlanStrategy.ID),
      PhaseSlot.IMPLEMENTATION to PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID),
      PhaseSlot.AUDIT to PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { codeReviewStrategyId }),
      PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
    )
  return mapOf(
    SkeletonDefinition.STANDALONE to
      shared +
      mapOf(
        PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
        PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
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
          ),
      ),
    SkeletonDefinition.REVIEW to
      mapOf(
        PhaseSlot.CODE_REVIEW to
          PhaseStrategyBinding.ByFact(
            CodeReviewExecutionMode.entries.associateWith { mode ->
              if (mode == CodeReviewExecutionMode.DELEGATED) codeReviewStrategyId else InlineReviewStrategy.ID
            },
          ),
      ),
    SkeletonDefinition.VALIDATION to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.VALIDATION),
    SkeletonDefinition.PLAN to shared.filterKeys { slot -> slot == PhaseSlot.PREPLAN || slot == PhaseSlot.PLAN },
    SkeletonDefinition.PR to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.PR),
  )
}
