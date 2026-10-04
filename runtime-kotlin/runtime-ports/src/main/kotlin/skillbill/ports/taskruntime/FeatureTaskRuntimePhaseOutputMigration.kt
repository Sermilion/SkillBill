package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult

interface FeatureTaskRuntimePhaseOutputMigration {
  fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigrationResult
}
