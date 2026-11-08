package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.launchArguments
import skillbill.engine.featuretask.phaserun.monitor.MONITOR_BRANCH_PREFIX
import skillbill.engine.goal.GoalPreflightGateBlock
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.executionLivenessBlocksIdleSafeRefresh
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goal.goalRepositoryRootFromIdentity
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.eligibility.recordedLaunchAgent
import skillbill.engine.work.observation.workerOwnership
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.ExecutionPlanArtifactView
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock

@Inject
public class WorkRunActions(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val goals: GoalRunnerStatusPort,
  private val branches: CheckedOutBranchSource,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val launcher: WorkRunLauncher,
  private val clock: Clock,
  private val preflight: GoalPreflightPort,
  private val environment: EnvironmentContext,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  internal fun startGoal(
    repoRoot: Path,
    issueKey: String,
    agentId: String?,
    qaDevice: QaNamedDevice? = null,
  ): WorkControlResult {
    val agent =
      agentId?.takeIf { it in SupportedAgent.supportedIds }
        ?: return WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED)
    val result =
      preflight.preflight(
        GoalPreflightRequest(
          issueKey,
          repoRoot,
          invokedAgentId = agent,
          userHome = environment.userHome,
          environment = environment.environment,
        ),
      )
    goalStartRefusal(result)?.let { return WorkControlResult.LaunchFailed(it) }
    val key = result.issueKey
    val ownershipKey = "${goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)}:$key:goal"
    return database.transaction { unit ->
      val request =
        WorkRunLaunchRequest(
          repoRoot,
          unit.dbPath,
          ownershipKey,
          WorkRunLaunchKind.RuntimeCommand(runtimeResumeArguments(goalArguments(key, agent, qaDevice), repoRoot)),
          emptyMap(),
        )
      when (val launched = launcher.start(request)) {
        is WorkRunLaunchResult.Refused -> WorkControlResult.LaunchFailed(launched.reason)
        is WorkRunLaunchResult.Started, is WorkRunLaunchResult.AwaitingConfirmation -> WorkControlResult.Accepted
      }
    }
  }

  internal fun launch(
    target: WorkRunTarget,
    agentId: String?,
    qaDevice: QaNamedDevice? = null,
  ): WorkControlResult {
    val operation = operation@{ unit: UnitOfWork ->
      val item =
        matchingRun(status, target)
          ?: return@operation WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      val options = options(item, unit)
      if (!options.canLaunch) return@operation WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
      val agent =
        (agentId ?: options.agentId)?.takeIf { it in options.agentChoices }
          ?: return@operation WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED)
      val phase = unit.standalonePhaseStatuses.readByInvocation(target.id)
      if (phase != null) {
        checkoutRejection(phase, target)?.let { return@operation WorkControlResult.Rejected(it) }
      }
      val arguments =
        arguments(item, phase, unit, agent, qaDevice)
          ?: return@operation WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
      val result =
        launcher.start(
          WorkRunLaunchRequest(
            target.repositoryRoot,
            unit.dbPath,
            workRunOwnershipKey(item, phase),
            launchKind(item, phase, arguments, agent, target),
            recordedAgentEnvironment(item, phase, unit),
          ),
        )
      if (result is WorkRunLaunchResult.Refused) return@operation WorkControlResult.LaunchFailed(result.reason)
      restartCanceled(item, unit)
      if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL) {
        goals.resume(requireNotNull(item.snapshot.issueKey), target.repositoryRoot)
      }
      WorkControlResult.Accepted
    }
    return when (val result = database.transactionFenced(target.databaseFileObservation, operation)) {
      FencedTransactionResult.ChangedTarget -> WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  private fun checkoutRejection(
    phase: StandalonePhaseStatusRecord,
    target: WorkRunTarget,
  ): WorkControlRejection? {
    val branch = phase.branchCorrelation
    if (phase.monitorTarget != null || branch.isBlank() || branch == "HEAD") return null
    val current = branches.checkedOutBranch(target.repositoryRoot) ?: return WorkControlRejection.BRANCH_UNAVAILABLE
    return WorkControlRejection.BRANCH_CHANGED.takeIf { current != branch }
  }

  private fun restartCanceled(
    item: WorkDetailItem,
    unit: UnitOfWork,
  ) {
    if (item.snapshot.lifecycleState != IdeStatusLifecycleState.CANCELED) return
    val workflowId = item.snapshot.workflowId ?: return
    val now = clock.instant()
    if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL) {
      check(unit.lifecycleTelemetry.restartCanceledGoalIssue(workflowId, requireNotNull(item.snapshot.issueKey), now))
    }
    listOfNotNull(workflowId, item.goalStatus?.currentChildWorkflowId).distinct().forEach { id ->
      val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)
      if (workflow?.workflowStatus == WorkflowStatus.ABANDONED) {
        unit.workflowStates.saveAcceptedSnapshotMutation(
          WorkflowFamily.TASK_RUNTIME,
          workflow.copy(workflowStatus = WorkflowStatus.PAUSED, finishedAt = null, updatedAt = now),
        )
      }
    }
  }

  internal fun options(
    item: WorkDetailItem,
    unit: UnitOfWork,
  ): WorkRunOptions {
    val snapshot = item.snapshot
    val phase = snapshot.execution?.invocationId?.let(unit.standalonePhaseStatuses::readByInvocation)
    val worker = workerOwnership(phase, snapshot.workflowId, unit)
    val liveWorker = worker != null && supervisor.inspect(worker) != FeatureTaskRuntimeProcessInspection.NotRunning
    val completed = snapshot.lifecycleState == IdeStatusLifecycleState.TERMINAL
    val canceled = snapshot.lifecycleState == IdeStatusLifecycleState.CANCELED
    val standaloneValidation = isStandaloneValidation(phase)
    val terminalValidation = completed && standaloneValidation
    val validationResume = standaloneValidation && isPausedOrBlocked(snapshot.lifecycleState)
    val agent = launchAgent(item, phase, unit)
    val supported =
      arguments(
        item,
        phase,
        unit,
        agent ?: SupportedAgent.supportedIds.first(),
        qaDevice = null,
      ) != null
    val live =
      snapshot.lifecycleState == IdeStatusLifecycleState.ACTIVE || liveWorker ||
        item.goalStatus?.executionLiveness?.let { executionLivenessBlocksIdleSafeRefresh(it) } == true
    val launchable = canLaunch(completed, terminalValidation, live)
    return WorkRunOptions(
      !completed && !canceled,
      launchable && supported,
      launchLabel(canceled, terminalValidation, validationResume, phase),
      agent?.takeIf { it in SupportedAgent.supportedIds },
      SupportedAgent.supportedIds,
      "This older entry has no complete launch details.".takeIf {
        launchable && !supported
      },
      goalStartedForPlan(phase, unit),
    )
  }

  private fun goalStartedForPlan(
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
  ): Boolean {
    val plan =
      phase
        ?.takeIf { it.launchScope == IdeStatusLaunchScope.STANDALONE }
        ?.takeIf { it.phaseId == SkeletonDefinition.PLAN.id }
        ?: return false
    val issueKey = plan.issueKey?.takeIf(String::isNotBlank) ?: return false
    val primary =
      goalRepositoryRootFromIdentity(plan.repositoryIdentity)
        ?.let(repositoryEnclosingRootPort::primaryRepositoryIdentity)
    return setOfNotNull(plan.repositoryIdentity, primary).any { identity ->
      unit.workList.goalParentCandidatePage(issueKey, identity, 0, 1).isNotEmpty()
    }
  }

  private fun canLaunch(
    completed: Boolean,
    terminalValidation: Boolean,
    live: Boolean,
  ): Boolean = (!completed || terminalValidation) && !live

  private fun isStandaloneValidation(phase: StandalonePhaseStatusRecord?): Boolean =
    phase?.let {
      it.launchScope == IdeStatusLaunchScope.STANDALONE && it.phaseId == SkeletonDefinition.VALIDATION.id
    } == true

  private fun isPausedOrBlocked(lifecycle: IdeStatusLifecycleState): Boolean =
    lifecycle == IdeStatusLifecycleState.PAUSED || lifecycle == IdeStatusLifecycleState.BLOCKED

  private fun launchAgent(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
  ): String? =
    phase?.launchAgentId
      ?: recordedLaunchAgent(item.goalStatus?.currentChildWorkflowId ?: item.snapshot.workflowId, unit)

  private fun launchLabel(
    canceled: Boolean,
    terminalValidation: Boolean,
    validationResume: Boolean,
    phase: StandalonePhaseStatusRecord?,
  ): String =
    if (validationResume) {
      "Resume"
    } else if (canceled || terminalValidation || phase?.let { it.workflowId == null } == true) {
      "Run again"
    } else {
      "Resume"
    }

  private fun workerOwnership(
    phase: StandalonePhaseStatusRecord?,
    workflowId: String?,
    unit: UnitOfWork,
  ) = phase?.workerOwnership() ?: workflowId?.let(unit.workflowStates::getFeatureTaskRuntimeWorkerOwnership)

  private fun arguments(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
    agent: String,
    qaDevice: QaNamedDevice?,
  ): List<String>? {
    if (phase != null) return phaseArguments(phase, agent, qaDevice)
    if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY) return null
    val issue = item.snapshot.issueKey ?: return null
    val workflow = item.snapshot.workflowId?.let { unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) }
    return if (workflow != null && ExecutionPlanArtifactView.definitionId(
        workflow.artifacts,
      ) == SkeletonDefinition.PLAN.id
    ) {
      listOf("phase", "plan", issue, "--agent", agent)
    } else {
      goalArguments(issue, agent, qaDevice)
    }
  }

  private fun goalArguments(
    issue: String,
    agent: String,
    qaDevice: QaNamedDevice? = null,
  ): List<String> = listOf("goal", issue, "--agent", agent) + qaDevice.launchArguments()

  private fun phaseArguments(
    phase: StandalonePhaseStatusRecord,
    agent: String,
    qaDevice: QaNamedDevice?,
  ): List<String>? {
    phase.monitorTarget?.let { target ->
      val selector = target.branchRef?.let { "$MONITOR_BRANCH_PREFIX$it" } ?: target.pullRequestUrl
      return listOf("phase", phase.phaseId, selector, "--agent", agent)
    }
    phase.launchArguments?.let { saved -> return savedArguments(phase, saved, agent, qaDevice) }
    return when {
      phase.launchScope == IdeStatusLaunchScope.GOAL -> phase.issueKey?.let { goalArguments(it, agent, qaDevice) }
      phase.workflowId != null && phase.phaseId == SkeletonDefinition.PLAN.id ->
        phase.issueKey?.let { listOf("phase", "plan", it, "--agent", agent) }
      phase.phaseId in
        setOf(
          SkeletonDefinition.MONITOR.id,
          SkeletonDefinition.PR.id,
          SkeletonDefinition.VALIDATION.id,
        )
      ->
        listOf("phase", phase.phaseId, "--agent", agent)
      else -> null
    }
  }

  private fun savedArguments(
    phase: StandalonePhaseStatusRecord,
    saved: List<String>,
    agent: String,
    qaDevice: QaNamedDevice?,
  ): List<String>? {
    val prefix =
      when (phase.launchScope) {
        IdeStatusLaunchScope.STANDALONE -> listOf("phase", phase.phaseId)
        IdeStatusLaunchScope.GOAL -> listOf("goal")
      }
    if (saved.take(prefix.size) != prefix) return null
    val currentArguments =
      if (phase.launchScope == IdeStatusLaunchScope.GOAL || phase.phaseId == SkeletonDefinition.QA.id) {
        saved.withoutQaDevice() + qaDevice.launchArguments()
      } else {
        saved
      }
    val agentFlag = currentArguments.indexOf("--agent")
    return if (agentFlag >= 0 && agentFlag + 1 < currentArguments.size) {
      currentArguments.toMutableList().apply { this[agentFlag + 1] = agent }
    } else {
      currentArguments + listOf("--agent", agent)
    }
  }

  private fun List<String>.withoutQaDevice(): List<String> {
    val arguments = this
    return buildList {
      var index = 0
      while (index < arguments.size) {
        if (arguments[index] == QA_DEVICE_FLAG) {
          index += if (index + 1 < arguments.size) 2 else 1
        } else {
          add(arguments[index])
          index++
        }
      }
    }
  }

  private fun launchKind(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    arguments: List<String>,
    agent: String,
    target: WorkRunTarget,
  ): WorkRunLaunchKind =
    if (phase == null) {
      WorkRunLaunchKind.RuntimeCommand(runtimeResumeArguments(arguments, target.repositoryRoot))
    } else {
      WorkRunLaunchKind.AgentHarness(agent, launchPrompt(item, phase, arguments, agent))
    }

  private fun runtimeResumeArguments(
    arguments: List<String>,
    repositoryRoot: Path,
  ): List<String> =
    if (arguments.first() == "goal") {
      arguments + listOf("--repo-root", repositoryRoot.toString(), "--no-live-output")
    } else {
      arguments
    }

  private fun launchPrompt(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord,
    arguments: List<String>,
    agent: String,
  ): String =
    buildString {
      appendLine("Use the installed skill-bill skill for this explicit operator request.")
      if (arguments.firstOrNull() == "phase") {
        appendLine("Invoke /skill-bill phase:${arguments[1]} with the recorded intake and options below.")
      } else {
        appendLine("Run the goal with /skill-bill and the recorded intake below; it finishes planning, then runs.")
      }
      appendLine("Request arguments as JSON: ${JsonCodec.valueToJsonString(arguments)}")
      appendLine("Selected agent: $agent")
      appendLine("Work identity: ${item.snapshot.execution?.invocationId ?: item.snapshot.workflowId}")
      appendLine("Repository: ${item.repositoryRoot}")
      val monitored = phase.monitorTarget
      val monitoredBranch = monitored?.branchRef
      if (monitored == null) {
        appendLine("Keep the recorded branch ${phase.branchCorrelation} and existing PR.")
      } else if (monitoredBranch != null) {
        appendLine(
          "Monitor exactly $MONITOR_BRANCH_PREFIX$monitoredBranch; the runtime prepares its own worktree for it.",
        )
        appendLine("Do not check out the branch $monitoredBranch in this repository.")
      } else {
        appendLine("Monitor exactly ${monitored.pullRequestUrl}; the runtime prepares its own worktree for it.")
        appendLine("Do not check out the pull request branch ${monitored.headRef} in this repository.")
      }
      append("Read durable state before continuing. Do not create another branch or PR for this work.")
    }

  private fun recordedAgentEnvironment(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
  ): Map<String, String> =
    phase?.agentEnvironment
      ?: item.snapshot.workflowId?.let(unit.standalonePhaseStatuses::latestWorkflowExecution)?.agentEnvironment
      ?: emptyMap()
}

internal fun goalStartRefusal(result: GoalPreflightResult): String? {
  val key = result.issueKey
  return when (result.verdict) {
    GoalPreflightVerdict.NEW_WORK ->
      result.gateBlock?.takeIf { it.expectedFirstRunnableSubtask == null }?.let(::blockedGateMessage)
    GoalPreflightVerdict.ALREADY_RUNNING -> "$key is already running."
    GoalPreflightVerdict.RESUMABLE, GoalPreflightVerdict.GOAL_CONTINUATION ->
      "$key already has a run. Use Resume or Run again."
    GoalPreflightVerdict.TERMINAL_ONLY ->
      "$key already finished. Purge its run history to start it again."
    else -> "Skill Bill can't start $key: preflight returned ${result.verdict}."
  }
}

private fun blockedGateMessage(gate: GoalPreflightGateBlock): String =
  "${gate.issueKey} is blocked at the goal gate: no subtask of ${gate.featureName} is runnable. " +
    "Subtasks: ${gate.subtasks.joinToString { "${it.id} ${it.status}" }}."
