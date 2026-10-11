package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import java.time.Instant

interface StandalonePhaseStatusRepository {
  fun register(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord

  fun registerWorkflow(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution?

  fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution?

  fun readEligible(
    repositoryIdentity: String,
    branchCorrelation: String,
    now: Instant,
  ): List<StandalonePhaseStatusRecord>

  fun readBoardCandidates(
    finishedFrom: Instant,
    finishedThrough: Instant,
  ): List<StandalonePhaseStatusRecord>

  fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord?

  fun latestStandaloneExecution(workflowId: String): StandalonePhaseStatusRecord?

  fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult
}
