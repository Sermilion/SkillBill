package skillbill.contracts.persistence

object DesktopReadSchema {
  const val VERSION = 59
  const val IDENTITY_TABLE = "work_store_identity"
  const val ROOM_IDENTITY_HASH = "7915a1b532fab60d83518e19b12c4aa0"
  const val REVISIONS_TABLE = "desktop_change_revisions"
  const val GOALS_TOPIC = "goals"
  const val PHASES_TOPIC = "phases"
  const val ACTIVITY_TOPIC = "activity"
  val TOPICS = listOf(GOALS_TOPIC, PHASES_TOPIC)
}
