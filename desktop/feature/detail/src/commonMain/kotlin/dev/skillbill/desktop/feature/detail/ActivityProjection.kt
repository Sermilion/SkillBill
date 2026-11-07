package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
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
    item.startedAt, finishedAt(item), phases, blocks,
    after = detail.executionHistory.flatMap { it.reviewReport?.findingActivity.orEmpty() }.sortedBy { it.at }))
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

internal data class PlanActivityEvent(
  val key: String,
  val at: Instant?,
  val text: String,
  val phaseId: String = "preplan",
  val kind: String = "phase",
  val updatedAt: Instant? = at,
  val path: String? = null,
  val state: String = "completed",
  val durationMs: Long? = null,
  val exitCode: Int? = null,
  val subtaskId: Int? = null,
  val agentId: String? = null,
  val children: List<PlanActivityEvent> = emptyList(),
  val openPath: String? = null,
  val verb: String? = null,
)

internal enum class PlanActivityFilter(val label: String) { All("All"), Files("Files"), Commands("Commands"), Notes("Notes") }

internal fun planActivity(item: WorkItem, detail: Detail): List<PlanActivityEvent> {
  val plan = detail.standalonePlan
  val roots = planRoots(item)
  val views = planStageViews(item, detail)
  return buildList {
    plan?.stages.orEmpty().forEach { stage ->
      stage.firstStartedAt?.let { add(PlanActivityEvent("${stage.phaseId}:start", it, "Phase started", stage.phaseId)) }
      val finishedAt = stage.finishedAt
      if (finishedAt != null) {
        val duration = stage.firstStartedAt?.let { (finishedAt - it).inWholeMilliseconds }
        add(PlanActivityEvent("${stage.phaseId}:finish", finishedAt, if (stage.status == "completed") "Phase finished" else "Phase ${stage.status ?: "stopped"}", stage.phaseId, durationMs = duration))
      }
    }
    plan?.waves.orEmpty().forEachIndexed { index, wave ->
      add(PlanActivityEvent("wave-$index", wave.startedAt, "${wave.number?.let { "Wave $it" } ?: "Planning wave"} started: subtasks ${wave.subtaskIds.joinToString(", ")}", "plan_subtasks"))
    }
    detail.planActivity.filterNot { it.kind == "stream" }.forEach { event ->
      val openPath = event.path?.let { if (it.startsWith("/") || it.getOrNull(1) == ':') it else "${item.checkoutRoot.trimEnd('/')}/$it" }
      val parsed = planOutputText(event.text, roots)
      val reparsed = event.kind in setOf("note", "tool") && parsed.kind != "note"
      val kind = if (reparsed) parsed.kind else event.kind
      val displayPath = event.path?.let { shortenPlanPath(it, roots) }
      val text = if ((kind == "read" || kind == "write") && !event.path.isNullOrBlank()) {
        displayPath.orEmpty()
      } else if (parsed.subject.isBlank()) event.text else parsed.subject
      add(PlanActivityEvent(event.id, event.at, text, event.phaseId, kind, event.updatedAt, displayPath, event.state, event.durationMs, event.exitCode, event.subtaskId ?: parsed.subtaskId, event.agentId, openPath = openPath, verb = parsed.verb.takeIf { it.isNotBlank() }))
    }
    val streaming = detail.planActivity.filter { it.kind == "stream" }.map { it.phaseId }.toSet()
    plan?.units.orEmpty().filter { it.state == "saved" && "plan_subtasks" !in streaming }.forEach { unit ->
      add(PlanActivityEvent("saved-${unit.subtaskId}", unit.finishedAt, shortenPlanPath(unit.specPath, roots), "plan_subtasks", "write", path = shortenPlanPath(unit.specPath, roots), subtaskId = unit.subtaskId, openPath = unit.specPath))
    }
    if ("plan" !in streaming) plan?.report?.bundleFiles.orEmpty().filter { fileName(it) in setOf("spec.md", "decomposition-manifest.yaml", "decomposition-manifest.yml") }.forEach { path ->
      add(PlanActivityEvent("saved-$path", null, shortenPlanPath(path, roots), "plan", "write", path = shortenPlanPath(path, roots), openPath = path))
    }
    (listOfNotNull(detail.incident) + detail.incidentHistory).distinctBy { it.incidentToken }.forEach { incident ->
      val phase = (views.firstOrNull { it.state == PlanStageState.Blocked } ?: views.firstOrNull { it.state == PlanStageState.Running || it.state == PlanStageState.Paused })?.phaseId ?: "plan_subtasks"
      add(PlanActivityEvent("incident-${incident.incidentToken}", incident.observedAt, incident.cause ?: incident.summary, phase, "error", state = "failed"))
    }
  }.sortedBy { it.at ?: Instant.DISTANT_FUTURE }
}

