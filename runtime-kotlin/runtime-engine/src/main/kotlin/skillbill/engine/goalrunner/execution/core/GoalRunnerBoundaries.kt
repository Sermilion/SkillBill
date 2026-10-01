package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import java.time.Clock

@Inject
data class GoalRunnerRunBoundaries(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val goalPlanningSweep: GoalPlanningSweep,
  val telemetry: GoalLifecycleTelemetryEmitter,
  val clock: Clock,
  val diagnostics: RuntimeDiagnostics,
  val executionCoordinator: GoalRunnerExecutionCoordinator,
  val phaseQuery: FeatureTaskRuntimePhaseQuery?,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
)

@Inject
data class GoalRunnerSubtaskLaunchBoundaries(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val gitOperations: WorkflowGitOperations,
)

@Inject
data class GoalRunnerFinalizationBoundaries(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val specScratchStore: SpecScratchStore,
  val gitOperations: WorkflowGitOperations,
  val diagnostics: RuntimeDiagnostics,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
)
