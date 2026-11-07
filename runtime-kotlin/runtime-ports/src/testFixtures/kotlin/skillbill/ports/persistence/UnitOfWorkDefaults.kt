package skillbill.ports.persistence

import skillbill.approval.model.ApprovalGrantFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.ports.approval.ApprovalStore
import skillbill.ports.approval.model.ApprovalConsumerLease
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease
import skillbill.ports.approval.model.ApprovalRegistration
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.approval.model.ApprovalStoredRequest
import skillbill.ports.blockincident.BlockIncidentRecoveryRepository
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.InMemoryBlockIncidentRecoveryRepository
import skillbill.ports.blockincident.InMemoryBlockIncidentRepository
import skillbill.ports.blockincident.InMemoryProductReportRepository
import skillbill.ports.blockincident.ProductReportRepository
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.UnavailableRejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.UnavailableRejectedOutputDiagnosticRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.InMemoryFeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.UnavailableFeatureTaskPhaseSettlementRepository
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.UnavailableUnaddressedFindingsRepository
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.EmptyAgentActivityStampRepository
import skillbill.ports.idestatus.EmptyWorktreeEditJournalRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import skillbill.ports.idestatus.model.WorkValidationBranchRun
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.UnavailableOperationCardRepository
import skillbill.ports.operation.UnavailableOperationProposalRepository
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.review.diffcomment.DiffCommentStore
import skillbill.ports.review.diffcomment.UnavailableDiffCommentStore
import java.time.Instant

