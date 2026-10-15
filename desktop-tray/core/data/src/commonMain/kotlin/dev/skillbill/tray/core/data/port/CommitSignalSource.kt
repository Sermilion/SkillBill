package dev.skillbill.tray.core.data.port

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/** A committed-write notification; [revisions] are the per-topic revisions the writer published. */
data class StoreChange(
  val initial: Boolean = false,
  val resynchronize: Boolean = false,
  val revisions: Map<String, Long> = emptyMap(),
)

/** The topic names the writer publishes revisions for. */
class SignalTopics(val all: Set<String>, val goals: String, val phases: String, val activity: String)

/** External committed-write signals for one store file. There is no polling: only a published signal produces a change. */
interface CommitSignalSource {
  val topics: SignalTopics

  /** Completes [ready] once the first baseline is published, or fails it when observation cannot start. */
  fun changes(databasePath: String, ready: CompletableDeferred<Unit>): Flow<StoreChange>
}
