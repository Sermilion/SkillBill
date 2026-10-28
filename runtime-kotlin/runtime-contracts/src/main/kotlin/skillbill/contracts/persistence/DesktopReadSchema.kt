package skillbill.contracts.persistence

object DesktopReadSchema {
  const val VERSION = 89
  const val INCIDENT_DETAIL_CAPABILITY_VERSION = 1
  const val MIN_INCIDENT_DETAIL_READ_SCHEMA = VERSION
  const val IDENTITY_TABLE = "work_store_identity"
  const val ROOM_IDENTITY_HASH = "7915a1b532fab60d83518e19b12c4aa0"
  const val REVISIONS_TABLE = "desktop_change_revisions"
  const val CHANGE_ITEMS_TABLE = "desktop_change_items"
  const val CHANGE_ITEM_RETENTION = 256
  const val GENERATION_TABLE = "desktop_signal_generation"
  const val GOALS_TOPIC = "goals"
  const val PHASES_TOPIC = "phases"
  const val ACTIVITY_TOPIC = "activity"
  const val SPECS_TOPIC = "specs"
  val TOPICS = listOf(GOALS_TOPIC, PHASES_TOPIC)
  val SIGNALLED_TOPICS = listOf(GOALS_TOPIC, PHASES_TOPIC, ACTIVITY_TOPIC, SPECS_TOPIC)
}
