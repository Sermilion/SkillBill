package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.RuntimeDatabaseReadiness
import skillbill.ports.db.RuntimeWorkerLeaseQuery
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.taskruntime.model.isConfirmedDead
import java.time.Clock

@Inject
class InstallDatabasePreparation(
  private val databaseSchemaPort: DatabaseSchemaPort,
  private val workerLeases: RuntimeWorkerLeaseQuery,
  private val processInspector: MonitorProcessInspector,
  private val clock: Clock,
) : RuntimeDatabaseReadiness {
  private var readLifecycleStarted = false

  @Synchronized
  override fun initializeRead(): RuntimeDatabaseReadinessResult {
    if (!readLifecycleStarted) {
      readLifecycleStarted = true
      return prepareExisting()
    }
    return observeRead()
  }

  override fun observeRead(): RuntimeDatabaseReadinessResult =
    try {
      val observation = databaseSchemaPort.observeReadiness()
      when {
        !observation.existing -> RuntimeDatabaseReadinessResult.Ready(false, observation)
        !observation.requiresPreparation -> RuntimeDatabaseReadinessResult.Ready(true, observation)
        else ->
          RuntimeDatabaseReadinessResult.Refused(
            SkillBillRuntimeException(
              AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
              "The selected database requires preparation before it can be read. " +
                "Its source remains retained; retry an authorized initialization boundary.",
            ),
          )
      }
    } catch (error: SkillBillRuntimeException) {
      RuntimeDatabaseReadinessResult.Refused(error)
    }

  @Synchronized
  override fun prepareExisting(): RuntimeDatabaseReadinessResult {
    readLifecycleStarted = true
    return try {
      val liveHolders = liveWorkersBlockingMigration()
      if (liveHolders.isNotEmpty()) {
        RuntimeDatabaseReadinessResult.Refused(
          SkillBillRuntimeException(
            AcceptanceCriterionMigrationFailureCode.MIGRATION_WORKER_ACTIVE,
            deferred(liveHolders).let { "${it.message} ${it.guidance}" },
          ),
        )
      } else {
        val existing = databaseSchemaPort.migrateExistingDatabase()
        RuntimeDatabaseReadinessResult.Ready(existing, databaseSchemaPort.observeReadiness())
      }
    } catch (error: SkillBillRuntimeException) {
      RuntimeDatabaseReadinessResult.Refused(error)
    }
  }

  fun prepare(result: InstallApplyResult): InstallApplyResult {
    if (result.status == InstallApplyStatus.FAILURE) {
      return result
    }
    val issue = prepareUpgrade() ?: return result
    return result.copy(status = InstallApplyStatus.FAILURE, failures = result.failures + issue)
  }

  fun prepareUpgrade(): InstallApplyIssue? =
    try {
      val liveHolders = liveWorkersBlockingMigration()
      if (liveHolders.isNotEmpty()) {
        deferred(liveHolders)
      } else {
        databaseSchemaPort.ensureDatabaseReady()
        null
      }
    } catch (error: SkillBillRuntimeException) {
      InstallApplyIssue(
        kind = InstallApplyIssueKind.DATABASE_MIGRATION_FAILED,
        message = error.message.orEmpty(),
        guidance =
          "The existing data was retained. Resolve the migration failure and retry installation " +
            "before using the new runtime.",
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      )
    }

  private fun liveWorkersBlockingMigration(): List<String> {
    if (!databaseSchemaPort.hasPendingMigrations()) return emptyList()
    return workerLeases.unexpiredLeases(clock.instant())
      .filterNot { lease -> processInspector.inspect(lease.process).isConfirmedDead() }
      .map { lease -> lease.holder }
      .distinct()
  }

  private fun deferred(liveHolders: List<String>): InstallApplyIssue =
    InstallApplyIssue(
      kind = InstallApplyIssueKind.DATABASE_MIGRATION_DEFERRED,
      message =
        "Skipped the database migration because Skill Bill runs are still active: " +
          "${liveHolders.joinToString(", ")}. Those runs use the previous runtime, which cannot read the " +
          "migrated schema.",
      guidance =
        "Finish or stop those runs, then retry installation. Keep the existing runtime until migration succeeds.",
    )
}
