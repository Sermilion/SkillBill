package skillbill.engine.featuretask.lifecycle.execution

import skillbill.ports.workflow.model.WorkflowStateRecord
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

private const val ADMISSION_WORKFLOW_LABEL_LIMIT = 128


@Inject
class FeatureTaskRuntimeExecutionAdmission(
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun admit(
    states: WorkflowStateRepository,
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity? = null,
    requestedReviewSelection: RuntimeReviewSelection? = null,
  ): AdmittedFeatureTaskRuntimeExecution =
    try {
      val identity =
        states.getFeatureTaskExecutionIdentity(workflowId)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing immutable execution identity")
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      val row =
        states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing workflow")
      if (WorkflowStatus.fromWire(row.workflowStatus)?.let { it in WorkflowStatus.terminalStatuses } == true) {
        throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "terminal workflow cannot be admitted")
      }
      requireMatchingIdentity(identity, row, workflowId, expectedIdentity)
      val checkedInputs = inputs.frozen()
      val artifacts = row.toSnapshot().artifacts
      val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts)
      val plan =
        compatibility.requireSupportedExecution(
          descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
          checkedInputs,
        )
      if (requestedReviewSelection != null && plan.reviewSelection != requestedReviewSelection) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      if (plan.definitionId != SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD).id) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      requireCompletedGateOutputEvidence(artifacts, plan)
      AdmittedFeatureTaskRuntimeExecution(identity, plan, checkedInputs, requireNotNull(descriptor))
    } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      warn(workflowId, error.reasonCode)
      throw error
    } catch (error: InvalidFeatureTaskExecutionIdentitySchemaError) {
      warn(workflowId, "invalid_route_identity")
      throw error
    } catch (error: UnsafeFeatureTaskRuntimeRegenerationError) {
      warn(workflowId, error.refusal.wireValue)
      throw error
    }

  private fun requireMatchingIdentity(
    identity: FeatureTaskExecutionIdentity,
    row: WorkflowStateRecord,
    workflowId: String,
    expected: FeatureTaskExecutionIdentity?,
  ) {
    val matchingRow = identity.workflowId == workflowId && identity.mode == FeatureTaskWorkflowMode.RUNTIME &&
      identity.normalizedIssueKey == row.issueKey?.trim()?.uppercase()
    val matchingExpected = expected == null || identity == expected
    if (!matchingRow || !matchingExpected) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "execution identity changed")
    }
  }

  fun requireCompatibleDescriptor(
    states: WorkflowStateRepository,
    workflowId: String,
    expected: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan =
    try {
      val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
      val descriptor =
        row?.toSnapshot()?.artifacts?.let {
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it)
        }
      compatibility.requireCompatibleExecution(
        descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
        expected,
      )
    } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      warn(workflowId, error.reasonCode)
      throw error
    }

  private fun warn(
    workflowId: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Execution admission refused workflow=${workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)} reason=$reason",
    )
  }
}

internal fun requireCompletedGateOutputEvidence(
  artifacts: Map<String, Any?>,
  plan: ResolvedPhaseExecutionPlan,
) {
  val gateSteps =
    plan.selectedStrategies
      .filter { it.slot == PhaseSlot.QUALITY_GATE }
      .flatMapTo(mutableSetOf()) { it.steps }
  if (gateSteps.isEmpty()) return
  val records = decodePhaseRecords(artifacts).values
  val completedGateRecords =
    records.filter { record ->
      record.phaseId in gateSteps && record.status == WorkflowStepStatus.COMPLETED
    }
  val missingCompletedOutput =
    completedGateRecords.any { record ->
      record.outputArtifact == null
    }
  val inconsistentCompletedOutput =
    completedGateRecords.any { record ->
      val outputStatus =
        record.outputArtifact
          ?.let(JsonCodec::parseObjectOrNull)
          ?.get(SharedPayloadKeys.STATUS)
          ?.let(JsonCodec::jsonElementToValue) as? String
      outputStatus?.let { WorkflowStepStatus.fromWire(it) }
        ?.let { it != WorkflowStepStatus.COMPLETED } == true
    }
  if (missingCompletedOutput || inconsistentCompletedOutput) {
    throw UnsafeFeatureTaskRuntimeRegenerationError(
      if (missingCompletedOutput) {
        FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE
      } else {
        FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS
      },
    )
  }
}
