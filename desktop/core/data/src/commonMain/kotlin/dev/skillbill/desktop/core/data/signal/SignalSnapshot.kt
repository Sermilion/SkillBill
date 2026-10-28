package dev.skillbill.desktop.core.data.signal

/**
 * The last commit signal the session read. [revisions] and [changed] are keyed by `DesktopReadSchema` topic names. [generation]
 * is null before the first valid read. [resyncEpoch] grows on every resynchronizing snapshot, so a consumer keyed on
 * `(revision, resyncEpoch)` re-reads after a resync even when the revisions did not move.
 */
data class SignalSnapshot(
  val generation: String?,
  val revisions: Map<String, Long>,
  val changed: Map<String, ChangedIds>,
  val resynchronize: Boolean,
  val resyncEpoch: Long,
) {
  companion object {
    val Initial = SignalSnapshot(generation = null, revisions = emptyMap(), changed = emptyMap(), resynchronize = false, resyncEpoch = 0)
  }
}

/** The ids a topic's last commit touched, relative to revision [Ids.base]; [All] means re-read the whole topic. */
sealed interface ChangedIds {
  data class Ids(val base: Long, val ids: List<String>) : ChangedIds

  data object All : ChangedIds
}
