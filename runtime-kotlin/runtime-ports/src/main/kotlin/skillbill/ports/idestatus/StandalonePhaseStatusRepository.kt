package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import java.time.Instant

interface StandalonePhaseStatusRepository :
  StandalonePhaseCancellationRepository,
  StandaloneValidationCheckRepository,
  StandaloneValidationBranchRepository,
  StandaloneOutputRepository {
  fun register(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord

  fun registerWorkflow(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution?

  fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution?

  fun readEligible(
    repositoryIdentity: String,
    branchCorrelation: String?,
    now: Instant,
    page: StandaloneEligiblePage = StandaloneEligiblePage(),
    monitorRepositoryIdentity: String = repositoryIdentity,
  ): List<StandalonePhaseStatusRecord>

  fun readPhaseExecutions(query: StandalonePhaseExecutionQuery): List<StandalonePhaseStatusRecord>

  fun readWorkflowExecutions(
    workflowId: String,
    startedThrough: Instant,
    offset: Int,
    limit: Int,
    after: StandalonePhaseExecutionAnchor? = null,
  ): List<IdeStatusWorkflowExecution>

  fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord?

  fun latestStandaloneExecution(workflowId: String): StandalonePhaseStatusRecord?

  fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult

  /**
   * Stores [checklist] for a live execution: `finished_at` is null and the lifecycle is active or paused. Any other
   * row returns FINISHED and stays unchanged. The status revision, `updated_at` and the lease columns never change,
   * so the compare-and-set [update] path is unaffected, and [update] never writes this value.
   */
  fun writeQaChecklist(
    invocationId: String,
    checklist: StandaloneQaChecklist,
  ): StandaloneQaChecklistWriteResult
}
