package skillbill.engine.goalrunner.planning.model

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal data class GoalPlanningAttemptScope(
  val shared: GoalPlanningSharedContext,
  val phaseId: String,
  val subtask: DecompositionSubtask?,
  val attempt: Int,
)

internal data class GoalPlanningLaunch(
  val runner: PhaseRunner,
  val state: PhaseAcceptedStepExecution,
  val prompt: PhaseStepPromptSource,
  val policy: PhaseStepPolicy,
  val invariantFields: Set<FeatureTaskRuntimeRunInvariantPromptField>,
)

internal data class GoalPlanningPhaseContext(
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val subtask: DecompositionSubtask?,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val phaseId: String,
  val launch: GoalPlanningLaunch,
  val outputSink: AgentRunOutputSink = request.outputSink,
)

internal data class GoalPlanningProduceAttemptArgs(
  val phase: GoalPlanningPhaseContext,
  val recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  val attempt: Int = 1,
  val resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
)

internal data class GoalPlanningAttemptRecordArgs(
  val scope: GoalPlanningAttemptScope,
  val outcome: GoalProgressOutcome,
  val eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
)

internal data class GoalPlanningRejectionRecordArgs(
  val scope: GoalPlanningAttemptScope,
  val rule: String,
  val reason: String,
  val agentId: String,
  val rawEvidence: String,
)

internal data class SharedPreplanSettlementArgs(
  val existingShared: SharedGoalPreplanCheckpoint?,
  val currentProvenance: GoalPlanningContractProvenance,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val launch: GoalPlanningLaunch,
)

internal data class StaleSharedPreplanSettlementArgs(
  val existingShared: SharedGoalPreplanCheckpoint,
  val currentProvenance: GoalPlanningContractProvenance,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val refreshedThisPrepare: Boolean,
  val launch: GoalPlanningLaunch,
)

internal data class RefreshStaleSharedPreplanArgs(
  val existing: SharedGoalPreplanCheckpoint,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val currentProvenance: GoalPlanningContractProvenance,
  val refreshedThisPrepare: Boolean,
  val launch: GoalPlanningLaunch,
)
