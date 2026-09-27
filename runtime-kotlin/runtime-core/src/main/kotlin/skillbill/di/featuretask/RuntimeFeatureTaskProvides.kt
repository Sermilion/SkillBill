package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.infrastructure.workflow.featuretask.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.workflow.featuretask.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.workflow.filesystem.FileSystemCheckedOutBranchSource
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor

internal interface RuntimeFeatureTaskProvides {
  @Provides
  fun featureTaskPhaseSettlementRepository(database: DatabaseSessionFactory): FeatureTaskPhaseSettlementRepository =
    SqliteFeatureTaskPhaseSettlementRepository(database)

  @Provides
  fun featureTaskRuntimeRunInvariantsSource(
    adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,
  ): FeatureTaskRuntimeRunInvariantsSource = adapter

  @Provides @RuntimeSingleton
  fun featureTaskRuntimeWorkerSupervisor(
    adapter: JdkFeatureTaskRuntimeWorkerSupervisor,
  ): FeatureTaskRuntimeWorkerSupervisor = adapter

  @Provides
  fun featureTaskRuntimeSpecStatusWriter(
    adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter,
  ): FeatureTaskRuntimeSpecStatusWriter = adapter

  @Provides
  fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource): CheckedOutBranchSource = source

  @Provides
  fun featureTaskRuntimeReadinessEvidencePort(
    recorder: FeatureTaskRuntimePhaseRecorder,
  ): FeatureTaskRuntimeReadinessEvidencePort = recorder
}
