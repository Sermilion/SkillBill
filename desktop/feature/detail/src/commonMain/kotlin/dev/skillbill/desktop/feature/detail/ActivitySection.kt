package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.detail.ActivityFeed
import dev.skillbill.desktop.core.designsystem.detail.ActivityFeedEntry
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.status.statusKind
import kotlin.time.Instant

private const val TIME = "d MMM HH:mm"

/** The 16 px icon an activity line leads with: the agent's small Avatar, or a StatusIcon. */
internal sealed interface ActivityLead {
  data class Agent(val id: String) : ActivityLead

  data class Status(val kind: StatusKind, val progress: Float?) : ActivityLead
}

/** One recorded fact as the feed words it. */
internal data class ActivityLine(
  val lead: ActivityLead,
  val actor: String,
  val action: String?,
  val target: String? = null,
  val mono: Boolean = false,
  val at: Instant?,
  val tooltip: String? = null,
)

/** screens.js `EXEC_STATE`: the StatusIcon an execution's lifecycle state ends with. */
private fun executionStatus(lifecycleState: String): StatusKind = when (lifecycleState.lowercase()) {
  "completed", "complete", "terminal" -> StatusKind.Completed
  "failed" -> StatusKind.Failed
  "interrupted", "paused" -> StatusKind.Paused
  "blocked" -> StatusKind.Blocked
  "canceled", "cancelled" -> StatusKind.Canceled
  "active", "running" -> StatusKind.Active
  else -> StatusKind.Pending
}

/**
 * HANDOFF 9.2 item 11: the Activity feed from recorded facts only, oldest first; a fact with no time sorts last. Runs are
 * numbered by start time; one execution reads "Goal run" for a goal, else the phase or operation id.
 */
internal fun activityLines(item: WorkItem, detail: Detail): List<ActivityLine> {
  val self = if (item.kind == WorkKind.Goal) "Goal" else rowKey(item)
  val agent = detail.activeAgent ?: item.agentId
  val agentLead = agent?.let { ActivityLead.Agent(it) } ?: ActivityLead.Status(item.statusKind(), null)
  val itemLead = ActivityLead.Status(item.statusKind(), null)
  val lines = buildList {
    val runs = detail.executionHistory.sortedBy { it.startedAt }
    runs.forEachIndexed { index, run ->
      val who = when {
        runs.size > 1 -> "Run ${index + 1}"
        item.kind == WorkKind.Goal -> "Goal run"
        else -> self
      }
      add(ActivityLine(ActivityLead.Status(StatusKind.Active, 0.12f), who, "started", at = run.startedAt, tooltip = "$who started ${formatInstant(run.startedAt, TIME)} · ${run.invocationId}"))
      run.finishedAt?.let { end ->
        val state = run.lifecycleState.lowercase()
        val tooltip = listOfNotNull("$who $state ${formatInstant(end, TIME)}", run.result, run.invocationId).joinToString(" · ")
        add(ActivityLine(ActivityLead.Status(executionStatus(state), 1f), who, state, run.result, mono = true, at = end, tooltip = tooltip))
      }
    }
    if (runs.isEmpty()) {
      item.startedAt?.let { start ->
        add(ActivityLine(ActivityLead.Status(StatusKind.Active, 0.12f), self, "started", at = start, tooltip = "$self started ${formatInstant(start, TIME)}"))
      }
      finishedAt(item)?.let { end ->
        val word = lifecycleLabel(item.lifecycle).lowercase()
        add(ActivityLine(itemLead, self, word, at = end, tooltip = "$self $word ${formatInstant(end, TIME)}"))
      }
    }
    detail.latestActivity.edit?.let { edit ->
      val change = listOfNotNull(edit.insertions?.let { "+$it" }, edit.deletions?.let { "−$it" }).joinToString(" ")
      val target = listOf(edit.paths.joinToString(", "), change).filter { it.isNotBlank() }.joinToString(" ")
      add(ActivityLine(agentLead, agent ?: "Agent", "edited", target.ifBlank { null }, mono = true, at = edit.recordedAt))
    }
    detail.latestActivity.event?.let { event ->
      val actor = listOfNotNull(event.workflowPhase, event.workerRole).filter { it.isNotBlank() }.joinToString(" · ")
      if (actor.isNotBlank() || !event.summary.isNullOrBlank()) add(ActivityLine(itemLead, actor, event.summary, at = event.at))
    }
    detail.lastAgentActivityLabel?.let { label ->
      add(ActivityLine(agentLead, agent ?: "Agent", label, at = detail.lastAgentActivityAt))
    }
    if (isEmpty() && finishedAt(item) == null && item.summary.isNotBlank()) add(ActivityLine(itemLead, self, item.summary, at = null))
  }
  return lines.sortedBy { it.at ?: Instant.DISTANT_FUTURE }
}

private fun ActivityLine.toEntry() = ActivityFeedEntry(
  lead = {
    when (val icon = lead) {
      is ActivityLead.Agent -> Avatar(icon.id, small = true, tooltip = null)
      is ActivityLead.Status -> StatusIcon(icon.kind, icon.progress)
    }
  },
  actor = actor,
  action = action,
  target = target,
  monoTarget = mono,
  at = at,
  tooltip = tooltip,
)

/** The Activity section; executions past the first page load as the feed's end comes into view. */
internal fun DetailSections.activitySection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val lines = activityLines(item, detail)
  if (lines.isEmpty()) return
  section("activity", "Activity", count = lines.size.toString()) {
    item(key = "activity-feed") {
      // Saveable, so "Show N earlier" stays open when the feed scrolls out of the lazy document and back.
      var showAll by rememberSaveable(item.id) { mutableStateOf(false) }
      ActivityFeed(lines.map { it.toEntry() }, showAll, onShowEarlier = { showAll = true })
    }
    pageFooter(DetailList.Executions, state, onAction)
  }
}
