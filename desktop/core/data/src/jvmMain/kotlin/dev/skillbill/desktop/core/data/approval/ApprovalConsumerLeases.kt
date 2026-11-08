package dev.skillbill.desktop.core.data.approval

import dev.skillbill.desktop.core.common.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import skillbill.application.approval.ApprovalOperatorService
import skillbill.ports.approval.model.ApprovalConsumerResult
import kotlin.time.Duration.Companion.seconds

/** One third of the broker's 60 s consumer lease, so two renewals can fail before the lease lapses. */
internal val CONSUMER_RENEW_INTERVAL = 20.seconds

/**
 * The operator consumer registrations of one database session, one per repository identity while at least one
 * collector observes it. The nonce the broker mints stays here: the UI never holds it. Every method runs on the database
 * dispatcher; [renewScope] is the session's own scope, so the session close cancels renewal before the graph is released.
 */
internal class ApprovalConsumerLeases(
  private val service: ApprovalOperatorService,
  private val renewScope: CoroutineScope,
  private val consumerId: String,
  private val diagnostics: Diagnostics,
  private val changed: () -> Unit,
) {
  internal class Entry(val repositoryIdentity: String) {
    var subscribers = 0
    var nonce: String? = null
    var renewal: Job? = null
  }

  private val lock = Any()
  private val entries = HashMap<String, Entry>()
  private var closed = false

  /** Counts one more collector, registering the consumer for the first; null once the session is closing. */
  fun acquire(repositoryIdentity: String): Entry? {
    val entry = synchronized(lock) {
      if (closed || !renewScope.isActive) return null
      entries.getOrPut(repositoryIdentity) { Entry(repositoryIdentity) }.also { entry ->
        entry.subscribers++
        if (entry.subscribers == 1) {
          register(entry)
          entry.renewal = startRenewal(entry)
        }
      }
    }
    changed()
    return entry
  }

  /** Drops one collector; the last one ends the lease, which cancels the repository's outstanding requests. */
  fun release(entry: Entry) {
    val ending = synchronized(lock) {
      if (entries[entry.repositoryIdentity] !== entry) return
      entry.subscribers--
      if (entry.subscribers > 0) return
      entries.remove(entry.repositoryIdentity)
      entry
    }
    end(ending)
    changed()
  }

  /** Session close: ends every lease and refuses later ones. Idempotent. */
  fun releaseAll() {
    val ending = synchronized(lock) {
      closed = true
      entries.values.toList().also { entries.clear() }
    }
    ending.forEach(::end)
  }

  /** The nonce a decision must present, or null when no registration is live for the repository. */
  fun nonceFor(repositoryIdentity: String): String? = synchronized(lock) {
    if (closed || !renewScope.isActive) null else entries[repositoryIdentity]?.nonce
  }

  fun isLive(entry: Entry): Boolean = synchronized(lock) { entries[entry.repositoryIdentity] === entry && entry.nonce != null }

  private fun end(entry: Entry) {
    entry.renewal?.cancel()
    val nonce = entry.nonce ?: return
    try {
      service.releaseConsumer(entry.repositoryIdentity, nonce)
    } catch (_: RuntimeException) {
      diagnostics.record("approval.consumer_release_failed")
    }
  }

  private fun register(entry: Entry) {
    entry.nonce = try {
      service.registerConsumer(entry.repositoryIdentity, consumerId).lease.nonce
    } catch (_: RuntimeException) {
      diagnostics.record("approval.consumer_register_failed")
      null
    }
  }

  private fun startRenewal(entry: Entry): Job = renewScope.launch {
    while (isActive) {
      delay(CONSUMER_RENEW_INTERVAL)
      if (synchronized(lock) { renew(entry) }) changed()
    }
  }

  /** Renews the lease; a lapsed or superseded one registers again once. Returns whether liveness or the nonce changed. */
  private fun renew(entry: Entry): Boolean {
    if (entries[entry.repositoryIdentity] !== entry) return false
    val before = entry.nonce
    if (before == null) {
      register(entry)
    } else {
      try {
        when (service.renewConsumer(entry.repositoryIdentity, before)) {
          is ApprovalConsumerResult.Registered -> Unit
          ApprovalConsumerResult.Stale -> {
            diagnostics.record("approval.consumer_stale")
            register(entry)
          }
        }
      } catch (_: RuntimeException) {
        diagnostics.record("approval.consumer_renew_failed")
        entry.nonce = null
      }
    }
    return before != entry.nonce
  }
}
