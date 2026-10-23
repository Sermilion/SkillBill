package dev.skillbill.tray.core.ui

import androidx.compose.ui.graphics.Color
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.red
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkPhase
import dev.skillbill.tray.core.domain.model.stalled
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

fun age(duration: Duration): String = duration.toComponents { hours, minutes, seconds, _ ->
  when {
    hours > 0 -> "${hours}h ${minutes}m"
    minutes > 0 -> "${minutes}m ${seconds}s"
    else -> "${seconds.coerceAtLeast(0)}s"
  }
}

fun Duration.coerceNonNegative(): Duration = if (isNegative()) Duration.ZERO else this

fun PhaseExecution.label(): String = when (kind) {
  PhaseExecutionKind.PASS -> "pass $count"
  PhaseExecutionKind.SEMANTIC_LOOP -> "loop $count"
  PhaseExecutionKind.GATE_RUN -> "gate run $count"
  PhaseExecutionKind.BOUNDED_EDGE -> total?.let { "$count/$it" } ?: "edge $count"
  PhaseExecutionKind.ATTEMPT -> if (phaseId in acceptanceAuditPhases) "repair attempt $count" else "run $count"
}

private val acceptanceAuditPhases = setOf("audit", "audit_plan_fix", "audit_implement_fix")

/** A running clock that ticks forward from its last fresh reading and never runs backwards; a stale clock is frozen. */
fun displayedDuration(baseMs: Long?, asOf: Instant?, now: Instant, freshness: Freshness, live: Boolean): Long? {
  if (baseMs == null) return null
  if (!live || freshness != Freshness.FRESH || asOf == null) return baseMs
  return (baseMs + (now - asOf).inWholeMilliseconds.coerceAtLeast(0)).coerceAtLeast(baseMs)
}

fun durationText(value: Long?, asOf: Instant?, item: WorkItem, now: Instant, stale: Boolean, frozen: Long? = null): String {
  val milliseconds = if (stale) {
    frozen ?: value
  } else {
    value?.let { displayedDuration(it, asOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE) }
  }
  return milliseconds?.let { age(it.milliseconds) } ?: "not available"
}

fun phaseElapsedText(item: WorkItem, now: Instant, stale: Boolean): String {
  val start = item.startedAt ?: return "not available"
  val end = item.finishedAt ?: if (!stale && item.freshness == Freshness.FRESH && item.lifecycle == Lifecycle.ACTIVE) now else item.updatedAt
  return age((end - start).coerceNonNegative())
}

fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

fun WorkItem.repositoryTitle(): String = repositoryName ?: fileName(repositoryRoot)

fun WorkItem.progressLabel(): String? = progressTotal?.let { total ->
  "${currentSubtaskId ?: progressCompleted ?: 0}/$total"
}

fun PhaseExecution.phaseLabel(): String =
  criterionRef?.let { "$phaseId · AC-${it.removePrefix("AC-").toInt()}" } ?: phaseId

/** Stale if the surrounding read is stale, the detail's extras failed to refresh, or the runtime itself judged it stale. */
fun effectiveFreshness(item: WorkItem, readStale: Boolean, detailExtrasStale: Boolean = false): Freshness =
  if (readStale || detailExtrasStale || item.freshness == Freshness.STALE) Freshness.STALE else item.freshness

/** The phase pipeline with the phases not loaded yet filled in, so the active phase stays visible beyond the first page. */
fun phasePipeline(detail: Detail): List<WorkPhase> {
  val loaded = detail.phases.associateBy { it.id }
  val current = detail.item.currentPhaseId ?: detail.currentPhase
  val ids = (detail.stepPipeline + loaded.keys + listOfNotNull(current)).distinct()
  return ids.map { id ->
    loaded[id] ?: WorkPhase(
      id, if (id == current) "in_progress" else "pending",
      detail.item.phaseExecution?.takeIf { it.phaseId == id }?.count ?: 0, false,
    )
  }
}

/** The status color of an item: amber while the runtime reports the active run stalled, otherwise its lifecycle color. */
fun WorkItem.statusColor(): Color = if (stalled()) amber else lifecycleColor(lifecycle)

/** The Liveness value: the runtime's liveness word, or how long a stalled run has gone without progress. */
fun livenessText(liveness: String, item: WorkItem, now: Instant): String =
  item.stalledSince?.takeIf { item.stalled() }
    ?.let { "stalled · no progress for ${age((now - it).coerceNonNegative())}" }
    ?: liveness

fun lifecycleColor(lifecycle: Lifecycle): Color = when (lifecycle) {
  Lifecycle.ACTIVE -> green
  Lifecycle.BLOCKED -> orange
  Lifecycle.FAILED -> red
  Lifecycle.CANCELED -> Color(0xFFB59AF4)
  else -> muted
}
