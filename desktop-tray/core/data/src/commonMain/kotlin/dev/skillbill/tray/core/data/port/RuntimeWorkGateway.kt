package dev.skillbill.tray.core.data.port

import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import kotlin.time.Instant

/** Revision of the runtime store; two equal observations bracket a coherent snapshot. */
data class StoreObservation(val storeId: String, val revision: Long)

/** One selected work record as the runtime projected it, already mapped to the domain. */
class ProjectedWork(val item: WorkItem, val incompatible: Boolean, val freshnessDeadline: Instant?)

class BoardProjection(
  val items: List<ProjectedWork>,
  /** Distinct runtime diagnostic reasons, as their wire values. */
  val diagnosticReasons: List<String>,
  val observation: StoreObservation?,
)

class DetailProjection(
  /** Wire value of the runtime's refusal, or null when the item was found. */
  val problemCode: String?,
  /** The mapped detail with no continuation cursor; the repository attaches its own. */
  val detail: Detail?,
  val runtimeCursor: String?,
  val listCursors: Map<DetailList, String>,
  /** The runtime's own per-list start anchors; the repository wraps each before it reaches the caller. */
  val listAnchors: Map<DetailList, String>,
  val moreLists: List<DetailList>,
  val runOptions: RunOptions?,
)

/** Reads available inside one runtime snapshot. */
interface SnapshotReads {
  fun projectBoard(ids: List<String>, retention: Retention, observedAt: Instant, pageSize: Int): BoardProjection

  fun detail(
    identity: WorkIdentity,
    observedAt: Instant,
    cursor: String?,
    pageSize: Int,
    anchor: String?,
    list: DetailList?,
  ): DetailProjection
}

/** A live registration for owners whose exit should refresh the view. */
interface OwnerRegistration : AutoCloseable {
  val nextLeaseExpiry: Instant?
}

enum class ControlAction { PAUSE, STOP, CANCEL, LAUNCH }

/** Why the runtime refused a control request, reduced to what the user-facing message distinguishes. */
enum class ControlRefusal { AGENT_REQUIRED, BRANCH_CHANGED, BRANCH_UNAVAILABLE, TERMINATION_INCOMPLETE, OWNERSHIP_MISMATCH, OTHER }

sealed interface ControlResult {
  /** The request was admitted; it makes no claim about the controlled process. */
  data object Accepted : ControlResult

  /** A goal pause is durably recorded; the runner honours it at its next checkpoint. */
  data object IntentRecorded : ControlResult

  /** A goal stop was recorded and the runner's exit was confirmed. */
  data object ExitConfirmed : ControlResult

  /** A goal stop signal was sent, but the runner's exit could not be confirmed; it may still be running. */
  data object TerminationAttempted : ControlResult

  /** The item no longer identifies a controllable target. */
  data object Stale : ControlResult
  data class LaunchFailed(val reason: String) : ControlResult
  data class Rejected(val reasonCode: String, val refusal: ControlRefusal) : ControlResult
}

/** The selected database setting does not name a valid file path. */
class InvalidDatabasePathException(cause: Throwable?) : RuntimeException("The database path is invalid.", cause)

/**
 * The supported runtime projection and control surface. Reads never create, migrate or repair a store;
 * an outdated store is a typed incompatible-database failure.
 */
interface RuntimeWorkGateway {
  /** The resolved database file; throws [InvalidDatabasePathException] when the selection is not a valid path. */
  fun databasePath(): String

  /**
   * Applies the runtime's supported migrations to an existing older store when it is selected, so its saved work opens. A missing
   * store stays absent, and a newer or unrecognized schema is refused unchanged.
   */
  fun upgradeExistingStore()

  fun observation(): StoreObservation

  /** Runs [read] in one snapshot of the existing store; a missing store is a typed failure. */
  fun <T : Any> existingSnapshot(read: SnapshotReads.() -> T): T

  fun observeOwners(ids: List<String>, observedAt: Instant, onExit: () -> Unit): OwnerRegistration

  fun request(item: WorkItem, action: ControlAction, agentId: String?, stillCurrent: () -> Boolean): ControlResult
}
