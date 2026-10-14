package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.di.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
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

  internal fun next(): Long = counter.next()
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

  fun compute(key: K, remap: (V?) -> V): V {
    var result: V? = null
    state.update { current ->
      remap(current[key]).also { result = it }.let { current + (key to it) }
    }
    return checkNotNull(result)
  }

  fun clear() = state.update { emptyMap() }
}
