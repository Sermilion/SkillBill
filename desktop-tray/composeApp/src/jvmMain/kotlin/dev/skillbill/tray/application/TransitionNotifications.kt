package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.allowedTransitions
import dev.skillbill.tray.core.domain.model.isFresh
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.ui.StoreObservation
import dev.skillbill.tray.core.ui.TrackedBoardSections
import dev.skillbill.tray.core.ui.TrackedSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Instant

fun enabledTransitions(events: List<Pair<WorkItem, String>>, settings: AppSettings): List<Pair<WorkItem, String>> =
  events.filter { (_, transition) -> transition in allowedTransitions && settings.notifications[transition] == true }

/**
 * Turns the board into desktop notifications. It reads the board through its own [TrackedBoardSections], so no screen's filter or
 * lifecycle decides what it sees. The first settled state of a store is a silent baseline, so work that was already finished or
 * blocked when the app opened never notifies; after that each newer lifecycle change does. A change of store starts the baseline
 * over, and a notification queued for the previous store is dropped when it would be sent. A reconnect to the same store (a new
 * observation session) is baselined silently as well, but keeps what is already queued.
 *
 * [scope] must be single-threaded: the sections it drives are not synchronised.
 */
class TransitionNotificationCoordinator(
  scope: CoroutineScope,
  boards: WorkBoardRepository,
  settings: SettingsRepository,
  private val native: NativeServices,
  private val onSelect: (WorkIdentity) -> Unit,
) {
  private val sections = TrackedBoardSections(scope, boards)
  private val tracker = TransitionTracker()
  @Volatile private var current: AppSettings = AppSettings()
  private var observedStore: StoreObservation? = null
  private var needsBaseline = true
  private var baselineSession: Long? = null
  @Volatile private var epoch = 0L

  init {
    scope.launch {
      settings.settings.collect { result ->
        if (result is SettingsState.Ready) current = result.settings
        sections.apply(result)
      }
    }
    scope.launch { sections.state.collect(::observe) }
  }

  internal fun observe(state: TrackedSections) {
    val store = state.store
    if (store?.place() != observedStore?.place()) {
      if (observedStore != null) epoch++
      tracker.clear()
      needsBaseline = true
    }
    observedStore = store
    // Wait until every section was read from one session; a reconnect's first full read is a silent baseline too.
    val observation = state.observation
    if (observation == null || !state.settled) return
    val items = state.trackedItems
    if (needsBaseline || observation.observationSession != baselineSession) {
      tracker.baseline(items)
      baselineSession = observation.observationSession
      needsBaseline = false
      return
    }
    val notificationEpoch = epoch
    enabledTransitions(tracker.observe(items), current).forEach { (item, transition) ->
      native.notifyAuthorized(item.summary, item.currentStepLabel, item.identity, { send ->
        if (notificationEpoch == epoch && current.notifications[transition] == true) send()
      }, onSelect)
    }
  }

  private fun StoreObservation.place() = generation to databasePath
}

class TransitionTracker {
  private data class Key(val identity: WorkIdentity, val executionId: String?)
  private data class Observation(val lifecycle: Lifecycle, val revision: String?, val updatedAt: Instant,
    val storeId: String?, val storeRevision: Long?)
  private val last = linkedMapOf<Key, Observation>()

  fun baseline(items: List<WorkItem>) {
    last.clear()
    items.filter { it.isFresh() }.forEach { item ->
      val key = Key(item.identity, item.executionId ?: item.invocationId)
      last.remove(key)
      last[key] = observation(item)
      while (last.size > 1000) last.remove(last.keys.first())
    }
  }

  fun observe(items: List<WorkItem>): List<Pair<WorkItem, String>> = buildList {
    items.forEach { item ->
      if (!item.isFresh()) return@forEach
      val key = Key(item.identity, item.executionId ?: item.invocationId)
      val prior = last[key]
      if (prior != null && !isNewer(item, prior)) return@forEach
      last.remove(key)
      last[key] = observation(item)
      while (last.size > 1000) last.remove(last.keys.first())
      if (prior == null) return@forEach
      val event = when {
        item.lifecycle == Lifecycle.BLOCKED && prior.lifecycle != Lifecycle.BLOCKED -> "blocked"
        item.lifecycle == Lifecycle.FAILED && prior.lifecycle != Lifecycle.FAILED -> "failed"
        item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.TERMINAL && prior.lifecycle != Lifecycle.TERMINAL -> "goal_completed"
        item.lifecycle == Lifecycle.PAUSED && prior.lifecycle != Lifecycle.PAUSED -> "pause_took_effect"
        item.scope == ExecutionScope.STANDALONE_PHASE && item.lifecycle == Lifecycle.TERMINAL && prior.lifecycle != Lifecycle.TERMINAL -> "standalone_phase_finished"
        else -> null
      }
      if (event != null) add(item to event)
    }
  }

  fun clear() { last.clear() }

  private fun observation(item: WorkItem) = Observation(item.lifecycle, item.statusRevision, item.updatedAt,
    item.databaseStoreId, item.databaseRevision)

  private fun isNewer(item: WorkItem, prior: Observation): Boolean {
    if (item.databaseStoreId != prior.storeId) return false
    if (item.databaseRevision != null && prior.storeRevision != null && item.databaseRevision != prior.storeRevision) {
      return item.databaseRevision > prior.storeRevision
    }
    val revision = item.statusRevision
    val previousRevision = prior.revision
    if (revision != null && previousRevision != null) {
      val normalized = revision.trimStart('0').ifEmpty { "0" }
      val previous = previousRevision.trimStart('0').ifEmpty { "0" }
      if (normalized.length != previous.length) return normalized.length > previous.length
      if (normalized != previous) return normalized > previous
    }
    return item.updatedAt > prior.updatedAt
  }
}
