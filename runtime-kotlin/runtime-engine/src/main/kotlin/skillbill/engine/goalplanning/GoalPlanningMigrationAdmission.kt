package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity

@Inject
class GoalPlanningMigrationAdmission(
  private val database: DatabaseSessionFactory,
  private val migration: GoalPlanningMigration,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun admit(identity: GoalPlanningIdentity): RuntimeMigrationReceipt {
    var sourceVersion = "unknown"
    val receipt =
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
        error.rethrowIfCooperativeCancellationOrInterruption()
        val reported =
          when (error) {
            is InvalidGoalPlanningPreparationSchemaError,
            is IncompatibleGoalPlanningPreparationRecoveryError,
            ->
              SkillBillRuntimeException(
                FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
                "Persisted planning preparation is corrupt or internally inconsistent. Preserve the original records " +
                  "and restore or repair the identified record before retrying.",
                error,
              )
            is SkillBillRuntimeException -> error
            else ->
              SkillBillRuntimeException(
                FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
                "Durable migration transaction failed. Original planning was preserved. " +
                  "Resolve the storage failure and retry.",
                error,
              )
          }
        val result =
          reported.code.let { code ->
            (code as? Enum<*>)?.name?.lowercase()
          } ?: "contract_or_storage_failure"
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=goal_planning_migration source_version=$sourceVersion " +
            "target_version=$FEATURE_TASK_RUNTIME_CONTRACT_VERSION result=$result",
        )
        throw reported
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=goal_planning_migration source_version=${receipt.sourceVersion} " +
        "target_version=${receipt.targetVersion} " +
        "result=" +
        if (receipt.result == RuntimeMigrationReceipt.Result.CONVERTED) "committed" else "current_or_absent",
    )
    return receipt
  }
}
