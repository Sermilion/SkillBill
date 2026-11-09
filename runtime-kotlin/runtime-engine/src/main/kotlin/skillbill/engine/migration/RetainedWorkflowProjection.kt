package skillbill.engine.migration

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.idestatus.model.IdeStatusRepositoryIdentityFamily
import skillbill.idestatus.model.IdeStatusRepositoryIdentityPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentitySource
import skillbill.ports.db.RetainedWorkflowReader
import skillbill.ports.db.model.RetainedWorkflowDiagnostic
import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusProblemDetails
import skillbill.ports.work.model.WorkItemKind

@Inject
public class RetainedWorkflowProjection(
  private val retained: RetainedWorkflowReader,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun details(diagnostic: RetainedWorkflowDiagnostic): IdeStatusProblemDetails =
    requireNotNull(
      IdeStatusProblemDetails.from(
        RetainedDiagnosticPayload(
          diagnostic.record.workflowId,
          diagnostic.failureCode.toString(),
          diagnostic.record.source.location,
        ),
      ),
    )

  internal fun diagnostic(
    workflowId: String,
    failure: SkillBillRuntimeException,
  ): RetainedWorkflowDiagnostic? = retained.read(workflowId).singleOrNull()?.let { diagnostic(it, failure) }

  internal fun diagnostic(
    record: RetainedWorkflowRecord,
    failure: SkillBillRuntimeException,
  ): RetainedWorkflowDiagnostic {
    val association =
      if (repositoryIdentity(record) == null) {
        " Repository association is unavailable; this record is shown without repository attribution."
      } else {
        ""
      }
    val guidance =
      "${failure.code}: ${failure.message}. Source retained at ${record.source.location}. " +
        "Saved source remains authoritative. Resolve the reported failure and retry readiness before mutation." +
        association
    diagnostics.warning(
      "seam=retained_workflow_projection workflow_id=${record.workflowId} " +
        "failure_code=${failure.code} value_used=original_record",
    )
    return RetainedWorkflowDiagnostic(record, failure.code, guidance)
  }

  internal fun repositoryIdentity(record: RetainedWorkflowRecord): String? {
    val family =
      when (record.workKind) {
        WorkItemKind.FEATURE_GOAL -> IdeStatusRepositoryIdentityFamily.GOAL
        WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusRepositoryIdentityFamily.TASK
        WorkItemKind.FEATURE_VERIFY -> IdeStatusRepositoryIdentityFamily.VERIFY
        null -> return record.repositoryIdentity
      }
    val facts =
      mapOf(
        IdeStatusRepositoryIdentitySource.CONTROL to record.controlRepositoryIdentity,
        IdeStatusRepositoryIdentitySource.EXECUTION_IDENTITY to record.executionRepositoryIdentity,
        IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION to record.workflowRegistrationRepositoryIdentity,
        IdeStatusRepositoryIdentitySource.STANDALONE_REGISTRATION to record.standaloneRegistrationRepositoryIdentity,
        IdeStatusRepositoryIdentitySource.GOAL_CHILDREN to record.goalChildrenRepositoryIdentity,
      )
    return IdeStatusRepositoryIdentityPolicy.resolve(family, facts)
  }
}

private data class RetainedDiagnosticPayload(
  val workflowId: String,
  val failureCode: String,
  val sourceLocation: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    mapOf(
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      RetainedDiagnosticPayloadKeys.FAILURE_CODE to failureCode,
      RetainedDiagnosticPayloadKeys.SOURCE_LOCATION to sourceLocation,
    )
}

private object RetainedDiagnosticPayloadKeys {
  const val FAILURE_CODE = "failure_code"
  const val SOURCE_LOCATION = "source_location"
}
