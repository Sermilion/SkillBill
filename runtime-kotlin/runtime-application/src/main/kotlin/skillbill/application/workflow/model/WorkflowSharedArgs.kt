package skillbill.application.workflow.model

import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.model.FeatureTaskRouteScope

internal data class BuildFeatureTaskExecutionIdentityArgs(
  val kind: WorkflowFamilyKind,
  val hasIdentityCoordinates: Boolean,
  val workflowId: String,
  val issueKey: String?,
  val repositoryIdentity: String?,
  val governedSpecPath: String?,
  val routeScope: FeatureTaskRouteScope,
)

data class WorkflowServiceOpenArgs(
  val kind: WorkflowFamilyKind,
  val sessionId: String = "",
  val currentStepId: String? = null,
  val issueKey: String? = null,
  val repositoryIdentity: String? = null,
  val governedSpecPath: String? = null,
  val routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  val executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
)

data class WorkflowServiceOpenFeatureTaskArgs(
  val kind: WorkflowFamilyKind,
  val sessionId: String = "",
  val currentStepId: String? = null,
  val issueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  val executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
)

data class RepairFeatureTaskRuntimeIdentityArgs(
  val workflowId: String,
  val issueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val reason: String,
)

internal data class FeatureTaskIdentityRepairArgs(
  val unitOfWork: UnitOfWork,
  val workflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val normalizedReason: String,
)
