package skillbill.ports.persistence

import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.UnavailableRejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.UnavailableRejectedOutputDiagnosticRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.UnavailableFeatureTaskPhaseSettlementRepository
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.UnavailableUnaddressedFindingsRepository
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.EmptyAgentActivityStampRepository
import skillbill.ports.idestatus.EmptyWorktreeEditJournalRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.UnavailableOperationProposalRepository
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import java.time.Instant

abstract class UnitOfWorkDefaults : UnitOfWork {
  open override val unaddressedFindings: UnaddressedFindingsRepository = UnavailableUnaddressedFindingsRepository
  open override val agentActivityStamps: AgentActivityStampRepository = EmptyAgentActivityStampRepository
  open override val worktreeEditJournal: WorktreeEditJournalRepository = EmptyWorktreeEditJournalRepository
  open override val standalonePhaseStatuses: StandalonePhaseStatusRepository =
    object : StandalonePhaseStatusRepository {
      override fun register(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord =
        error("Standalone phase status repository is unavailable in this fixture.")

      override fun registerWorkflow(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution? = null

      override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? = null

      override fun readEligible(
        repositoryIdentity: String,
        branchCorrelation: String,
        now: Instant,
      ): List<StandalonePhaseStatusRecord> = emptyList()

      override fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult =
        StandalonePhaseStatusUpdateResult.MISSING

      override fun reconcileExpiredLeases(now: Instant): Int = 0
    }
  open override val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository =
    UnavailableRejectedOutputDiagnosticRepository
  open override val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions =
    UnavailableRejectedOutputDiagnosticPermissions
  open override val featureTaskPhaseSettlements: FeatureTaskPhaseSettlementRepository =
    UnavailableFeatureTaskPhaseSettlementRepository
  open override val operationProposals: OperationProposalRepository = UnavailableOperationProposalRepository

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts = GoalPurgeTableCounts()

  override fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts = GoalPurgeTableCounts()
}
