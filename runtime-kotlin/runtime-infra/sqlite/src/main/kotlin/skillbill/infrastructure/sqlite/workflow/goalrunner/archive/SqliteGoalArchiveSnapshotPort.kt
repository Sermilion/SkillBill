package skillbill.infrastructure.sqlite.workflow.goalrunner.archive

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.GoalArchiveSnapshotPort
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.persistence.model.GoalPurgeTarget

@Inject
class SqliteGoalArchiveSnapshotPort(
  private val database: DatabaseSessionFactory,
) : GoalArchiveSnapshotPort {
  override fun snapshot(target: GoalPurgeTarget): GoalArchiveOwnedSnapshot =
    database.read { unitOfWork -> unitOfWork.snapshotGoalArchiveOwned(target) }

  override fun restoreOwnedTables(ownedTables: GoalArchiveOwnedTables) {
    database.transaction { unitOfWork ->
      unitOfWork.restoreGoalArchiveOwnedTables(ownedTables)
    }
  }
}
