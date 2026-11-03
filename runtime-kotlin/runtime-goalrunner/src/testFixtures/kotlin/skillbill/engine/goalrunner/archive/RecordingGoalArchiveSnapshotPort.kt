package skillbill.engine.goalrunner.archive

import skillbill.ports.goalrunner.GoalArchiveSnapshotPort
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.persistence.model.GoalPurgeTarget

class RecordingGoalArchiveSnapshotPort(
  private val snapshotProvider: (GoalPurgeTarget) -> GoalArchiveOwnedSnapshot = {
    GoalArchiveOwnedSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
  },
) : GoalArchiveSnapshotPort {
  val targets = mutableListOf<GoalPurgeTarget>()

  override fun snapshot(target: GoalPurgeTarget): GoalArchiveOwnedSnapshot {
    targets += target
    return snapshotProvider(target)
  }
}
