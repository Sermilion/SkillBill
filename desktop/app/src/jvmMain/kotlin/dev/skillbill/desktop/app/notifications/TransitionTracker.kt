package dev.skillbill.desktop.app.notifications

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.settings.NotificationKind

internal class TransitionTracker {
  private data class Key(val identity: String, val execution: String?)
  private data class Seen(val lifecycle: Lifecycle, val statusRevision: String?, val updatedAt: kotlin.time.Instant, val storeId: String?, val storeRevision: Long?, val qaAt: kotlin.time.Instant?)
  private val seen = linkedMapOf<Key, Seen>()

  fun clear() = seen.clear()

  fun baseline(items: List<WorkItem>) {
    clear()
    items.filter { it.fresh }.forEach { item -> seen[key(item)] = item.seen(); trim() }
  }

  fun observe(items: List<WorkItem>): List<Pair<WorkItem, NotificationKind>> = buildList {
    items.filter { it.fresh }.forEach { item ->
      val key = key(item)
      val prior = seen[key]
      if (prior != null && !newer(item, prior)) return@forEach
      seen[key] = item.seen()
      trim()
      if (prior == null) return@forEach
      val transition = when {
        item.lifecycle == Lifecycle.Blocked && prior.lifecycle != Lifecycle.Blocked -> NotificationKind.Blocked
        item.lifecycle == Lifecycle.Failed && prior.lifecycle != Lifecycle.Failed -> NotificationKind.Failed
        item.kind?.name == "Goal" && item.lifecycle == Lifecycle.Terminal && item.completionSource?.name == "GoalCompletion" && prior.lifecycle != Lifecycle.Terminal -> NotificationKind.GoalCompleted
        item.lifecycle == Lifecycle.Paused && prior.lifecycle != Lifecycle.Paused -> NotificationKind.PauseTookEffect
        item.standaloneExecution && item.lifecycle == Lifecycle.Terminal && prior.lifecycle != Lifecycle.Terminal -> NotificationKind.StandalonePhaseFinished
        else -> null
      }
      val qa = item.workflowQaOutcome?.takeIf { it.couldNotComplete }?.recordedAt
      val qaEvent = qa != null && qa != prior.qaAt
      if (qaEvent) add(item to NotificationKind.QaIncomplete)
      if (transition != null && !(qaEvent && transition in setOf(NotificationKind.Blocked, NotificationKind.Failed))) add(item to transition)
    }
  }

  private fun key(item: WorkItem) = Key("${item.id}\u0000${item.control.repositoryIdentity}", item.control.executionId ?: item.control.invocationId)
  private fun WorkItem.seen() = Seen(lifecycle, control.statusRevision, control.updatedAt, control.storeId, control.storeRevision, workflowQaOutcome?.takeIf { it.couldNotComplete }?.recordedAt)
  private fun trim() { while (seen.size > 1000) seen.remove(seen.keys.first()) }
  private fun newer(item: WorkItem, old: Seen): Boolean {
    if (item.control.storeId != old.storeId) return false
    val revision = item.control.storeRevision
    if (revision != null && old.storeRevision != null && revision != old.storeRevision) return revision > old.storeRevision
    val current = item.control.statusRevision?.trimStart('0')?.ifEmpty { "0" }
    val previous = old.statusRevision?.trimStart('0')?.ifEmpty { "0" }
    if (current != null && previous != null) {
      if (current.length != previous.length) return current.length > previous.length
      if (current != previous) return current > previous
    }
    return item.control.updatedAt > old.updatedAt
  }
}
