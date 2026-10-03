package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity

@Inject
class GoalPlanningMigrationAdmission(
  private val database: DatabaseSessionFactory,
  private val migration: GoalPlanningMigration,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun admit(identity: GoalPlanningIdentity) {
    var sourceVersion = "unknown"
    val changed =
      runCatching {
        database.transaction {
          sourceVersion = it.goalPlanningPreparations.findSharedPreplan(identity)
            ?.provenance?.phaseOutputContractVersion?.takeIf {
                version ->
              version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}"))
            }
            ?: "unknown"
          migration.migrate(it, identity.parentGoalWorkflowId, identity.repositoryIdentity, identity.normalizedIssueKey)
        }
      }.getOrElse { error ->
        val result =
          ((error as? SkillBillRuntimeException)?.code as? FeatureTaskRuntimeMigrationFailureCode)
            ?.name?.lowercase() ?: "contract_or_storage_failure"
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=goal_planning_migration source_version=$sourceVersion target_version=0.7 result=$result",
        )
        error.rethrowIfCooperativeCancellationOrInterruption()
        if (error is SkillBillRuntimeException) throw error
        throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
          "Durable migration transaction failed. Original planning was preserved. " +
            "Resolve the storage failure and retry.",
          error,
        )
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=goal_planning_migration source_version=$sourceVersion target_version=0.7 " +
        "result=${if (changed) "committed" else "current_or_absent"}",
    )
  }
}
