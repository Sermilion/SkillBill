package skillbill.engine.featuretask.slot

import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.unusedQaOutcomeRecorder
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineCodeQualityRubricResolver
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
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
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.MonitorRepairPublisher
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPublicationRefusal
import skillbill.ports.monitor.model.MonitorPublicationRequest
import skillbill.ports.monitor.model.MonitorPublicationResult
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

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
  delegatedReviewRunner: ParallelCodeReviewRunner? = null,
  monitoredPullRequestLookup: PullRequestIdentityLookup = OpenPullRequestIdentityLookup(gitOperations),
  pullRequestChecksLookup: PullRequestChecksLookup = PassingPullRequestChecksLookup,
  planFanOutPort: BoundedWorkFanOutPort = SequentialBoundedWorkFanOutPort,
  planFanOutCap: Int = 1,
  standaloneMonitorService: StandaloneMonitorService = UnavailableStandaloneMonitorService.service,
  qaReports: QaReportSettlement = unavailableQaReportSettlement(),
  qaExecution: QaExecutionLifecycle = testQaExecutionLifecycle(),
  qaOutcomes: FeatureTaskQaOutcomeRecorder = unusedQaOutcomeRecorder(),
  inlineRubricResolver: InlineCodeQualityRubricResolver? = null,
): PhaseStrategyLookup {
  val runner = DefaultPhaseRunner(launcher, gitOperations)
  val standaloneMonitor = StandaloneMonitorStrategy(standaloneMonitorService)
  val planFanOut = PlanFanOutStrategy(planFanOutPort, planFanOutCap)
  val codeReviewRunner = reviewRunner?.let { reviewRoutingPhaseRunner(it, runner) } ?: runner
  val registry =
    PhaseStrategyRegistry(
      listOfNotNull(
        PhaseStrategyRegistration(
          RuntimeIntakeStrategy { error("Test phase strategies do not run plan intake.") },
          runner,
        ),
        PhaseStrategyRegistration(AgentPreplanStrategy(), runner),
        PhaseStrategyRegistration(planFanOut, runner),
        PhaseStrategyRegistration(ImplementThenSimplifyStrategy(), runner),
        PhaseStrategyRegistration(AcceptanceAuditStrategy(), runner),
        PhaseStrategyRegistration(InlineReviewStrategy(codeReviewRunner, inlineRubricResolver), codeReviewRunner),
        PhaseStrategyRegistration(
          InlineStandaloneReviewStrategy(codeReviewRunner, inlineRubricResolver),
          codeReviewRunner,
        ),
        delegatedReviewRunner?.let {
          PhaseStrategyRegistration(DelegatedStandaloneReviewStrategy(runner, it), runner)
        },
        PhaseStrategyRegistration(PackBuildStrategy(), runner),
        PhaseStrategyRegistration(AgentValidateStrategy(), runner),
        PhaseStrategyRegistration(BoundaryHistoryStrategy(), runner),
        PhaseStrategyRegistration(GoalChildHistoryDraftStrategy(), runner),
        PhaseStrategyRegistration(RuntimeCommitStrategy(), runner),
        PhaseStrategyRegistration(
          PrDescriptionStrategy(pullRequestIdentityLookup, LocalPullRequestTemplateFiles),
          runner,
        ),
        PhaseStrategyRegistration(MonitorStrategy(monitoredPullRequestLookup, pullRequestChecksLookup), runner),
        PhaseStrategyRegistration(standaloneMonitor, runner),
        PhaseStrategyRegistration(StandaloneMonitorPublicationStrategy(standaloneMonitorService), runner),
        PhaseStrategyRegistration(
          QaStrategy(qaReports, qaExecution, qaOutcomes),
          runner,
        ),
      ),
    )
  val bindings =
    testPhaseStrategyBindings(
      if (delegatedReviewRunner == null) InlineStandaloneReviewStrategy.ID else DelegatedStandaloneReviewStrategy.ID,
    )
  return PhaseStrategyLookup(
    registry,
    PhaseStrategySelection(registry, bindings),
    ModelDirectiveSupplements(enabled = true),
  )
}

fun unavailableQaReportSettlement(): QaReportSettlement =
  QaReportSettlement(
    object : QaWorkspacePort {
      override fun open(
        repoRoot: Path,
        invocationId: String,
      ): QaWorkspace = error("Test phase strategies do not open a QA workspace.")

      override fun readReport(reportPath: Path): QaReportSource? = null

      override fun readRetainedReport(
        repoRoot: Path,
        reference: String,
      ): QaReportSource? = null

      override fun close(workspace: QaWorkspace) = Unit
    },
    NoopRuntimeDiagnostics,
  )

object UnavailablePullRequestIdentityLookup : PullRequestIdentityLookup {
  override fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity = PullRequestIdentity.Unavailable("test runs do not reach GitHub")
}

class OpenPullRequestIdentityLookup(private val git: WorkflowGitCommitHistoryOperations) : PullRequestIdentityLookup {
  override fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity {
    val head = git.headCommitSha(repoRoot)
    check(head is WorkflowGitOperationResult.Ok && head.value.isNotBlank()) {
      "The open pull request fixture needs a published HEAD."
    }
    return PullRequestIdentity.Found(
      url = "https://github.com/example/repo/pull/1",
      number = 1,
      headSha = head.value.trim(),
    )
  }
}

