package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.data.session.SessionGraph
import dev.skillbill.desktop.core.data.session.SessionGraphFactory
import dev.skillbill.desktop.core.data.session.SessionSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.util.Collections

/** A session graph that records `open` and `release` into its factory's shared [FakeSessionGraphFactory.events] log. */
class FakeSessionGraph(
  val selection: SessionSelection,
  private val events: MutableList<String>,
  private val openFailure: Throwable?,
) : SessionGraph {
  override fun displayPath(): String = selection.databasePath ?: DEFAULT_PATH

  override fun open(): Job {
    events += "open ${selection.id}"
    openFailure?.let { throw it }
    return selection.scope.launch { awaitCancellation() }
  }

  override fun release() {
    events += "release ${selection.id}"
  }

  companion object {
    const val DEFAULT_PATH = "/default/review-metrics.db"
  }
}

/** Creates [FakeSessionGraph]s; a path in [openFailures] makes that graph's `open()` throw the mapped failure. */
class FakeSessionGraphFactory(private val openFailures: Map<String?, Throwable> = emptyMap()) : SessionGraphFactory<FakeSessionGraph> {
  val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val graphs: MutableList<FakeSessionGraph> = Collections.synchronizedList(mutableListOf())

  override fun create(selection: SessionSelection): FakeSessionGraph =
    FakeSessionGraph(selection, events, openFailures[selection.databasePath]).also { graphs += it }
}
