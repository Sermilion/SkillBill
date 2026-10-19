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
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.telemetry.lifecycle.GoalLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.model.GoalChildSubtaskIdentity
import skillbill.ports.telemetry.lifecycle.model.GoalStartedCandidate
import skillbill.ports.telemetry.lifecycle.model.GoalStartedOwnershipEvidence
import skillbill.ports.telemetry.lifecycle.model.GoalStartedPreparation
import skillbill.ports.telemetry.lifecycle.model.GoalStartedSupersession
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.attribution.normalizeStackLabel
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.time.Clock
import java.time.Instant

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
            supersessions(record, prepared, unitOfWork.goalRunnerControls, unitOfWork.workflowStates, repository)
          } else {
            emptyList()
          }
        repository.reconcileGoalStarted(record, prepared.inserted, candidates, settings.level)
      }
    }
  }

  private fun supersessions(
    record: GoalStartedRecord,
    prepared: GoalStartedPreparation,
    controls: GoalRunnerControlRepository,
    workflows: WorkflowStateRepository,
    repository: GoalLifecycleTelemetryRepository,
  ): List<GoalStartedSupersession> {
    val evidenceByWorkflow = prepared.ownershipEvidence.groupBy { it.workflowId }
    val ownershipByParent = parentOwnership(record, evidenceByWorkflow, controls)
    return prepared.candidates.groupBy { it.workflowId }.map { (workflowId, candidates) ->
      val evidence = evidenceByWorkflow[workflowId].orEmpty()
      val associations = evidence.parentAssociations()
      val oldParent = associations.singleOrNull()
      val candidate =
        candidates.first().copy(
          parentWorkflowId = evidence.firstNotNullOfOrNull { it.parentWorkflowId } ?: oldParent,
          latestSegmentParentWorkflowId =
            oldParent?.takeIf { evidence.none { row -> row.parentWorkflowId == oldParent } },
        )
      val statuses = progressStatuses(record.issueKey, candidate, oldParent, workflows, repository)
      supersession(record, candidate, associations, ownershipByParent, statuses)
    }
  }

  private fun parentOwnership(
    record: GoalStartedRecord,
    evidenceByWorkflow: Map<String, List<GoalStartedOwnershipEvidence>>,
    controls: GoalRunnerControlRepository,
  ): Map<String, ParentOwnershipDecision> {
    val contradictoryParents =
      evidenceByWorkflow.values
        .filter { it.parentAssociations().size > 1 }
        .flatMap { it.parentAssociations() }
        .toSet()
    val observedAt = clock.instant()
    return evidenceByWorkflow.values.flatten()
      .flatMap { listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId) }
      .filter { it != record.parentWorkflowId }
      .distinct()
      .associateWith { parent ->
        val observed = inspectParent(parent, controls, observedAt)
        if (parent in contradictoryParents) {
          ParentOwnershipDecision(ParentOwnershipState.PROTECTED, "parent-ownership-ambiguous")
        } else {
          observed
        }
      }
  }

  private fun inspectParent(
    parent: String,
    controls: GoalRunnerControlRepository,
    observedAt: Instant,
  ): ParentOwnershipDecision {
    val lease = controls.controlState(parent).executionLease
    val inspection =
      if (lease != null && !lease.expiresAt.isAfter(observedAt)) {
        supervisor?.inspect(lease.asWorkerOwnership(parent))
      } else {
        null
      }
    return when {
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

  private fun supersession(
    record: GoalStartedRecord,
    candidate: GoalStartedCandidate,
    associations: List<String>,
    ownership: Map<String, ParentOwnershipDecision>,
    statuses: List<String>,
  ): GoalStartedSupersession {
    val oldParent = associations.singleOrNull()
    val sameParent = !record.parentWorkflowId.isNullOrBlank() && oldParent == record.parentWorkflowId
    val eligible = supersessionEligible(record.parentWorkflowId, associations, ownership)
    val counts = statuses.mapNotNull(String::decompositionStatus)
    val reason = supersessionReason(eligible, associations, ownership)
    val result =
      GoalStartedSupersession(
        candidate = candidate,
        eligible = eligible,
        confirmedNonLiveOwner =
          eligible && !sameParent &&
            ownership[oldParent]?.state == ParentOwnershipState.CONFIRMED_NON_LIVE,
        complete = counts.count { it == DecompositionStatus.COMPLETE },
        blocked = counts.count { it == DecompositionStatus.BLOCKED },
        skipped = counts.count { it == DecompositionStatus.SKIPPED },
        diagnosticReason = reason,
      )
    if (reason != null) {
      diagnostics.warning(
        "seam=goal-run-supersession outcome=deferred issue=${record.issueKey} " +
          "workflow=${candidate.workflowId} reason=$reason",
        null,
      )
    }
    return result
  }

  private fun supersessionEligible(
    currentParent: String?,
    associations: List<String>,
    ownership: Map<String, ParentOwnershipDecision>,
  ): Boolean {
    val oldParent = associations.singleOrNull()
    val sameParent = !currentParent.isNullOrBlank() && oldParent == currentParent
    val protectedAssociation =
      associations.size > 1 ||
        associations.any { parent ->
          parent != currentParent && ownership[parent]?.state == ParentOwnershipState.PROTECTED
        }
    val missingLeaseConflict =
      associations.isEmpty() || ownership[oldParent]?.state == ParentOwnershipState.MISSING_LEASE
    val anyProtectedOwner = ownership.values.any { it.state == ParentOwnershipState.PROTECTED }
    return !protectedAssociation && (sameParent || !(missingLeaseConflict && anyProtectedOwner))
  }

  private fun supersessionReason(
    eligible: Boolean,
    associations: List<String>,
    ownership: Map<String, ParentOwnershipDecision>,
  ): String? {
    val oldParent = associations.singleOrNull()
    val missingLeaseConflict =
      associations.isEmpty() || ownership[oldParent]?.state == ParentOwnershipState.MISSING_LEASE
    val anyProtectedOwner = ownership.values.any { it.state == ParentOwnershipState.PROTECTED }
    return when {
      eligible -> null
      associations.isEmpty() || associations.size > 1 -> "parent-ownership-ambiguous"
      missingLeaseConflict && anyProtectedOwner -> "other-parent-ownership-conflict"
      else -> associations.firstNotNullOfOrNull { ownership[it]?.reason }
    }
  }

  private fun progressStatuses(
    issueKey: String,
    candidate: GoalStartedCandidate,
    oldParent: String?,
    workflows: WorkflowStateRepository,
    repository: GoalLifecycleTelemetryRepository,
  ): List<String> {
    val snapshot = oldParent?.let { workflows.get(WorkflowFamily.TASK_RUNTIME, it) }
    val manifest = snapshot?.artifacts?.decompositionRuntime()
    val statuses =
      when {
        manifest != null -> manifest.subtasks.distinctBy { it.id }.map { it.status }
        oldParent != null -> {
          val children =
            workflows.listGoalChildWorkflowIdsByParent(oldParent)
              .mapNotNull { childProgressIdentity(workflows, oldParent, issueKey, it) }
              .groupBy { it.first }
              .mapNotNull { (_, attempts) -> attempts.maxByOrNull { it.second } }
              .map { GoalChildSubtaskIdentity(it.first, it.third) }
          repository.persistedChildSubtaskStatuses(issueKey, children).values.toList()
        }
        else -> emptyList()
      }
    if (manifest == null && statuses.isEmpty()) {
      diagnostics.warning(
        "seam=goal-run-supersession outcome=degraded issue=$issueKey " +
          "workflow=${candidate.workflowId} reason=parent-progress-unavailable",
        null,
      )
    }
    return statuses
  }

  private fun childProgressIdentity(
    workflows: WorkflowStateRepository,
    parent: String,
    issueKey: String,
    childId: String,
  ): Triple<Int, Instant, String>? {
    val snapshot = workflows.get(WorkflowFamily.TASK_RUNTIME, childId) ?: return null
    val continuation = snapshot.artifacts.goalContinuationArtifact() ?: return null
    if (continuation.parentWorkflowId != parent || !continuation.issueKey.equals(issueKey, ignoreCase = true)) {
      return null
    }
    val observedAt = snapshot.updatedAt ?: snapshot.startedAt
    return observedAt?.let { Triple(continuation.subtaskId, it, childId) }
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

private fun List<GoalStartedOwnershipEvidence>.parentAssociations(): List<String> =
  flatMap { listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId) }.distinct()
