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
import java.time.Instant

interface StandalonePhaseStatusRepository : StandalonePhaseCancellationRepository {
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
   * Settles a non-terminal execution as blocked after the caller proved its lease expired and its owner process is
   * gone. Guarded on the exact revision, lease owner and generation of [record]: a changed revision is
   * STALE_REVISION, a changed or renewed lease is STALE_LEASE, and an already terminal execution is IDEMPOTENT.
   */
  fun releaseExpiredLease(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult
}
