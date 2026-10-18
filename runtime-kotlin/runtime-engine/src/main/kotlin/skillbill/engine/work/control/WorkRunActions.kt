package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.goalrunner.status.executionLivenessBlocksIdleSafeRefresh
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.observation.workerOwnership
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.install.model.SupportedAgent
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.ExecutionPlanArtifactView
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Clock

@Inject
class WorkRunActions(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val goals: GoalRunnerStatusService,
  private val branches: CheckedOutBranchSource,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val launcher: WorkRunLauncher,
  private val clock: Clock,
) {
  fun launch(
    target: WorkRunTarget,
    agentId: String?,
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
        arguments(item, phase, unit, agent)
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
        unit.workflowStates.save(
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
    val agent = phase?.launchAgentId ?: recordedAgent(item, unit)
    val supported = arguments(item, phase, unit, agent ?: SupportedAgent.supportedIds.first()) != null
    val live =
      snapshot.lifecycleState == IdeStatusLifecycleState.ACTIVE || liveWorker ||
        item.goalStatus?.executionLiveness?.let { executionLivenessBlocksIdleSafeRefresh(it) } == true
    return WorkRunOptions(
      !completed && !canceled,
      !completed && !live && supported,
      launchLabel(canceled, phase),
      agent?.takeIf { it in SupportedAgent.supportedIds },
      SupportedAgent.supportedIds,
      when {
        completed -> null
        live -> "This run still has a running owner."
        !supported -> "This older entry has no complete launch details."
        else -> null
      },
    )
  }

  private fun launchLabel(
    canceled: Boolean,
    phase: StandalonePhaseStatusRecord?,
  ): String = if (canceled || phase != null && phase.workflowId == null) "Run again" else "Resume"

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
  ): List<String>? {
    if (phase != null) return phaseArguments(phase, agent)
    if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY) return null
    val issue = item.snapshot.issueKey ?: return null
    val workflow = item.snapshot.workflowId?.let { unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) }
    return if (workflow != null && ExecutionPlanArtifactView.definitionId(
        workflow.artifacts,
      ) == SkeletonDefinition.PLAN.id
    ) {
      listOf("phase", "plan", issue, "--agent", agent)
    } else {
      listOf("goal", issue, "--agent", agent)
    }
  }

  private fun phaseArguments(
    phase: StandalonePhaseStatusRecord,
    agent: String,
  ): List<String>? {
    phase.monitorTarget?.let { target ->
      return listOf("phase", phase.phaseId, target.pullRequestUrl, "--agent", agent)
    }
    phase.launchArguments?.let { saved -> return savedArguments(phase, saved, agent) }
    return when {
      phase.launchScope == IdeStatusLaunchScope.GOAL -> phase.issueKey?.let { listOf("goal", it, "--agent", agent) }
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
  ): List<String>? {
    val prefix =
      when (phase.launchScope) {
        IdeStatusLaunchScope.STANDALONE -> listOf("phase", phase.phaseId)
        IdeStatusLaunchScope.GOAL -> listOf("goal")
      }
    if (saved.take(prefix.size) != prefix) return null
    val agentFlag = saved.indexOf("--agent")
    return if (agentFlag >= 0 && agentFlag + 1 < saved.size) {
      saved.toMutableList().apply { this[agentFlag + 1] = agent }
    } else {
      saved + listOf("--agent", agent)
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
      WorkRunLaunchKind.RuntimeCommand(runtimeResumeArguments(arguments, target))
    } else {
      WorkRunLaunchKind.AgentHarness(agent, launchPrompt(item, phase, arguments, agent))
    }

  private fun runtimeResumeArguments(
    arguments: List<String>,
    target: WorkRunTarget,
  ): List<String> =
    if (arguments.first() == "goal") {
      arguments + listOf("--repo-root", target.repositoryRoot.toString(), "--no-live-output")
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
      if (monitored == null) {
        appendLine("Keep the recorded branch ${phase.branchCorrelation} and existing PR.")
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

  private fun recordedAgent(
    item: WorkDetailItem,
    unit: UnitOfWork,
  ): String? {
    val workflowId = item.goalStatus?.currentChildWorkflowId ?: item.snapshot.workflowId ?: return null
    val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return null
    val plan =
      JsonCodec.anyToStringAnyMap(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(workflow.artifacts),
      )
    val assignments = plan?.get(FeatureTaskRuntimeExecutionPlanKeys.STEP_LAUNCH_ASSIGNMENTS) as? List<*> ?: return null
    val records = assignments.mapNotNull(JsonCodec::anyToStringAnyMap)
    val current = records.firstOrNull { it[FeatureTaskRuntimeExecutionPlanKeys.STEP_ID] == workflow.currentStepId }
    return (current ?: records.firstOrNull())?.get(FeatureTaskRuntimeExecutionPlanKeys.AGENT_ID) as? String
  }
}
