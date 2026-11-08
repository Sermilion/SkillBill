package skillbill.ports

import skillbill.ports.approval.ApprovalStore
import skillbill.ports.blockincident.BlockIncidentRecoveryRepository
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.ProductReportRepository
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.MonitorCiCycleRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.review.diffcomment.DiffCommentStore
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import java.nio.file.Path

interface UnitOfWork : GoalRunnerPersistenceSession {
  val dbPath: Path
  val databaseFileObservation: DatabaseFileObservation?
  override val reviews: ReviewRepository
  val learnings: LearningRepository
  val lifecycleTelemetry: LifecycleTelemetryRepository
  val telemetryReconciliation: TelemetryReconciliationRepository
  val telemetryOutbox: TelemetryOutboxRepository
  override val workflowStates: WorkflowStateRepository
  val workList: WorkListRepository
  val workBoardReadModel: WorkBoardReadModelRepository
  override val goalPlanningPreparations: GoalPlanningPreparationRepository
  override val goalRunnerControls: GoalRunnerControlRepository
  val unaddressedFindings: UnaddressedFindingsRepository
  val agentActivityStamps: AgentActivityStampRepository
  val worktreeEditJournal: WorktreeEditJournalRepository
  val agentBuildRuns: AgentBuildRunRepository
  val monitorCiCycles: MonitorCiCycleRepository
  val standalonePhaseStatuses: StandalonePhaseStatusRepository
  val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository
  val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions
  val featureTaskPhaseSettlements: FeatureTaskPhaseSettlementRepository
  val featureTaskQaOutcomes: FeatureTaskQaOutcomeRepository
  val operationProposals: OperationProposalRepository
  val operationCards: OperationCardRepository
  val approvals: ApprovalStore
  val blockIncidents: BlockIncidentRepository
  val blockIncidentRecoveries: BlockIncidentRecoveryRepository
  val productReports: ProductReportRepository
  val diffComments: DiffCommentStore

  fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts

  fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts

  fun snapshotGoalArchiveOwned(target: GoalPurgeTarget): GoalArchiveOwnedSnapshot

  fun restoreGoalArchiveOwnedTables(ownedTables: GoalArchiveOwnedTables)
}
