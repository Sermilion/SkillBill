package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.observation.workerOwnership
import skillbill.engine.goalrunner.status.executionLivenessBlocksIdleSafeRefresh
import skillbill.goalrunner.model.ExecutionLiveness
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
      val branch = item.snapshot.branchCorrelation
      if (!branch.isNullOrBlank() && branch != "HEAD" && branches.checkedOutBranch(target.repositoryRoot) != branch) {
        return@operation WorkControlResult.Rejected(WorkControlRejection.BRANCH_CHANGED)
      }
      val phase = unit.standalonePhaseStatuses.readByInvocation(target.id)
      val arguments =
        arguments(item, phase, unit, agent)
          ?: return@operation WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
      val result =
        launcher.start(
          WorkRunLaunchRequest(
            target.repositoryRoot,
            unit.dbPath,
            agent,
            launchPrompt(item, arguments, agent),
            workRunOwnershipKey(item, phase),
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
    phase.launchArguments?.let { saved ->
      return saved.takeIf { it.take(2) == listOf("phase", phase.phaseId) }?.let { arguments ->
        val agentFlag = arguments.indexOf("--agent")
        if (agentFlag >= 0 && agentFlag + 1 < arguments.size) {
          arguments.toMutableList().apply { this[agentFlag + 1] = agent }
        } else {
          arguments + listOf("--agent", agent)
        }
      }
    }
    return when {
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

  private fun launchPrompt(
    item: WorkDetailItem,
    arguments: List<String>,
    agent: String,
  ): String =
    buildString {
      appendLine("Use the installed skill-bill skill for this explicit operator request.")
      if (arguments.firstOrNull() == "phase") {
        appendLine("Invoke /skill-bill phase:${arguments[1]} with the recorded intake and options below.")
      } else {
        appendLine("Resume the existing goal with /skill-bill ${item.snapshot.issueKey}.")
      }
      appendLine("Request arguments as JSON: ${JsonCodec.valueToJsonString(arguments)}")
      appendLine("Selected agent: $agent")
      appendLine("Work identity: ${item.snapshot.execution?.invocationId ?: item.snapshot.workflowId}")
      appendLine("Repository: ${item.repositoryRoot}")
      appendLine("Keep the recorded branch ${item.snapshot.branchCorrelation ?: "HEAD"} and existing PR.")
      append("Read durable state before continuing. Do not create another branch or PR for this work.")
    }

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
