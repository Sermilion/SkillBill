package dev.skillbill.desktop.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeListResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * Shares working-tree list reads per baseline. A caller waits for a read already in flight for the same baseline, and reuses
 * a result that finished less than [CHANGE_REFRESH_INTERVAL] ago, so every screen and window observing one baseline costs
 * one git read per refresh. Failed results and exceptions are never reused.
 */
class WorkingTreeReads(private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
  private class Entry {
    val gate = Mutex()
    var last: Pair<ComparableTimeMark, WorkChangeListResult>? = null
  }

  private val entries = ConcurrentHashMap<WorkChangeBaseline, Entry>()

  suspend fun changes(baseline: WorkChangeBaseline, read: suspend () -> WorkChangeListResult): WorkChangeListResult {
    val entry = entries.computeIfAbsent(baseline) { Entry() }
    return entry.gate.withLock {
      entry.last?.takeIf { (mark, _) -> mark.elapsedNow() < CHANGE_REFRESH_INTERVAL }?.let { return@withLock it.second }
      read().also { result ->
        entry.last = if (result is WorkChangeListResult.Failed) null else time.markNow() to result
      }
    }
  }
}
