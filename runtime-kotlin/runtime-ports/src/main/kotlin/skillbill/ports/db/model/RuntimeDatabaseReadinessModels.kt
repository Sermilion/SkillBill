package skillbill.ports.db.model

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.work.model.WorkItemKind

sealed interface RuntimeDatabaseReadinessResult {
  data class Ready(
    val existing: Boolean,
    val observation: DatabaseReadinessObservation? = null,
  ) : RuntimeDatabaseReadinessResult

  data class Refused(val failure: SkillBillRuntimeException) : RuntimeDatabaseReadinessResult
}

data class RetainedWorkflowSource(val location: String, val artifactsJson: String, val stepsJson: String)

data class RetainedWorkflowRecord(
  val workflowId: String,
  val workflowName: String,
  val issueKey: String?,
  val repositoryIdentity: String?,
  val currentStepId: String?,
  val updatedAt: String,
  val source: RetainedWorkflowSource,
  val workKind: WorkItemKind? = null,
  val executionRepositoryIdentity: String? = null,
  val workflowRegistrationRepositoryIdentity: String? = null,
  val standaloneRegistrationRepositoryIdentity: String? = null,
  val controlRepositoryIdentity: String? = null,
  val goalChildrenRepositoryIdentity: String? = null,
  val workflowBranchCorrelation: String? = null,
)

data class RetainedWorkflowDiagnostic(
  val record: RetainedWorkflowRecord,
  val failureCode: RuntimeFailureCode,
  val guidance: String,
)

data class DatabaseReadinessObservation(
  val existing: Boolean,
  val storeIdentity: String?,
  val selectedSchemaVersion: Int?,
  val requiresPreparation: Boolean,
)
