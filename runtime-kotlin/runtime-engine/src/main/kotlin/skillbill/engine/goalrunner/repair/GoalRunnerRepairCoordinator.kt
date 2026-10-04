package skillbill.engine.goalrunner.repair

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.execution.core.asWorkerOwnership
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairStatus
import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.status.GoalRunnerStatusDurableReadTracker
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunnerRepairCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val childRepairStore: GoalRunnerChildRepairStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val repositoryRoot: RepositoryRoot,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val parentWedgeDiagnosis = GoalRunnerParentRepairWedgeDiagnosis(clock)

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult {
    val repoRoot = request.repoRoot ?: repositoryRoot.path
    val loaded =
      manifestStore.loadByIssueKey(request.issueKey, repoRoot)
        ?: return notFound(request.issueKey)
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
    )
    val parentDiagnosis =
      if (request.subtaskId == null) {
        parentWedgeDiagnosis.diagnose(loaded.controlState)
      } else {
        GoalRunnerParentWedgeDiagnosis()
      }
    val children =
      loaded.manifest.subtasks
        .filter { request.subtaskId == null || it.id == request.subtaskId }
        .filter { !it.workflowId.isNullOrBlank() }
    val diagnoses =
      children.map { subtask ->
        childRepairStore.diagnoseChildWedges(
          GoalRunnerChildWedgeDiagnosisRequest(
            workflowId = requireNotNull(subtask.workflowId),
            issueKey = request.issueKey,
            subtaskId = subtask.id,
            subtasks = loaded.manifest.subtasks,
            repoRoot = repoRoot,
          ),
        )
      }
    val childWedged = diagnoses.filterNot(GoalRunnerChildWedgeDiagnosis::isHealthy)
    val wedged = !parentDiagnosis.isHealthy || childWedged.isNotEmpty()
    return when {
      request.subtaskId != null && children.isEmpty() ->
        subtaskNotFound(request, loaded.parentWorkflowId, parentDiagnosis)
      !wedged ->
        healthyResult(request, loaded.parentWorkflowId, parentDiagnosis, diagnoses)
      childWedged.any { diagnosis -> diagnosis.wedges.any { it.wedgeClass.operatorRequired } } ->
        operatorRequiredResult(request, loaded.parentWorkflowId, parentDiagnosis, diagnoses)
      !request.apply ->
        inspectedResult(request, loaded.parentWorkflowId, parentDiagnosis, diagnoses)
      else ->
        applyRepairs(
          RepairApplicationContext(
            request = request,
            parentWorkflowId = loaded.parentWorkflowId,
            parentDiagnosis = parentDiagnosis,
            diagnoses = diagnoses,
            childWedged = childWedged,
            repoRoot = repoRoot,
            manifestSubtasks = loaded.manifest.subtasks,
          ),
        )
    }
  }

  private fun notFound(issueKey: String): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = issueKey,
      status = GoalRunnerRepairStatus.NOT_FOUND,
    )

  private fun subtaskNotFound(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    parentDiagnosis: GoalRunnerParentWedgeDiagnosis,
  ): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.NOT_FOUND,
      parentWorkflowId = parentWorkflowId,
      parentWedges = parentDiagnosis.wedges,
      parentPassedChecks = parentDiagnosis.passedChecks,
      refusalReason = "No child workflow is bound to subtask ${request.subtaskId}.",
    )

  private fun healthyResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    parentDiagnosis: GoalRunnerParentWedgeDiagnosis,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult {
    val status =
      if (request.apply && request.subtaskId != null) {
        GoalRunnerRepairStatus.NOT_WEDGED
      } else {
        GoalRunnerRepairStatus.HEALTHY
      }
    val healthy = diagnoses.firstOrNull { request.subtaskId == null || it.subtaskId == request.subtaskId }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = status,
      parentWorkflowId = parentWorkflowId,
      parentWedges = parentDiagnosis.wedges,
      parentPassedChecks = parentDiagnosis.passedChecks,
      diagnoses = diagnoses,
      refusalReason =
        if (status == GoalRunnerRepairStatus.NOT_WEDGED) {
          "Subtask ${request.subtaskId} is not wedged; passed checks: " +
            (healthy?.passedChecks?.joinToString(", ") ?: "none")
        } else {
          "Goal children passed every repair check; no durable write."
        },
    )
  }

  private fun operatorRequiredResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    parentDiagnosis: GoalRunnerParentWedgeDiagnosis,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = if (request.apply) GoalRunnerRepairStatus.OPERATOR_REQUIRED else GoalRunnerRepairStatus.INSPECTED,
      parentWorkflowId = parentWorkflowId,
      parentWedges = parentDiagnosis.wedges,
      parentPassedChecks = parentDiagnosis.passedChecks,
      diagnoses = diagnoses,
      refusalReason =
        "Saved phase-output evidence is incompatible with the installed runtime. " +
          "Keep the workflow and checkpoints intact, then retry after migration support is available.",
    )

  private fun inspectedResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    parentDiagnosis: GoalRunnerParentWedgeDiagnosis,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.INSPECTED,
      parentWorkflowId = parentWorkflowId,
      parentWedges = parentDiagnosis.wedges,
      parentPassedChecks = parentDiagnosis.passedChecks,
      diagnoses = diagnoses,
    )

  private fun applyRepairs(context: RepairApplicationContext): GoalRunnerRepairResult {
    val initialRefusal = liveLeaseRefusal(context)
    if (initialRefusal != null) return initialRefusal
    val applied = applyParentWedges(context.parentWorkflowId, context.parentDiagnosis.wedges).toMutableList()
    if (parentExecutionLeaseLive(context.parentWorkflowId)) {
      return liveLeaseRefused(
        context = context,
        workflowId = context.parentWorkflowId,
        scope = "Parent workflow",
        appliedRepairs = applied,
      )
    }
    val childFailure = applyChildWedges(context, applied)
    if (childFailure != null) return childFailure
    return GoalRunnerRepairResult(
      issueKey = context.request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = context.parentWorkflowId,
      parentWedges = context.parentDiagnosis.wedges,
      parentPassedChecks = context.parentDiagnosis.passedChecks,
      diagnoses = context.diagnoses,
      appliedRepairs = applied,
    )
  }

  private fun liveLeaseRefusal(context: RepairApplicationContext): GoalRunnerRepairResult? {
    if (parentExecutionLeaseLive(context.parentWorkflowId)) {
      return liveLeaseRefused(context, context.parentWorkflowId, "Parent workflow")
    }
    val diagnosis =
      context.childWedged.firstOrNull { child ->
        child.workflowId?.let(::childWorkerLeaseLive) == true
      }
    val workflowId = diagnosis?.workflowId ?: return null
    return liveLeaseRefused(context, workflowId, "Child workflow")
  }

  private fun applyChildWedges(
    context: RepairApplicationContext,
    applied: MutableList<GoalRunnerAppliedRepair>,
  ): GoalRunnerRepairResult? {
    for (diagnosis in context.childWedged) {
      val workflowId = diagnosis.workflowId
      if (workflowId != null) {
        val repairResult =
          childRepairStore.applyChildWedgeRepairs(
            GoalRunnerChildWedgeRepairRequest(
              workflowId = workflowId,
              issueKey = context.request.issueKey,
              subtaskId = diagnosis.subtaskId,
              wedgeClasses = diagnosis.wedges.map { it.wedgeClass },
              repoRoot = context.repoRoot,
              wedgeFindings = diagnosis.wedges,
            ),
          )
        applied += repairResult.repairs
        val unrecoverableReviewWedge =
          diagnosis.wedges.firstOrNull { finding ->
            finding.wedgeClass in
              setOf(
                GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE,
                GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
              ) && repairResult.repairs.none { repair -> repair.wedgeClass == finding.wedgeClass }
          }
        if (unrecoverableReviewWedge != null) {
          val subtaskStatus =
            context.manifestSubtasks.firstOrNull { it.id == diagnosis.subtaskId }
              ?.status
              ?.decompositionStatus()
          val childProgress = outcomeStore.progress(workflowId)
          return GoalRunnerRepairResult(
            issueKey = context.request.issueKey,
            status = GoalRunnerRepairStatus.OPERATOR_REQUIRED,
            parentWorkflowId = context.parentWorkflowId,
            parentWedges = context.parentDiagnosis.wedges,
            parentPassedChecks = context.parentDiagnosis.passedChecks,
            diagnoses = context.diagnoses,
            appliedRepairs = applied,
            refusalReason =
              "Review remediation for subtask ${diagnosis.subtaskId} could not be recovered. " +
                "Recover with: '${recommendedDurableChildRecoveryCommand(
                  context.request.issueKey,
                  diagnosis.subtaskId,
                  subtaskStatus,
                  childProgress,
                )}'.",
          )
        }
      }
    }
    return null
  }

  private fun liveLeaseRefused(
    context: RepairApplicationContext,
    workflowId: String,
    scope: String,
    appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  ): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = context.request.issueKey,
      status = GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
      parentWorkflowId = context.parentWorkflowId,
      parentWedges = context.parentDiagnosis.wedges,
      parentPassedChecks = context.parentDiagnosis.passedChecks,
      diagnoses = context.diagnoses,
      appliedRepairs = appliedRepairs,
      liveLeaseWorkflowId = workflowId,
      refusalReason = "$scope '$workflowId' holds a live worker lease; a running worker owns that state.",
    )

  private fun applyParentWedges(
    parentWorkflowId: String,
    wedges: List<GoalRunnerWedgeFinding>,
  ): List<GoalRunnerAppliedRepair> =
    wedges.mapNotNull { wedge ->
      when (wedge.wedgeClass) {
        GoalRunnerWedgeClass.STALE_EXECUTION_LEASE -> {
          val lease = manifestStore.executionLease(parentWorkflowId)
          if (lease != null &&
            manifestStore.releaseExecutionLeaseIfExpired(
              parentWorkflowId,
              lease.ownerToken,
              lease.generation,
              clock.instant().toString(),
            )
          ) {
            parentAppliedRepair(parentWorkflowId, wedge, wedge.currentValue, null)
          } else {
            null
          }
        }
        GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE -> {
          if (parentExecutionLeaseLive(parentWorkflowId)) {
            null
          } else {
            val before = manifestStore.controlState(parentWorkflowId)
            if (before.pauseReason != GOAL_PAUSE_REASON_RUNNER_INTERRUPTED) {
              null
            } else {
              val after = manifestStore.clearRunnerInterruptedPause(parentWorkflowId)
              if (after == before) {
                null
              } else {
                parentAppliedRepair(
                  parentWorkflowId,
                  wedge,
                  GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
                  null,
                )
              }
            }
          }
        }
        else -> null
      }
    }

  private fun parentAppliedRepair(
    parentWorkflowId: String,
    wedge: GoalRunnerWedgeFinding,
    priorValue: String?,
    newValue: String?,
  ): GoalRunnerAppliedRepair =
    GoalRunnerAppliedRepair(
      subtaskId = 0,
      workflowId = parentWorkflowId,
      wedgeClass = wedge.wedgeClass,
      field = wedge.field,
      priorValue = priorValue,
      newValue = newValue,
    )

  private fun parentExecutionLeaseLive(parentWorkflowId: String): Boolean {
    val lease = manifestStore.executionLease(parentWorkflowId) ?: return false
    return workerLeaseLive(lease.asWorkerOwnership(parentWorkflowId))
  }

  private fun childWorkerLeaseLive(workflowId: String): Boolean {
    val tracker = GoalRunnerStatusDurableReadTracker(diagnostics)
    val ownership =
      runCatching { phaseQuery.workerOwnership(workflowId) }.getOrElse { error ->
        tracker.recordDegradedRead(
          seam = "goal-repair.child_worker_ownership",
          expected = "ownership_row",
          used = "degraded",
          error = error,
        )
        return false
      } ?: return false
    return workerLeaseLive(ownership)
  }

  private fun workerLeaseLive(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    if (ownership.expiresAtInstant.isAfter(clock.instant())) return true
    return workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive
  }
}

private data class RepairApplicationContext(
  val request: GoalRunnerRepairRequest,
  val parentWorkflowId: String,
  val parentDiagnosis: GoalRunnerParentWedgeDiagnosis,
  val diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  val childWedged: List<GoalRunnerChildWedgeDiagnosis>,
  val repoRoot: Path,
  val manifestSubtasks: List<DecompositionSubtask>,
)
