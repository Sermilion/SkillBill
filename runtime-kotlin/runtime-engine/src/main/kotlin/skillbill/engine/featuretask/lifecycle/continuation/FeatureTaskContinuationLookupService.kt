package skillbill.engine.featuretask.lifecycle.continuation

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.requireCompletedGateOutputEvidence
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLiveness
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupQuery
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.shellcontent.LegacyProseWorkflowError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private const val ADMISSION_WORKFLOW_LABEL_LIMIT = 128

@Inject
class FeatureTaskContinuationLookupService(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val executionCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun claim(
    candidate: FeatureTaskContinuationCandidate,
    effectiveInputs: EffectiveGatePolicyInputs,
    expectedOwnership: FeatureTaskRuntimeWorkerOwnership? = null,
  ): ResolvedPhaseExecutionPlan? =
    try {
      database.transaction { unitOfWork ->
        val states = unitOfWork.workflowStates
        val row =
          states.getFeatureTaskWorkflowAsMode(candidate.workflowId, candidate.mode)
            ?: return@transaction null
        if (!row.matchesContinuationCandidate(candidate)) return@transaction null
        val ownership = states.getFeatureTaskRuntimeWorkerOwnership(candidate.workflowId)
        if (ownership != expectedOwnership) return@transaction null
        if (ownership != null && ownership.leaseState != FeatureTaskRuntimeWorkerLeaseState.ACTIVE) {
          return@transaction null
        }
        val identity =
          states.getFeatureTaskExecutionIdentity(candidate.workflowId)
            ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
              candidate.workflowId,
              "missing immutable execution identity",
            )
        FeatureTaskExecutionIdentityPolicy.validate(identity)
        requireCandidateIdentity(identity, candidate, row)
        val snapshot = row.toSnapshot()
        workflowSnapshotValidator.validate(snapshot, snapshot.workflowName)
        val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(snapshot.artifacts)
        val plan =
          executionCompatibility.requireSupportedExecution(
            descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
            effectiveInputs,
            onMapping = {
              RuntimeDiagnosticsBestEffortWarning.record(
                diagnostics,
                "Execution plan checked semantic mapping " +
                  "workflow=${candidate.workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)}; " +
                  "original descriptor and evidence retained",
              )
            },
          )
        if (plan.definitionId !=
          SkeletonDefinition.forRun(
            identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD,
          ).id
        ) {
          throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
        }
        requireCompletedGateOutputEvidence(snapshot.artifacts, plan)
        if (states.claimFeatureTaskContinuation(candidate.workflowId, candidate.updatedAt)) plan else null
      }
    } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Execution admission refused workflow=${candidate.workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)}" +
          " reason=${error.reasonCode}",
      )
      throw error
    } catch (error: InvalidFeatureTaskExecutionIdentitySchemaError) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Execution admission refused workflow=${candidate.workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)}" +
          " reason=invalid_route_identity",
      )
      throw error
    } catch (error: UnsafeFeatureTaskRuntimeRegenerationError) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Execution admission refused workflow=${candidate.workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)}" +
          " reason=${error.refusal.wireValue}",
      )
      throw error
    }

  private fun WorkflowStateRecord.matchesContinuationCandidate(candidate: FeatureTaskContinuationCandidate): Boolean =
    updatedAt == candidate.updatedAt && workflowStatus == candidate.status && currentStepId == candidate.currentStep &&
      workflowStatus.workflowStatus() !in TERMINAL_STATUSES + WorkflowStatus.RUNNING

  private fun requireCandidateIdentity(
    identity: FeatureTaskExecutionIdentity,
    candidate: FeatureTaskContinuationCandidate,
    row: WorkflowStateRecord,
  ) {
    val unchangedRoute =
      identity == candidate.executionIdentity && identity.governedSpecPath == candidate.governedSpecPath
    val matchingRow =
      identity.workflowId == row.workflowId && identity.mode == candidate.mode &&
        identity.normalizedIssueKey == row.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    if (!unchangedRoute || !matchingRow) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(candidate.workflowId, "identity changed before claim")
    }
  }

  fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
  ): FeatureTaskContinuationLookupResult =
    lookup(
      FeatureTaskContinuationLookupQuery(
        issueKey = issueKey,
        repositoryIdentity = repositoryIdentity,
        workflowId = workflowId,
        routeScope = FeatureTaskRouteScope.STANDALONE,
      ),
    )

  fun lookupGoalChild(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String,
  ): FeatureTaskContinuationLookupResult =
    lookup(
      FeatureTaskContinuationLookupQuery(
        issueKey = issueKey,
        repositoryIdentity = repositoryIdentity,
        workflowId = workflowId,
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      ),
    )

  fun lookupIfPresent(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
  ): FeatureTaskContinuationLookupResult =
    lookup(
      FeatureTaskContinuationLookupQuery(
        issueKey = issueKey,
        repositoryIdentity = repositoryIdentity,
        workflowId = workflowId,
        routeScope = FeatureTaskRouteScope.STANDALONE,
        readIfPresent = true,
      ),
    )

  private fun lookup(query: FeatureTaskContinuationLookupQuery): FeatureTaskContinuationLookupResult {
    val lookup = { unitOfWork: UnitOfWork ->
      executeFeatureTaskContinuationLookup(
        query = query,
        unitOfWork = unitOfWork,
        project = ::project,
        classify = ::classify,
      )
    }
    return if (query.readIfPresent) {
      database.readIfPresent(lookup) ?: FeatureTaskContinuationLookupResult.NoMatch
    } else {
      database.read(lookup)
    }
  }

  private fun project(
    candidate: FeatureTaskWorkflowCandidate,
    ownership: FeatureTaskRuntimeWorkerOwnership?,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskContinuationCandidate {
    val identity =
      requireNotNull(candidate.identity) {
        invalidIdentity(candidate, "missing immutable execution identity")
      }
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity.routeScope != routeScope) {
      invalidIdentity(
        candidate,
        "${routeScope.wireValue} lookup returned route_scope '${identity.routeScope.wireValue}'",
      )
    }
    if (identityConflictsWithWorkflow(identity, candidate)) {
      invalidIdentity(candidate, "immutable identity conflicts with workflow snapshot")
    }

    if (identity.mode == FeatureTaskWorkflowMode.PROSE) {
      throw LegacyProseWorkflowError(candidate.workflow.workflowId, candidate.workflow.issueKey)
    }
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    workflowSnapshotValidator.validate(candidate.workflow.toSnapshot(), definition.workflowName)
    val status = candidate.workflow.workflowStatus
    val typedStatus = status.workflowStatus()
    return FeatureTaskContinuationCandidate(
      executionIdentity = identity,
      workflowId = candidate.workflow.workflowId,
      mode = identity.mode,
      status = status,
      currentStep = candidate.workflow.currentStepId,
      governedSpecPath = identity.governedSpecPath,
      updatedAt = candidate.workflow.updatedAt,
      liveness =
        if (typedStatus == WorkflowStatus.RUNNING) {
          ownership?.let {
            FeatureTaskContinuationLiveness(
              classification = "worker_ownership_recorded",
              lastEvidenceAt = it.heartbeatAt,
              evidence =
                "Runtime worker ownership is fenced at generation ${it.generation}; exact process liveness " +
                  "must be verified before takeover.",
            )
          } ?: FeatureTaskContinuationLiveness(
            classification = "ownership_unavailable",
            lastEvidenceAt = candidate.workflow.updatedAt,
            evidence = "The workflow is running without verifiable worker ownership; operator repair is required.",
          )
        } else {
          null
        },
      summary =
        when {
          typedStatus == WorkflowStatus.RUNNING -> "Workflow is already running; inspect liveness before recovery."
          typedStatus in TERMINAL_STATUSES -> "Workflow is terminal with status '$status'."
          else -> "Resume from '${candidate.workflow.currentStepId}' using durable workflow artifacts."
        },
    )
  }

  private fun identityConflictsWithWorkflow(
    identity: FeatureTaskExecutionIdentity,
    candidate: FeatureTaskWorkflowCandidate,
  ): Boolean {
    val workflow = candidate.workflow
    val modeConflicts = workflow.mode?.let { it != identity.mode } ?: false
    return identity.workflowId != workflow.workflowId ||
      modeConflicts ||
      identity.normalizedIssueKey != workflow.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
  }

  private fun invalidIdentity(
    candidate: FeatureTaskWorkflowCandidate,
    reason: String,
  ): Nothing = throw InvalidFeatureTaskExecutionIdentitySchemaError(candidate.workflow.workflowId, reason)

  private fun classify(candidates: List<FeatureTaskContinuationCandidate>): FeatureTaskContinuationLookupResult {
    if (candidates.isEmpty()) return FeatureTaskContinuationLookupResult.NoMatch
    val eligible = candidates.filterNot { it.status.workflowStatus() in TERMINAL_STATUSES }
    if (eligible.size > 1) return FeatureTaskContinuationLookupResult.Ambiguous(candidates)
    if (eligible.size == 1) {
      val candidate = eligible.single()
      return if (candidate.status.workflowStatus() == WorkflowStatus.RUNNING) {
        FeatureTaskContinuationLookupResult.AlreadyRunning(candidate)
      } else {
        FeatureTaskContinuationLookupResult.Resumable(candidate)
      }
    }
    return FeatureTaskContinuationLookupResult.TerminalOnly(candidates)
  }

  private companion object {
    val TERMINAL_STATUSES = setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED)
  }
}
