package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.BoardSection
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.time.Instant

/** A board row with plain defaults, so a test sets only the fields it asserts on. */
fun workItem(
  id: String,
  kind: WorkKind? = WorkKind.Goal,
  issueKey: String? = if (kind == WorkKind.Goal) id else null,
  lifecycle: Lifecycle = Lifecycle.Active,
  finishedAt: Instant? = null,
  pauseEligible: Boolean = true,
  pauseRequested: Boolean = false,
  reason: String? = null,
) = WorkItem(
  id = id,
  kind = kind,
  issueKey = issueKey,
  title = "Title $id",
  summary = "",
  lifecycle = lifecycle,
  planningPrepared = true,
  stalledSince = null,
  pauseRequested = pauseRequested,
  fresh = true,
  stepLabel = "implement",
  phaseExecution = null,
  phaseExecutionLabel = null,
  progress = null,
  planningProgress = null,
  activeDurationMs = null,
  activeDurationAsOf = null,
  startedAt = null,
  finishedAt = finishedAt,
  model = null,
  effort = null,
  agentId = null,
  branch = null,
  repositoryName = "skill-bill",
  repositoryRoot = "/work/skill-bill",
  liveSection = null,
  displayGroup = null,
  displayPriority = 0,
  requiresAttention = false,
  controlEligible = true,
  pauseEligible = pauseEligible,
  running = finishedAt == null,
  recentlyFinishedPhase = false,
  doneWithinDay = false,
  freshnessDeadline = null,
  membershipDeadline = null,
  exactPauseReason = null,
  awaitsOperatorDecision = false,
  currentSubtaskId = null,
  reason = reason,
  control = WorkControlIdentity(
    sessionId = 1L, workflowId = id, invocationId = null, repositoryIdentity = "skill-bill", executionId = null, statusStoreId = null,
    runSequence = null, statusRevision = null, phaseId = null, updatedAt = Instant.fromEpochMilliseconds(0), controlLeaseOwner = null,
    controlLeaseGeneration = null, storeId = null, storeRevision = null, databaseFile = null,
  ),
)

/** A loaded board with [sections] filled as given, every other section empty, and [items] in the given order. */
fun loadedBoard(
  sections: Map<BoardSectionKind, List<WorkItem>> = emptyMap(),
  items: List<WorkItem> = sections.values.flatten(),
  nextCursor: String? = null,
  stale: Boolean = false,
) = BoardState.Loaded(
  sections = BoardSectionKind.entries.map { BoardSection(it, sections[it].orEmpty()) },
  counts = BoardCounts(TrayStatus.Idle, 0, 0, 0, 0, 0, 0, 0, 0),
  nextCursor = nextCursor,
  stale = stale,
  items = items,
)