object PassingPullRequestChecksLookup : PullRequestChecksLookup {
  override fun lookup(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestChecks =
    PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.PASS, "https://ci.example/build")))
}

object UnavailableStandaloneMonitorService {
  val source: MonitorPullRequestSource =
    object : MonitorPullRequestSource {
      override fun callerRepository(callerRoot: Path): MonitorCallerRepository =
        MonitorCallerRepository.Unavailable("test runs do not reach GitHub")

      override fun pullRequest(
        repository: MonitorRepositoryIdentity,
        number: Int,
      ): MonitorPullRequestLookup = MonitorPullRequestLookup.Failed("test runs do not reach GitHub")

      override fun pullRequests(repository: MonitorRepositoryIdentity): MonitorPullRequestListing =
        MonitorPullRequestListing.Failed("test runs do not reach GitHub")

      override fun pullRequestsForBranch(
        repository: MonitorRepositoryIdentity,
        headRepository: MonitorRepositoryIdentity,
        branch: String,
      ): MonitorPullRequestListing = MonitorPullRequestListing.Failed("test runs do not reach GitHub")
    }

  val service: StandaloneMonitorService =
    StandaloneMonitorService(
      source = source,
      checks =
        object : MonitorCheckSource {
          override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks =
            PullRequestChecks.Unavailable("test runs do not reach GitHub")

          override fun failureLog(
            pullRequest: MonitorPullRequestIdentity,
            check: PullRequestCheck,
          ): MonitorFailureLog = MonitorFailureLog.Unavailable("test runs do not reach GitHub")
        },
      publisher =
        object : MonitorRepairPublisher {
          override fun publish(request: MonitorPublicationRequest): MonitorPublicationResult =
            MonitorPublicationResult.Rejected(MonitorPublicationRefusal.GUARD_REFUSED, "test runs do not publish")
        },
      clock = Clock.systemUTC(),
    )
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
  standaloneDelegatedId: String = InlineStandaloneReviewStrategy.ID,
): Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>> {
  val shared =
    mapOf(
      PhaseSlot.PREPLAN to PhaseStrategyBinding.Fixed(AgentPreplanStrategy.ID),
      PhaseSlot.PLAN to PhaseStrategyBinding.Fixed(PlanFanOutStrategy.ID),
      PhaseSlot.IMPLEMENTATION to PhaseStrategyBinding.Fixed(ImplementThenSimplifyStrategy.ID),
      PhaseSlot.AUDIT to PhaseStrategyBinding.Fixed(AcceptanceAuditStrategy.ID),
      PhaseSlot.CODE_REVIEW to
        PhaseStrategyBinding.ByFact(CodeReviewExecutionMode.entries.associateWith { InlineReviewStrategy.ID }),
      PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(BoundaryHistoryStrategy.ID),
      PhaseSlot.COMMIT_PUSH to PhaseStrategyBinding.Fixed(RuntimeCommitStrategy.ID),
      PhaseSlot.QA to PhaseStrategyBinding.Fixed(QaStrategy.ID),
    )
  val bindings =
    mapOf(
      SkeletonDefinition.STANDALONE to
        shared +
        mapOf(
          PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
          PhaseSlot.PULL_REQUEST to PhaseStrategyBinding.Fixed(PrDescriptionStrategy.ID),
          PhaseSlot.MONITOR to PhaseStrategyBinding.Fixed(MonitorStrategy.ID),
        ),
      SkeletonDefinition.GOAL_CHILD_REVISION_3 to
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
        mapOf(PhaseSlot.STANDALONE_REVIEW to reviewStandaloneBinding(standaloneDelegatedId)),
      SkeletonDefinition.VALIDATION to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.VALIDATION),
      SkeletonDefinition.QA to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.QA),
      SkeletonDefinition.GOAL_QA to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.GOAL_QA),
      SkeletonDefinition.PLAN to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.PLAN),
      SkeletonDefinition.PR to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.PR),
      SkeletonDefinition.MONITOR to SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.MONITOR),
      SkeletonDefinition.STANDALONE_MONITOR to
        SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.STANDALONE_MONITOR),
    )
  return bindings +
    mapOf(
      SkeletonDefinition.STANDALONE_REVISION_2 to (bindings.getValue(SkeletonDefinition.STANDALONE) - PhaseSlot.QA),
      SkeletonDefinition.GOAL_CHILD_REVISION_2 to
        (bindings.getValue(SkeletonDefinition.GOAL_CHILD_REVISION_3) - PhaseSlot.QA),
      SkeletonDefinition.GOAL_CHILD to (bindings.getValue(SkeletonDefinition.GOAL_CHILD_REVISION_3) - PhaseSlot.QA),
      SkeletonDefinition.GOAL_CHILD_REVISION_5 to (
        bindings.getValue(SkeletonDefinition.GOAL_CHILD_REVISION_3) - PhaseSlot.QA - PhaseSlot.WRITE_HISTORY +
          (PhaseSlot.WRITE_HISTORY to PhaseStrategyBinding.Fixed(GoalChildHistoryDraftStrategy.ID))
      ),
    )
}

private fun reviewStandaloneBinding(delegatedId: String): PhaseStrategyBinding =
  PhaseStrategyBinding.ByFact(
    CodeReviewExecutionMode.entries.associateWith { mode ->
      if (mode == CodeReviewExecutionMode.DELEGATED) delegatedId else InlineStandaloneReviewStrategy.ID
    },
  )
