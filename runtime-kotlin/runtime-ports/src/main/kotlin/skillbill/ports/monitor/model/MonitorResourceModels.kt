package skillbill.ports.monitor.model

import java.nio.file.Path

data class MonitorRepositoryContext(
  val callerRoot: Path,
  val primaryRoot: Path,
  val commonDir: Path,
)

sealed interface MonitorContextResolution {
  data class Resolved(val context: MonitorRepositoryContext) : MonitorContextResolution

  data class Unavailable(val reason: String) : MonitorContextResolution
}

data class MonitorFence(
  val directory: Path,
  val ownerToken: String,
  val generation: Long,
)

data class MonitorAcquireRequest(
  val context: MonitorRepositoryContext,
  val target: MonitorResolvedTarget,
  val invocationId: String,
  val ownerToken: String,
)

enum class MonitorOwnershipState(val wireValue: String) {
  ACQUIRING("acquiring"),
  PREPARED("prepared"),
  RETAINED("retained"),
  RELEASED("released"),
  ;

  companion object {
    fun fromWire(value: String?): MonitorOwnershipState? = entries.firstOrNull { it.wireValue == value }
  }
}

data class MonitorOwnerSummary(
  val ownerToken: String,
  val invocationId: String,
  val hostIdentity: String,
  val pid: Long,
  val state: MonitorOwnershipState,
)

enum class MonitorAdmissionBlock { LIVE_OWNER, STALE_OWNER, RETAINED_WORK, CORRUPT_OWNERSHIP }

sealed interface MonitorAcquisition {
  data class Acquired(val fence: MonitorFence) : MonitorAcquisition

  data class Blocked(
    val reason: MonitorAdmissionBlock,
    val path: Path,
    val detail: String,
    val owner: MonitorOwnerSummary?,
  ) : MonitorAcquisition
}

data class MonitorFetchedRefs(
  val headRef: String,
  val baseRef: String,
)

enum class MonitorFetchRejection { HEAD_UNAVAILABLE, HEAD_MOVED, BASE_UNAVAILABLE, BASE_MOVED }

sealed interface MonitorFetchResult {
  data class Fetched(
    val refs: MonitorFetchedRefs,
    val baseSha: String,
  ) : MonitorFetchResult

  data class Rejected(
    val reason: MonitorFetchRejection,
    val detail: String,
  ) : MonitorFetchResult
}

data class MonitorWorktree(
  val path: Path,
  val branch: String,
)

enum class MonitorRetentionReason(val wireValue: String) {
  DIRTY_WORKTREE("dirty_worktree"),
  UNTRACKED_FILES("untracked_files"),
  UNPUBLISHED_COMMITS("unpublished_commits"),
  PUBLICATION_UNKNOWN("publication_unknown"),
  UNOWNED_RESOURCE("unowned_resource"),
  CLEANUP_FAILED("cleanup_failed"),
  ;

  companion object {
    fun fromWire(value: String?): MonitorRetentionReason? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class MonitorOutcomeKind(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  SETUP_FAILED("setup_failed"),
  RUN_FAILED("run_failed"),
  CANCELLED("cancelled"),
  ABANDONED("abandoned"),
  ;

  companion object {
    fun fromWire(value: String?): MonitorOutcomeKind? = entries.firstOrNull { it.wireValue == value }
  }
}

data class MonitorPrimaryOutcome(
  val kind: MonitorOutcomeKind,
  val detail: String,
)

data class MonitorSettleRequest(
  val fence: MonitorFence,
  val outcome: MonitorPrimaryOutcome,
  val removalAllowed: Boolean = true,
)

sealed interface MonitorSettlement {
  data object Released : MonitorSettlement

  data class Retained(
    val reason: MonitorRetentionReason,
    val path: Path?,
    val detail: String,
  ) : MonitorSettlement

  data class FenceLost(val detail: String) : MonitorSettlement
}

data class MonitorExpectedOwnerToken(val value: String)

data class MonitorStaleRecoveryRequest(
  val context: MonitorRepositoryContext,
  val pullRequest: MonitorPullRequestIdentity,
  val expectedOwner: MonitorExpectedOwnerToken,
)

enum class MonitorRecoveryRefusal {
  NO_OWNERSHIP,
  TOKEN_MISMATCH,
  LIVE_OWNER,
  UNKNOWN_OWNER,
  RETAINED_UNFINISHED,
  CORRUPT_OWNERSHIP,
}

sealed interface MonitorStaleRecovery {
  data class Recovered(val ownerToken: String) : MonitorStaleRecovery

  data class Refused(
    val reason: MonitorRecoveryRefusal,
    val path: Path,
    val detail: String,
  ) : MonitorStaleRecovery
}
