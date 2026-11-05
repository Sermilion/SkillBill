package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.persistence.model.GoalPurgeTarget

/** Reads purge-owned durable rows for a portable goal archive snapshot. */
interface GoalArchiveSnapshotPort {
  /** Returns the owned workflow, planning, and satellite table rows for [target]. */
  fun snapshot(target: GoalPurgeTarget): GoalArchiveOwnedSnapshot

  /** Restores purge-owned satellite table rows from an archive snapshot. */
  fun restoreOwnedTables(ownedTables: GoalArchiveOwnedTables)
}
