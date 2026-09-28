package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationReason
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.isConfirmedDead
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
import kotlin.time.Duration.Companion.milliseconds

@Inject
class FeatureTaskRuntimeCrashReconciler(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val executionPlanCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val executionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver,
) {
  fun reconcile(): FeatureTaskRuntimeCrashReconciliationResult {
    val now = clock.instant().toString()
    val candidates =
      runCatching {
        database.read { it.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates(now) }
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

  private fun reconcileCandidate(candidate: FeatureTaskRuntimeCrashReconciliationCandidate): String? =
    runCatching {
      if (!supervisor.inspect(candidate.ownership).isConfirmedDead()) {
        return@runCatching null
      }
      val reason = interruptionReason()
      val admission = database.read { unit ->
        val row = unit.workflowStates.getFeatureTaskWorkflowAsMode(
          candidate.ownership.workflowId,
          FeatureTaskWorkflowMode.RUNTIME,
        ) ?: return@read null
        val identity = unit.workflowStates.getFeatureTaskExecutionIdentity(candidate.ownership.workflowId)
          ?: throw IllegalStateException("crash candidate has no execution identity")
        FeatureTaskExecutionIdentityPolicy.validate(identity)
        if (
          identity.workflowId != row.workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
          identity.normalizedIssueKey != row.issueKey?.trim()?.uppercase()
        ) throw IllegalStateException("crash candidate route identity is incompatible")
        val artifact = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(row.toSnapshot().artifacts)
        val encoded = artifact?.let { value -> JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8) }
          ?: throw MissingFeatureTaskRuntimeExecutionPlanError()
        val recordedPlan = executionPlanCompatibility.requireSupportedComposition(encoded)
        val settings = recordedPlan.effectivePolicySettings
          ?: throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
        val repositoryPath = identity.repositoryIdentity.removePrefix(
          FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX,
        )
        val effectiveInputs = executionPlanResolver.resolveInputs(
          Path.of(repositoryPath),
          recordedPlan.qualityGateSelection,
          settings.validationDepth,
          settings.phaseTimeoutMillis?.milliseconds,
        )
        val admittedPlan = executionPlanCompatibility.requireSupportedRecovery(encoded, effectiveInputs)
        val expectedDefinition = SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD)
        if (admittedPlan.definitionId != expectedDefinition.id) {
          throw IllegalStateException("crash candidate execution identity is incompatible")
        }
        CrashCandidateAdmission(identity, encoded)
      } ?: return null

      val reconciled =
        database.transaction {
          val states = it.workflowStates
          val row = states.getFeatureTaskWorkflowAsMode(candidate.ownership.workflowId, FeatureTaskWorkflowMode.RUNTIME)
            ?: return@transaction false
          if (row.workflowStatus.workflowStatus() != WorkflowStatus.RUNNING) {
            return@transaction false
          }
          val currentOwnership = states.getFeatureTaskRuntimeWorkerOwnership(candidate.ownership.workflowId)
            ?: return@transaction false
          if (currentOwnership.ownerToken != candidate.ownership.ownerToken ||
            currentOwnership.generation != candidate.ownership.generation ||
            currentOwnership.leaseState != FeatureTaskRuntimeWorkerLeaseState.ACTIVE ||
            !currentOwnership.expiresAtInstant.isBefore(clock.instant())
          ) return@transaction false
          val identity = states.getFeatureTaskExecutionIdentity(candidate.ownership.workflowId)
            ?: throw IllegalStateException("crash candidate has no execution identity")
          if (identity != admission.identity) return@transaction false
          if (
            identity.workflowId != row.workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
            identity.normalizedIssueKey != row.issueKey?.trim()?.uppercase()
          ) return@transaction false
          val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(row.toSnapshot().artifacts)
          val encodedDescriptor = descriptor?.let { value -> JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8) }
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
      if (reconciled) reason.wireValue else null
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Crash reconciliation faulted on a candidate; the pass continues and the fault is counted.",
        error,
      )
      FAULT_REASON_CLASS
    }

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
