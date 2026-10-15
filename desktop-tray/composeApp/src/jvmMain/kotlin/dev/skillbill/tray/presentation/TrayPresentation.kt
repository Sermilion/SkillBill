package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.core.domain.model.*

data class PopoverView(
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

/** Renders the runtime's arrangement of the board: identities are looked up on the board, their order is kept. */
fun presentPopover(state: DashboardState): PopoverView {
  val board = state.board
  val arrangement = state.arrangement
  val byIdentity = board?.items.orEmpty().associateBy { it.identity }
  fun List<WorkIdentity>.items(): List<WorkItem> = mapNotNull(byIdentity::get)
  val livePhases = arrangement?.livePhases?.items().orEmpty()
  val finishedPhases = arrangement?.finishedPhases?.items().orEmpty()
  return PopoverView(
    running = arrangement?.running ?: 0,
    needsYou = arrangement?.needsYou ?: 0,
    done24h = arrangement?.doneWithinDay ?: 0,
    goals = arrangement?.goals?.items().orEmpty(),
    phases = livePhases + finishedPhases,
    livePhases = livePhases,
    finishedPhases = finishedPhases,
    problem = state.problem ?: state.trayProblem ?: board?.problem,
    stale = state.stale,
    attention = (arrangement?.needsYou ?: 0) > 0,
    selectedTab = state.settings.lastPopoverTab,
  )
}

enum class TrayStateKind { IDLE, RUNNING, NEEDS_YOU, FAILED }
data class TrayState(val kind: TrayStateKind, val running: Int)

fun presentTrayState(state: DashboardState): TrayState {
  val arrangement = state.arrangement ?: return TrayState(TrayStateKind.IDLE, 0)
  return TrayState(TrayStateKind.valueOf(arrangement.trayStatus.name), arrangement.running)
}

fun effectiveFreshness(item: WorkItem, boardStale: Boolean, detailExtrasStale: Boolean = false): Freshness =
  if (boardStale || detailExtrasStale || item.freshness == Freshness.STALE) Freshness.STALE else item.freshness
