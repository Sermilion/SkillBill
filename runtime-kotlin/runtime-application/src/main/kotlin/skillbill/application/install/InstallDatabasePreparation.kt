package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.RuntimeWorkerLeaseQuery
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.taskruntime.model.isConfirmedDead
import java.time.Clock

@Inject
class InstallDatabasePreparation(
  private val databaseSchemaPort: DatabaseSchemaPort,
  private val workerLeases: RuntimeWorkerLeaseQuery,
  private val processInspector: MonitorProcessInspector,
  private val clock: Clock,
) {
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
