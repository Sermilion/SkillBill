package skillbill.application.telemetry.lifecycle

import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.application.telemetry.settings.telemetrySettingsOrNull
import skillbill.contracts.JsonPayloadContract
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.GoalChildSubtaskIdentity
import skillbill.ports.telemetry.lifecycle.GoalStartedSupersession
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.attribution.normalizeStackLabel
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.time.Clock

private const val QUALITY_CHECK_ROUTED_SKILL = "bill-code-check"

private enum class ParentOwnershipState { MISSING_LEASE, CONFIRMED_NON_LIVE, PROTECTED }

private data class ParentOwnershipDecision(val state: ParentOwnershipState, val reason: String?)

class LifecycleTelemetryGoalEmission(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor? = null,
) : GoalLifecycleTelemetryEmitter {
  override fun goalStarted(request: GoalStartedRequest) {
    enabledStandaloneResult(settingsProvider, diagnostics, request.workflowId) { settings ->
      database.transaction { unitOfWork ->
        val record = request.toRecord()
        val repository = unitOfWork.lifecycleTelemetry
        val prepared = repository.prepareGoalStarted(record)
        val candidates =
          if (prepared.inserted) {
            val observedAt = clock.instant()
            val evidenceByWorkflow = prepared.ownershipEvidence.groupBy { it.workflowId }
            val contradictoryParents = evidenceByWorkflow.values
              .filter { evidence -> evidence.flatMap { listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId) }.distinct().size > 1 }
              .flatMap { evidence -> evidence.flatMap { listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId) } }
              .toSet()
            val ownershipByParent = prepared.ownershipEvidence
              .flatMap { evidence ->
                listOfNotNull(evidence.parentWorkflowId, evidence.latestSegmentParentWorkflowId)
                  .filter { it != record.parentWorkflowId }
                  .distinct().map { it to (evidence.parentWorkflowId to evidence.latestSegmentParentWorkflowId) }
              }
              .groupBy({ it.first }, { it.second })
              .mapValues { (parent, associations) ->
                val conflictingAssociation = associations.any { (explicit, latest) ->
                  explicit != null && latest != null && explicit != latest
                }
                val state = unitOfWork.goalRunnerControls.controlState(parent)
                val lease = state.executionLease
                val inspection = if (lease != null && !lease.expiresAt.isAfter(observedAt)) {
                  supervisor?.inspect(lease.asWorkerOwnership(parent))
                } else null
                when {
                  parent in contradictoryParents || conflictingAssociation ->
                    ParentOwnershipDecision(ParentOwnershipState.PROTECTED, "parent-ownership-ambiguous")
                  lease == null -> ParentOwnershipDecision(ParentOwnershipState.MISSING_LEASE, "lease-missing")
                  lease.expiresAt.isAfter(observedAt) ->
                    ParentOwnershipDecision(ParentOwnershipState.PROTECTED, "other-parent-lease-live")
                  inspection == FeatureTaskRuntimeProcessInspection.NotRunning ->
                    ParentOwnershipDecision(ParentOwnershipState.CONFIRMED_NON_LIVE, null)
                  supervisor == null ->
                    ParentOwnershipDecision(ParentOwnershipState.PROTECTED, "process-inspection-unavailable")
                  else -> ParentOwnershipDecision(ParentOwnershipState.PROTECTED, "other-parent-process-not-confirmed-dead")
                }
              }
            val anyProtectedOwner = ownershipByParent.any { it.value.state == ParentOwnershipState.PROTECTED }
            prepared.candidates.groupBy { it.workflowId }.map { (workflowId, joinedCandidates) ->
              val evidence = evidenceByWorkflow[workflowId].orEmpty()
              val associations = evidence.flatMap { listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId) }.distinct()
              val contradictory = associations.size > 1
              val candidate = joinedCandidates.first()
              val oldParent = associations.singleOrNull()
              val normalizedCandidate = candidate.copy(
                parentWorkflowId = evidence.firstNotNullOfOrNull { it.parentWorkflowId } ?: oldParent,
                latestSegmentParentWorkflowId = oldParent?.takeIf { evidence.none { row -> row.parentWorkflowId == oldParent } },
              )
              val sameParent = !record.parentWorkflowId.isNullOrBlank() && oldParent == record.parentWorkflowId
              val protectedAssociation = contradictory || associations.any { parent ->
                parent != record.parentWorkflowId && ownershipByParent[parent]?.state == ParentOwnershipState.PROTECTED
              }
              val missingLeaseConflict = associations.isEmpty() || oldParent?.let { ownershipByParent[it]?.state == ParentOwnershipState.MISSING_LEASE } == true
              val eligible = when {
                sameParent -> !protectedAssociation
                else -> !protectedAssociation && !(missingLeaseConflict && anyProtectedOwner)
              }
              val snapshot = oldParent?.let { unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) }
              val manifest = snapshot?.artifacts?.decompositionRuntime()
              val progressStatuses =
                if (manifest != null) {
                  manifest.subtasks.distinctBy { it.id }.map { it.status }
                } else if (oldParent != null) {
                  val children =
                    unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(oldParent)
                      .mapNotNull { childId ->
                        val childSnapshot =
                          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId) ?: return@mapNotNull null
                        val continuation = childSnapshot.artifacts.goalContinuationArtifact() ?: return@mapNotNull null
                        if (
                          continuation.parentWorkflowId != oldParent ||
                          !continuation.issueKey.equals(record.issueKey, ignoreCase = true)
                        ) {
                          return@mapNotNull null
                        }
                        val observedAt = childSnapshot.updatedAt ?: childSnapshot.startedAt ?: return@mapNotNull null
                        Triple(continuation.subtaskId, observedAt, childId)
                      }
                      .groupBy { it.first }
                      .mapNotNull { (_, attempts) -> attempts.maxByOrNull { it.second } }
                      .map { GoalChildSubtaskIdentity(it.first, it.third) }
                  repository.persistedChildSubtaskStatuses(record.issueKey, children).values.toList()
                } else {
                  emptyList()
                }
              if (manifest == null && progressStatuses.isEmpty()) {
                diagnostics.warning(
                  "seam=goal-run-supersession outcome=degraded issue=${record.issueKey} " +
                    "workflow=${normalizedCandidate.workflowId} reason=parent-progress-unavailable",
                  null,
                )
              }
              val counts = progressStatuses.mapNotNull(String::decompositionStatus)
              val supersession =
                GoalStartedSupersession(
                  candidate = normalizedCandidate,
                  eligible = eligible,
                  confirmedNonLiveOwner = eligible && !sameParent &&
                    oldParent?.let { ownershipByParent[it]?.state == ParentOwnershipState.CONFIRMED_NON_LIVE } == true,
                  complete = counts.count { it == DecompositionStatus.COMPLETE },
                  blocked = counts.count { it == DecompositionStatus.BLOCKED },
                  skipped = counts.count { it == DecompositionStatus.SKIPPED },
                  diagnosticReason = when {
                    !eligible && (associations.isEmpty() || contradictory) -> "parent-ownership-ambiguous"
                    !eligible && missingLeaseConflict && anyProtectedOwner -> "other-parent-ownership-conflict"
                    !eligible -> associations.firstNotNullOfOrNull { ownershipByParent[it]?.reason }
                    else -> null
                  },
                )
              if (supersession.diagnosticReason != null) {
                diagnostics.warning(
                  "seam=goal-run-supersession outcome=deferred issue=${record.issueKey} " +
                    "workflow=$workflowId reason=${supersession.diagnosticReason}",
                  null,
                )
              }
              supersession
            }
          } else {
            emptyList()
          }
        repository.reconcileGoalStarted(record, prepared.inserted, candidates, settings.level)
      }
    }
  }

  override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest) {
    enabledStandaloneResult(settingsProvider, diagnostics, request.workflowId) { settings ->
      val reconciledRequest = request.reconcileBlockedReason()
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalSubtaskFinished(reconciledRequest.toRecord(), settings.level)
      }
    }
  }

  override fun goalFinished(request: GoalFinishedRequest) {
    enabledStandaloneResult(settingsProvider, diagnostics, request.workflowId) { settings ->
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalFinished(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalIssueFinished(request: GoalIssueFinishedRequest) {
    enabledStandaloneResult(settingsProvider, diagnostics, request.parentWorkflowId) { settings ->
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalIssueFinished(request.toRecord(), settings.level)
      }
    }
  }
}

internal fun enabledStandaloneResult(
  settingsProvider: TelemetrySettingsProvider,
  diagnostics: RuntimeDiagnostics,
  sessionId: String,
  action: (TelemetrySettings) -> Unit,
): JsonPayloadContract {
  val settings = telemetrySettingsOrNull(settingsProvider, diagnostics)
  return if (settings?.enabled == true) {
    action(settings)
    lifecycleOkPayload(sessionId)
  } else {
    lifecycleSkippedPayload(sessionId)
  }
}

internal fun telemetryLevelOrAnonymous(
  settingsProvider: TelemetrySettingsProvider,
  diagnostics: RuntimeDiagnostics,
): String = telemetrySettingsOrNull(settingsProvider, diagnostics)?.level ?: "anonymous"

internal fun FeatureTaskRuntimeFinishedRequest.reconcileBlockedRuntimeFields(): FeatureTaskRuntimeFinishedRequest {
  if (completionStatus.workflowStatus() != WorkflowStatus.BLOCKED) {
    return this
  }
  return copy(
    lastIncompletePhase = lastIncompletePhase.takeIf(String::isNotBlank) ?: phaseOutcomes.firstIncompletePhase(),
    blockedReason =
      normalizedBlockedReason(
        reason = blockedReason,
        category = "runtime",
        fallback = "Feature-task-runtime blocked without a specific reason.",
      ),
  )
}

internal fun Map<String, String>.firstIncompletePhase(): String =
  entries.firstOrNull { it.value.workflowStepStatus() != WorkflowStepStatus.COMPLETED }?.key?.takeIf(String::isNotBlank)
    ?: "unknown"

internal fun QualityCheckStartedRequest.normalizedLabels(): QualityCheckStartedRequest {
  val stack = normalizeStackLabel(detectedStack)
  return copy(
    routedSkill = QUALITY_CHECK_ROUTED_SKILL,
    detectedStack = stack.stack,
    fallback = fallback || stack.fallback,
    fallbackReason = fallbackReason ?: stack.fallbackReason,
  )
}

internal fun QualityCheckFinishedRequest.normalizedLabels(): QualityCheckFinishedRequest {
  val stack = normalizeStackLabel(detectedStack)
  return copy(
    routedSkill = QUALITY_CHECK_ROUTED_SKILL,
    detectedStack = stack.stack,
    fallback = fallback || stack.fallback,
    fallbackReason = fallbackReason ?: stack.fallbackReason,
  )
}

internal fun GoalSubtaskFinishedRequest.reconcileBlockedReason(): GoalSubtaskFinishedRequest {
  if (status.workflowStatus() != WorkflowStatus.BLOCKED) {
    return this
  }
  return copy(
    blockedReason =
      normalizedBlockedReason(
        reason = blockedReason,
        category = "runtime",
        fallback = "Goal subtask $subtaskId is blocked.",
      ),
  )
}
