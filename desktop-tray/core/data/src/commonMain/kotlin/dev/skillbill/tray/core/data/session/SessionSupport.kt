package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.di.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Monotonic nanosecond reading for measuring durations; only differences between readings are meaningful. */
fun interface MonotonicClock {
  fun nanos(): Long
}

internal object TimeSourceMonotonicClock : MonotonicClock {
  private val origin = TimeSource.Monotonic.markNow()

  override fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}

@ContributesTo(AppScope::class)
interface DataTimeProviders {
  @Provides
  fun clock(): Clock = Clock.System

  @Provides
  fun monotonicClock(): MonotonicClock = TimeSourceMonotonicClock
}

/** A thread-safe counter whose values never repeat. */
internal class Counter {
  private val value = MutableStateFlow(0L)

  fun next(): Long = value.updateAndGet { it + 1 }
}

/**
 * Database generations are shared by every session of one app run, so a view, cursor or control target taken from a
 * previous selection can never match the generation of the next one.
 */
@Inject
@SingleIn(AppScope::class)
class DatabaseGenerations {
  private val counter = Counter()
  private val published = MutableStateFlow(0L)

  internal fun next(): Long = counter.next()

  /**
   * The generation of the database the app reads, as the navigation layer sees it. It advances once when the selected
   * database changes (including a selection of the same path) and once when a live connection is found replaced, and never
   * on the routine opening of a connection, so a screen is invalidated exactly when what it shows can no longer be trusted.
   */
  val current: StateFlow<Long> = published.asStateFlow()

  internal fun publishReplacement() {
    published.update { it + 1 }
  }
}

/** Whole milliseconds to wait for [remaining], rounded up so a timer never fires before its deadline. */
internal fun waitMillis(remaining: Duration): Long {
  val nanos = remaining.inWholeNanoseconds
  return (nanos.floorDiv(1_000_000L) + if (nanos % 1_000_000L == 0L) 0 else 1).coerceAtLeast(1)
}

/** A small concurrent map for state that is touched from several coroutines without a shared lock. */
internal class ConcurrentRegistry<K, V> {
  private val state = MutableStateFlow(emptyMap<K, V>())

  operator fun get(key: K): V? = state.value[key]

  fun remove(key: K): V? {
    var removed: V? = null
    state.update { current ->
      removed = current[key]
      if (removed == null) current else current - key
    }
    return removed
  }

  /** Removes every entry whose key matches [predicate] and returns the removed values. */
  fun removeAll(predicate: (K) -> Boolean): List<V> {
    var removed = emptyList<V>()
    state.update { current ->
      removed = current.filterKeys(predicate).values.toList()
      if (removed.isEmpty()) current else current.filterKeys { !predicate(it) }
    }
    return removed
  }

  fun compute(key: K, remap: (V?) -> V): V {
    var result: V? = null
    state.update { current ->
      remap(current[key]).also { result = it }.let { current + (key to it) }
    }
    return checkNotNull(result)
  }

  fun clear() = state.update { emptyMap() }
}
