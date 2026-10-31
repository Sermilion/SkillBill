package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goal.GoalBlockRecoveryPort
import skillbill.engine.goal.GoalIssueStartedReader
import skillbill.engine.goal.GoalManifestProjectionReader
import skillbill.engine.goal.StandalonePlanReportPort
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.manifest.GoalRunnerBlockRecoveryAdapter
import skillbill.engine.goalrunner.manifest.GoalRunnerIssueStartedReader
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestProjectionReader
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerProgressRecording
import skillbill.engine.goalrunner.plan.StandalonePlanReportBuilder
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator

internal interface RuntimeGoalRunnerProjectionProvides {
  @Provides
  fun goalParentProjectionWriter(validator: DecompositionManifestValidator): GoalParentProjectionWriter =
    GoalParentProjectionWriter(validator)

  @Provides
  fun goalBlockRecoveryPort(
    writer: GoalParentProjectionWriter,
    writes: WorkflowGoalRunnerBlockWrites,
  ): GoalBlockRecoveryPort = GoalRunnerBlockRecoveryAdapter(writer, writes)

  @Provides
  fun goalManifestProjectionReader(reader: GoalRunnerManifestProjectionReader): GoalManifestProjectionReader = reader

  @Provides
  fun standalonePlanReportPort(builder: StandalonePlanReportBuilder): StandalonePlanReportPort = builder

  @Provides
  fun goalIssueStartedReader(reader: GoalRunnerIssueStartedReader): GoalIssueStartedReader = reader

  @Provides
  fun goalRunnerProgressRecording(
    database: DatabaseSessionFactory,
    snapshots: WorkflowSnapshotValidator,
    artifacts: FeatureTaskRuntimeWireArtifactValidator,
    history: GoalChildHistoryEvidenceStore,
    diagnostics: RuntimeDiagnostics,
  ): WorkflowGoalRunnerProgressRecording =
    WorkflowGoalRunnerProgressRecording(database, snapshots, artifacts, history, diagnostics)
}