internal fun planActivityRows(events: List<PlanActivityEvent>, filter: PlanActivityFilter): List<PlanActivityEvent> {
  val selected = events.filter { event -> when (filter) {
    PlanActivityFilter.All -> true
    PlanActivityFilter.Files -> event.kind in setOf("read", "write", "search")
    PlanActivityFilter.Commands -> event.kind in setOf("command", "mcp", "tool")
    PlanActivityFilter.Notes -> event.kind == "note"
  } }
  val rows = mutableListOf<PlanActivityEvent>()
  selected.forEach { event ->
    val previous = rows.lastOrNull()
    if (event.kind == "read" && previous?.kind == "read" && event.phaseId == previous.phaseId && event.subtaskId == previous.subtaskId && event.agentId == previous.agentId && event.state == "completed" && previous.state == "completed") {
      rows[rows.lastIndex] = previous.copy(children = previous.children + event)
    } else rows += event
  }
  return rows
}

internal fun planActivityVerb(event: PlanActivityEvent): String = if (event.state == "failed") {
  when (event.kind) { "read" -> "Read failed"; "write" -> "Write failed"; "search" -> "Search failed"; "command" -> "Command failed"; "mcp", "tool" -> "Tool failed"; "error" -> if (event.key.startsWith("incident-")) "Blocked" else "Error"; else -> "" }
} else when (event.kind) {
  "read" -> if (event.state == "started") "Reading" else "Read"
  "write" -> if (event.state == "started") "Writing" else "Wrote"
  "search" -> if (event.state == "started") "Searching" else "Searched"
  "command" -> if (event.state == "started") "Running" else "Ran"
  "mcp" -> if (event.state == "started") "Calling" else "Called"
  "tool" -> "Tool"
  "error" -> if (event.key.startsWith("incident-")) "Blocked" else "Error"
  else -> ""
}

internal fun planActivityGlyph(event: PlanActivityEvent): ImageVector = when {
  event.state == "failed" || event.kind == "error" -> LucideIcons.warn
  event.kind == "write" -> LucideIcons.pencil
  event.kind == "read" -> LucideIcons.file
  event.kind == "search" -> LucideIcons.search
  event.kind == "command" || event.kind == "tool" -> LucideIcons.terminal
  event.kind == "mcp" -> LucideIcons.plug
  event.kind == "phase" -> LucideIcons.play
  else -> LucideIcons.messageSquare
}

internal fun planActivityTone(event: PlanActivityEvent, colors: SkillBillColors): Color = when {
  event.state == "failed" || event.kind == "error" -> colors.stBlocked
  event.kind == "write" -> colors.stDone
  event.kind == "command" || event.kind == "tool" -> colors.data3
  event.kind == "mcp" -> colors.data2
  event.kind == "phase" -> colors.stActive
  else -> colors.fgFaint
}

internal fun planActivityNewestKey(events: List<PlanActivityEvent>): String? = events.withIndex()
  .filter { it.value.at != null }
  .maxWithOrNull(compareBy<IndexedValue<PlanActivityEvent>> { it.value.at }.thenBy { it.index })
  ?.value?.key