abstract class UnitOfWorkDefaults : UnitOfWork {
  open override val databaseFileObservation: DatabaseFileObservation? = null
  open override val workBoardReadModel: WorkBoardReadModelRepository = EmptyBoardReadModel()
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
        branchCorrelation: String?,
        now: Instant,
        page: StandaloneEligiblePage,
        monitorRepositoryIdentity: String,
      ): List<StandalonePhaseStatusRecord> = emptyList()

      override fun readPhaseExecutions(query: StandalonePhaseExecutionQuery): List<StandalonePhaseStatusRecord> =
        emptyList()

      override fun readWorkflowExecutions(
        workflowId: String,
        startedThrough: Instant,
        offset: Int,
        limit: Int,
        after: StandalonePhaseExecutionAnchor?,
      ): List<IdeStatusWorkflowExecution> = emptyList()

      override fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord? = null

      override fun readValidationBranchRuns(invocationId: String): List<WorkValidationBranchRun> = emptyList()

      override fun latestStandaloneExecution(workflowId: String): StandalonePhaseStatusRecord? = null

      override fun requestCancellation(
        record: StandalonePhaseStatusRecord,
        at: Instant,
      ): StandalonePhaseStatusUpdateResult = StandalonePhaseStatusUpdateResult.MISSING

      override fun cancel(
        record: StandalonePhaseStatusRecord,
        at: Instant,
      ): StandalonePhaseStatusUpdateResult = StandalonePhaseStatusUpdateResult.MISSING

      override fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult =
        StandalonePhaseStatusUpdateResult.MISSING

      override fun releaseExpiredLease(
        record: StandalonePhaseStatusRecord,
        at: Instant,
      ): StandalonePhaseStatusUpdateResult = StandalonePhaseStatusUpdateResult.MISSING

      override fun writeQaChecklist(
        invocationId: String,
        checklist: StandaloneQaChecklist,
      ): StandaloneQaChecklistWriteResult = StandaloneQaChecklistWriteResult.MISSING

      override fun appendValidationCheckEvent(record: ValidationCheckEventRecord) = Unit

      override fun readValidationCheckEvents(invocationId: String): List<ValidationCheckEventRecord> = emptyList()

      override fun writeCurrentActivity(
        invocationId: String,
        activity: String,
      ): StandaloneNarrowWriteResult = StandaloneNarrowWriteResult.MISSING

      override fun writeLiveChecks(
        invocationId: String,
        mode: LiveChecksWriteMode,
      ): StandaloneNarrowWriteResult = StandaloneNarrowWriteResult.MISSING

      override fun writeOutputSnapshot(
        executionId: String,
        snapshot: StandalonePhaseOutputSnapshot,
      ) = Unit

      override fun readOutputLines(
        executionId: String,
        beforeSequence: Long?,
        limit: Int,
      ): List<StandalonePhaseOutputLine> = emptyList()

      override fun readOutput(executionId: String): StandalonePhaseOutputSnapshot? = null
    }
  open override val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository =
    UnavailableRejectedOutputDiagnosticRepository
  open override val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions =
    UnavailableRejectedOutputDiagnosticPermissions
  open override val featureTaskPhaseSettlements: FeatureTaskPhaseSettlementRepository =
    UnavailableFeatureTaskPhaseSettlementRepository
  open override val featureTaskQaOutcomes: FeatureTaskQaOutcomeRepository = InMemoryFeatureTaskQaOutcomeRepository()
  open override val operationProposals: OperationProposalRepository = UnavailableOperationProposalRepository
  open override val operationCards: OperationCardRepository = UnavailableOperationCardRepository
  open override val approvals: ApprovalStore =
    object : ApprovalStore {
      override fun register(
        request: ApprovalRequestRecord,
        facts: ApprovalGrantFacts,
      ): ApprovalRegistration = ApprovalRegistration.IdentityConflict

      override fun find(identity: ApprovalRequestIdentity): ApprovalStoredRequest? = null

      override fun settleAndClaim(
        identity: ApprovalRequestIdentity,
        settled: ApprovalRequestRecord,
        rule: ApprovalRuleRecord?,
        claimedAt: String,
      ): Boolean = false

      override fun settleWithoutDelivery(
        identity: ApprovalRequestIdentity,
        settled: ApprovalRequestRecord,
      ): Boolean = false

      override fun recordProviderDisposition(
        identity: ApprovalRequestIdentity,
        disposition: ApprovalProviderDisposition,
      ): Boolean = false

      override fun finishDelivery(
        identity: ApprovalRequestIdentity,
        state: ApprovalDeliveryState,
      ): Boolean = false

      override fun activeRules(repositoryIdentity: String): List<ApprovalRuleRecord> = emptyList()

      override fun revokeRule(
        ruleId: String,
        repositoryIdentity: String,
        revokedAt: String,
      ): Boolean = false

      override fun pending(
        repositoryIdentity: String,
        owner: ApprovalOwnerKey?,
      ): List<ApprovalStoredRequest> = emptyList()

      override fun claimedDeliveries(owner: ApprovalOwnerKey): List<ApprovalStoredRequest> = emptyList()

      override fun recent(
        repositoryIdentity: String,
        limit: Int,
      ): List<ApprovalStoredRequest> = emptyList()

      override fun saveOwner(lease: ApprovalOwnerLease) = Unit

      override fun owner(key: ApprovalOwnerKey): ApprovalOwnerLease? = null

      override fun saveConsumer(lease: ApprovalConsumerLease) = Unit

      override fun consumer(repositoryIdentity: String): ApprovalConsumerLease? = null
    }
  open override val blockIncidents: BlockIncidentRepository = InMemoryBlockIncidentRepository()
  open override val blockIncidentRecoveries: BlockIncidentRecoveryRepository = InMemoryBlockIncidentRecoveryRepository()
  open override val productReports: ProductReportRepository = InMemoryProductReportRepository()
  open override val diffComments: DiffCommentStore = UnavailableDiffCommentStore

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts = GoalPurgeTableCounts()

  override fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts = GoalPurgeTableCounts()

  override fun snapshotGoalArchiveOwned(target: GoalPurgeTarget): GoalArchiveOwnedSnapshot =
    GoalArchiveOwnedSnapshot(
      target.workflowIds.mapNotNull(workflowStates::getFeatureTaskWorkflow),
      emptyList(),
      emptyList(),
      emptyList(),
    )

  override fun restoreGoalArchiveOwnedTables(ownedTables: GoalArchiveOwnedTables) = Unit
}

private class EmptyBoardReadModel : WorkBoardReadModelRepository {
  override fun upsert(row: WorkBoardReadModelRow) = Unit

  override fun delete(workId: String) = Unit

  override fun deleteAll() = Unit

  override fun isComplete(): Boolean = false

  override fun setComplete(complete: Boolean) = Unit

  override fun page(query: WorkBoardReadModelPageQuery): WorkBoardReadModelPage = WorkBoardReadModelPage(emptyList())

  override fun readByWorkIds(workIds: Set<String>): List<WorkBoardReadModelPage.Entry> = emptyList()
}
