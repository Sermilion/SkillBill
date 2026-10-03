package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationReason
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.isConfirmedDead
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@Inject
class FeatureTaskRuntimeCrashReconciler(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val executionPlanCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val executionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver,
) {
  fun reconcile(workflowId: String? = null): FeatureTaskRuntimeCrashReconciliationResult {
    val now = clock.instant()
    val candidates =
      runCatching {
        database.read { unit ->
          if (workflowId == null) {
            unit.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates(now.toString())
          } else {
            listOfNotNull(recoveryCandidate(unit.workflowStates, workflowId, now))
          }
        }
      }.getOrElse { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Crash-reconciliation candidate scan failed; startup is unaffected.",
          error,
        )
        return FeatureTaskRuntimeCrashReconciliationResult.NONE
      }
    if (candidates.isEmpty()) return FeatureTaskRuntimeCrashReconciliationResult.NONE
    val reasonClassCounts = mutableMapOf<String, Int>()
    var reconciledCount = 0
    candidates.forEach { candidate ->
      reconcileCandidate(candidate)?.let { reasonClass ->
        reasonClassCounts.merge(reasonClass, 1, Int::plus)

        if (reasonClass != FAULT_REASON_CLASS) reconciledCount++
      }
    }
    return FeatureTaskRuntimeCrashReconciliationResult(reconciledCount, reasonClassCounts)
  }

  private fun recoveryCandidate(
    states: WorkflowStateRepository,
    workflowId: String,
    now: Instant,
  ): FeatureTaskRuntimeCrashReconciliationCandidate? {
    val ownership = states.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return null
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME) ?: return null
    if (row.workflowStatus.workflowStatus() !in setOf(WorkflowStatus.RUNNING, WorkflowStatus.BLOCKED) ||
      ownership.leaseState != FeatureTaskRuntimeWorkerLeaseState.ACTIVE ||
      !ownership.expiresAtInstant.isBefore(now)
    ) {
      return null
    }
    return FeatureTaskRuntimeCrashReconciliationCandidate(ownership, row.currentStepId, row.workflowStatus)
  }

  private fun reconcileCandidate(candidate: FeatureTaskRuntimeCrashReconciliationCandidate): String? =
    runCatching {
      if (!supervisor.inspect(candidate.ownership).isConfirmedDead()) {
        return@runCatching null
      }
      val reason = interruptionReason()
      val admission = readCandidateAdmission(candidate) ?: return@runCatching null
      val reconciled = reconcileAdmittedCandidate(candidate, admission, reason)
      if (reconciled) reason.wireValue else null
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Crash reconciliation faulted on a candidate; the pass continues and the fault is counted.",
        error,
      )
      FAULT_REASON_CLASS
    }

  private fun readCandidateAdmission(
    candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
  ): CrashCandidateAdmission? =
    database.read { unit ->
      val row =
        unit.workflowStates.getFeatureTaskWorkflowAsMode(
          candidate.ownership.workflowId,
          FeatureTaskWorkflowMode.RUNTIME,
        ) ?: return@read null
      val identity =
        unit.workflowStates.getFeatureTaskExecutionIdentity(candidate.ownership.workflowId)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
            candidate.ownership.workflowId,
            "crash candidate has no execution identity",
          )
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      if (
        identity.workflowId != row.workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
        identity.normalizedIssueKey != row.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
      ) {
        throw InvalidFeatureTaskExecutionIdentitySchemaError(
          candidate.ownership.workflowId,
          "crash candidate route identity is incompatible",
        )
      }
      val artifact =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
          row.toSnapshot().artifacts,
        )
      val encoded =
        artifact?.let { value -> JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8) }
          ?: throw MissingFeatureTaskRuntimeExecutionPlanError()
      val recordedPlan = executionPlanCompatibility.requireSupportedComposition(encoded)
      val repositoryPath =
        identity.repositoryIdentity.removePrefix(
          FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX,
        )
      val effectiveInputs = executionPlanResolver.resolveRecordedInputs(Path.of(repositoryPath), recordedPlan)
      val admittedPlan = executionPlanCompatibility.requireSupportedRecovery(encoded, effectiveInputs)
      val expectedDefinition = SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD)
      if (admittedPlan.definitionId != expectedDefinition.id) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      CrashCandidateAdmission(identity, encoded)
    }

  private fun reconcileAdmittedCandidate(
    candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
    admission: CrashCandidateAdmission,
    reason: FeatureTaskRuntimeCrashReconciliationReason,
  ): Boolean =
    database.transaction {
      val states = it.workflowStates
      val row =
        states.getFeatureTaskWorkflowAsMode(candidate.ownership.workflowId, FeatureTaskWorkflowMode.RUNTIME)
          ?: return@transaction false
      if (row.workflowStatus != candidate.workflowStatus ||
        row.workflowStatus.workflowStatus() !in setOf(WorkflowStatus.RUNNING, WorkflowStatus.BLOCKED)
      ) {
        return@transaction false
      }
      val currentOwnership =
        states.getFeatureTaskRuntimeWorkerOwnership(candidate.ownership.workflowId)
          ?: return@transaction false
      if (!ownsExpiredLease(currentOwnership, candidate)) {
        return@transaction false
      }
      val identity =
        states.getFeatureTaskExecutionIdentity(candidate.ownership.workflowId)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
            candidate.ownership.workflowId,
            "crash candidate has no execution identity",
          )
      if (identity != admission.identity) return@transaction false
      if (
        identity.workflowId != row.workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
        identity.normalizedIssueKey != row.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
      ) {
        return@transaction false
      }
      val descriptor =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
          row.toSnapshot().artifacts,
        )
      val encodedDescriptor =
        descriptor?.let { value ->
          JsonCodec.valueToJsonString(
            value,
          ).toByteArray(Charsets.UTF_8)
        }
      if (encodedDescriptor == null || !encodedDescriptor.contentEquals(admission.encodedDescriptor)) {
        return@transaction false
      }
      it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
        workflowId = candidate.ownership.workflowId,
        ownerToken = candidate.ownership.ownerToken,
        generation = candidate.ownership.generation,
        interruptionReason = "${reason.wireValue}: worker lease expired and process confirmed dead",
        nowInstant = clock.instant().toString(),
      )
    }

  private fun ownsExpiredLease(
    current: FeatureTaskRuntimeWorkerOwnership,
    candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
  ): Boolean =
    current.ownerToken == candidate.ownership.ownerToken &&
      current.generation == candidate.ownership.generation &&
      current.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE &&
      current.expiresAtInstant.isBefore(clock.instant())

  private companion object {
    const val FAULT_REASON_CLASS = "reconcile_fault"
  }

  private data class CrashCandidateAdmission(
    val identity: FeatureTaskExecutionIdentity,
    val encodedDescriptor: ByteArray,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as CrashCandidateAdmission

      if (identity != other.identity) return false
      if (!encodedDescriptor.contentEquals(other.encodedDescriptor)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = identity.hashCode()
      result = 31 * result + encodedDescriptor.contentHashCode()
      return result
    }
  }

  private fun interruptionReason(): FeatureTaskRuntimeCrashReconciliationReason =
    FeatureTaskRuntimeCrashReconciliationReason.LEASE_EXPIRED
}
