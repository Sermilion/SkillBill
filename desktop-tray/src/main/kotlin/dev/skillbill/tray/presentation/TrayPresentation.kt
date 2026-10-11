package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.application.project
import dev.skillbill.tray.domain.*
import java.time.Instant

data class PopoverView(
  val pollAgeSeconds: Long?,
  val running: Int,
  val needsYou: Int,
  val done24h: Int,
  val goals: List<WorkItem>,
  val phases: List<WorkItem>,
  val livePhases: List<WorkItem>,
  val finishedPhases: List<WorkItem>,
  val problem: Problem?,
  val stale: Boolean,
  val attention: Boolean,
  val selectedTab: String,
)

fun presentPopover(state: DashboardState, now: Instant): PopoverView {
  val board = state.board
  val projected = board?.let { project(it, now) }
  return PopoverView(
    pollAgeSeconds = board?.let { java.time.Duration.between(it.observedAt, now).seconds.coerceAtLeast(0) },
    running = projected?.running ?: 0,
    needsYou = projected?.needsYou ?: 0,
    done24h = projected?.done24h ?: 0,
    goals = projected?.goals.orEmpty(),
    phases = projected?.phases.orEmpty(),
    livePhases = projected?.livePhases.orEmpty(),
    finishedPhases = projected?.finishedPhases.orEmpty(),
    problem = state.problem ?: state.trayProblem ?: board?.problem,
    stale = state.stale,
    attention = (projected?.needsYou ?: 0) > 0,
    selectedTab = state.settings.lastPopoverTab,
  )
}

enum class TrayStateKind { IDLE, RUNNING, NEEDS_YOU, FAILED }
data class TrayState(val kind: TrayStateKind, val running: Int)

fun presentTrayState(state: DashboardState): TrayState {
  val projection = state.board?.let { project(it, it.observedAt) }
  val failed = state.board?.items?.any { it.lifecycle == Lifecycle.FAILED } == true
  val kind = when {
    failed -> TrayStateKind.FAILED
    (projection?.needsYou ?: 0) > 0 -> TrayStateKind.NEEDS_YOU
    (projection?.running ?: 0) > 0 -> TrayStateKind.RUNNING
    else -> TrayStateKind.IDLE
  }
  return TrayState(kind, projection?.running ?: 0)
}

fun effectiveFreshness(item: WorkItem, boardStale: Boolean, detailExtrasStale: Boolean = false): Freshness =
  if (boardStale || detailExtrasStale || item.freshness == Freshness.STALE) Freshness.STALE else item.freshness
