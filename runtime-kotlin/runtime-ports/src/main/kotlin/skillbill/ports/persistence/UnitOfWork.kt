package skillbill.ports.persistence

import skillbill.ports.blockincident.BlockIncidentRecoveryRepository
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import java.nio.file.Path

interface UnitOfWork : GoalRunnerPersistenceSession {
  val dbPath: Path
  val databaseFileObservation: DatabaseFileObservation? get() = null
  override val reviews: ReviewRepository
  val learnings: LearningRepository
  val lifecycleTelemetry: LifecycleTelemetryRepository
  val telemetryReconciliation: TelemetryReconciliationRepository
  val telemetryOutbox: TelemetryOutboxRepository
  override val workflowStates: WorkflowStateRepository
  val workList: WorkListRepository
  override val goalPlanningPreparations: GoalPlanningPreparationRepository
  override val goalRunnerControls: GoalRunnerControlRepository
  val unaddressedFindings: UnaddressedFindingsRepository
  val agentActivityStamps: AgentActivityStampRepository
  val worktreeEditJournal: WorktreeEditJournalRepository
  val standalonePhaseStatuses: StandalonePhaseStatusRepository
  val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository
  val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions
  val featureTaskPhaseSettlements: FeatureTaskPhaseSettlementRepository
  val featureTaskQaOutcomes: FeatureTaskQaOutcomeRepository
  val operationProposals: OperationProposalRepository
  val blockIncidents: BlockIncidentRepository
  val blockIncidentRecoveries: BlockIncidentRecoveryRepository

  fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts

  fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts
}
