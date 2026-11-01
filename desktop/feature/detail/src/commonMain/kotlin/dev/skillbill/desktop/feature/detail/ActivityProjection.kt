package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal val activityLongGap = 10.minutes

internal sealed interface ActivityEntry {
  data class Phases(val phases: List<ActivityPhase>) : ActivityEntry
  data class Block(val block: ActivityBlock) : ActivityEntry
  data class Wait(val milliseconds: Long) : ActivityEntry
}

internal fun activityEntries(group: ActivityGroup): List<ActivityEntry> {
  val events = (group.phases.map { ActivityEntry.Phases(listOf(it)) } + group.blocks.map { ActivityEntry.Block(it) })
    .sortedBy { entry -> when (entry) {
      is ActivityEntry.Phases -> entry.phases.first().startedAt ?: Instant.DISTANT_FUTURE
      is ActivityEntry.Block -> entry.block.at
      is ActivityEntry.Wait -> Instant.DISTANT_FUTURE
    } }
  val result = mutableListOf<ActivityEntry>()
  var previousEnd: Instant? = null
  events.forEach { event ->
    val phase = (event as? ActivityEntry.Phases)?.phases?.single()
    val start = phase?.startedAt ?: (event as? ActivityEntry.Block)?.block?.at
    val gap = start?.let { at -> previousEnd?.let { at - it } }
    if (phase != null && gap != null && gap > activityLongGap) result += ActivityEntry.Wait(gap.inWholeMilliseconds)
    val previous = result.lastOrNull() as? ActivityEntry.Phases
    val family = phase?.phaseId?.let(::activityLoopFamily)
    if (phase != null && phase.finishedAt != null && previous != null && family != null &&
      previous.phases.all { activityLoopFamily(it.phaseId) == family && it.finishedAt != null }) {
      result[result.lastIndex] = ActivityEntry.Phases(previous.phases + phase)
    } else result += event
    previousEnd = phase?.finishedAt ?: previousEnd
  }
  return result
}

internal fun activityLoopFamily(phase: String): String? = when (phase) {
  "audit", "audit_plan_fix", "audit_implement_fix" -> "audit"
  "review", "verify_findings", "review_fix" -> "review"
  "validate", "validate_fix" -> "validate"
  "monitor", "monitor_fix" -> "monitor"
  "qa", "qa_fix" -> "qa"
  else -> null
}

internal fun ActivityGroup.activeMilliseconds(now: Instant): Long = phases.sumOf { phase ->
  val start = phase.startedAt ?: return@sumOf 0L
  val end = phase.finishedAt ?: now.takeIf { status == ActivityStatus.RUNNING } ?: return@sumOf 0L
  (end - start).inWholeMilliseconds.coerceAtLeast(0)
}

internal fun activityDuration(milliseconds: Long): String {
  if (milliseconds < 1_000) return "<1s"
  val seconds = (milliseconds + 500) / 1_000
  if (seconds < 60) return "${seconds}s"
  val minutes = (seconds + 30) / 60
  if (minutes < 60) return "${minutes}m"
  return "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

internal data class ActivitySpan(val groupId: String?, val start: Instant, val end: Instant, val milliseconds: Long, val block: Boolean = false, val running: Boolean = false)

internal fun activitySpans(groups: List<ActivityGroup>, now: Instant): List<ActivitySpan> {
  val spans = mutableListOf<ActivitySpan>()
  groups.forEach { group ->
    val work = mutableListOf<ActivitySpan>()
    group.phases.sortedBy { it.startedAt }.forEach { phase ->
      val start = phase.startedAt ?: return@forEach
      val end = phase.finishedAt ?: now.takeIf { group.status == ActivityStatus.RUNNING } ?: return@forEach
      val previous = work.lastOrNull()
      val duration = (end - start).inWholeMilliseconds.coerceAtLeast(0)
      if (previous != null && start - previous.end <= activityLongGap) {
        work[work.lastIndex] = previous.copy(end = maxOf(end, previous.end), milliseconds = previous.milliseconds + duration)
      } else work += ActivitySpan(group.id, start, end, duration)
    }
    spans += work
    spans += group.blocks.map { ActivitySpan(group.id, it.at, it.at, 0, block = true) }
    if (group.status == ActivityStatus.RUNNING && (work.isNotEmpty() || group.after.any { it.running })) spans += ActivitySpan(group.id, now, now, 0, running = true)
  }
  val ordered = spans.sortedBy { it.start }
  val result = mutableListOf<ActivitySpan>()
  var end: Instant? = null
  ordered.forEach { span ->
    val previous = end
    if (previous != null && span.groupId != null && groups.first { it.id == span.groupId }.subtaskId != null && span.start - previous > activityLongGap) {
      result += ActivitySpan(null, previous, span.start, (span.start - previous).inWholeMilliseconds)
    }
    result += span
    end = maxOf(end ?: span.end, span.end)
  }
  return result
}

internal fun standaloneActivity(item: WorkItem, detail: Detail): List<ActivityGroup> {
  val phases = detail.executionHistory.sortedBy { it.startedAt }.mapIndexed { index, run ->
    ActivityPhase(item.control.phaseId ?: detail.currentPhase ?: rowKey(item), index + 1, run.startedAt, run.finishedAt,
      detail.activeAgent ?: item.agentId, null, null, run.reviewReport?.summary ?: run.result, run.reviewReport?.verdict)
  }.ifEmpty {
    detail.phases.map { phase -> ActivityPhase(phase.id, phase.attemptCount, null, null, null, null, null, null, phase.status) }
  }
  val blocks = (listOfNotNull(detail.incident) + detail.incidentHistory).distinctBy { it.incidentToken }.map { incident ->
    ActivityBlock(incident.incidentToken, incident.observedAt, ActivityBlockKind.BLOCKED, null, null, incident.cause ?: incident.summary, false)
  }
  return listOf(ActivityGroup(item.id, null, rowKey(item),
    if (item.running) ActivityStatus.RUNNING else if (detail.incident != null) ActivityStatus.BLOCKED else ActivityStatus.COMPLETE,
    item.startedAt, finishedAt(item), phases, blocks))
}

internal fun activityIdleMilliseconds(groups: List<ActivityGroup>, now: Instant): Long {
  val spans = activitySpans(groups, now).filter { it.groupId != null && !it.block && !it.running }
  var end: Instant? = null
  var idle = 0L
  spans.forEach { span ->
    end?.let { previous -> if (span.start - previous > activityLongGap) idle += (span.start - previous).inWholeMilliseconds }
    end = maxOf(end ?: span.end, span.end)
  }
  return idle
}
