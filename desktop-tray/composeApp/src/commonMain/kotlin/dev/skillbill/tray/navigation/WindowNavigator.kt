package dev.skillbill.tray.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.savedstate.SavedState
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.feature.detail.detailIdentity
import dev.skillbill.tray.feature.detail.historyDetailRoute
import dev.skillbill.tray.feature.detail.workDetailRoute
import dev.skillbill.tray.feature.history.HistoryRoute
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.WorkRoute

/** The list sections a main window switches between. */
enum class Section { WORK, HISTORY }

/**
 * What a section keeps while it is not the one on screen, and across its list entry being rebuilt: the filter text, the
 * selected detail and the list's scroll anchor. It is plain window state, never a ViewModel, so it outlives the entry.
 */
class SectionMemory {
  var filter: String by mutableStateOf("")
  var selection: AppRoute? = null
  var scrollIndex: Int = 0
  var scrollOffset: Int = 0
}

/**
 * What the list entry on screen last reported about its board, for the panes that cannot see its ViewModel: the empty detail
 * explains it, and the banner above the detail offers its retry.
 */
data class ListStatus(
  val loading: Boolean = true,
  val failed: Boolean = false,
  val hasMore: Boolean = false,
  val problem: Problem? = null,
  val stale: Boolean = false,
  val onRefresh: () -> Unit = {},
)

/**
 * One window's navigation: the typed [stack] and each section's [SectionMemory]. Routes are made under the database
 * generation [generation] reports; while it reports none (a database switch is under way) nothing that names work is opened.
 * [reset] throws the stack and every memory away, which is what a database switch does before the new session renders.
 */
class WindowNavigator(private val generation: () -> DatabaseGenerationRef?, initial: List<AppRoute>) {
  val stack = RouteBackStack(initial.first()).also { it.replaceAll(initial) }
  private val memories = mutableMapOf<Section, SectionMemory>()

  var status: ListStatus by mutableStateOf(ListStatus())

  /** Counts every detail opened, re-opening the one already shown included, so a reader can tell a re-pick from no change. */
  var picks: Int by mutableIntStateOf(0)
    private set

  /** The item whose detail is open, or null when only the list shows. */
  val selected: WorkIdentity? get() = stack.top.detailIdentity()

  /** The section whose list is the root of the stack; null for a window rooted at settings. */
  val section: Section? get() = when (stack.root) {
    is WorkRoute -> Section.WORK
    is HistoryRoute -> Section.HISTORY
    else -> null
  }

  fun memory(section: Section): SectionMemory = memories.getOrPut(section) { SectionMemory() }

  /** Opens [identity]'s detail in the current section. Returns false when nothing can open it now. */
  fun openDetail(identity: WorkIdentity): Boolean {
    val ref = generation() ?: return false
    val route = when (stack.root) {
      is WorkRoute -> workDetailRoute(identity, ref)
      is HistoryRoute -> historyDetailRoute(identity, ref)
      else -> return false
    }
    stack.open(route)
    picks++
    return true
  }

  /** A launch registered [successor] for [from]; the selection moves only if [from] is still what is shown. */
  fun selectSuccessor(from: WorkIdentity, successor: WorkIdentity) {
    if (selected == from) openDetail(successor)
  }

  /** Leaves the detail for its list. Returns whether there was a detail to leave. */
  fun back(): Boolean = stack.back()

  /** Makes [target] the root, saving what the current section shows and bringing back what [target] showed last. */
  fun switchTo(target: Section) {
    val ref = generation() ?: return
    val current = section
    if (current == null || current == target) return
    memory(current).selection = stack.entries.getOrNull(1)
    val restored = memory(target).selection?.takeIf { it.generation == ref }
    stack.replaceAll(listOfNotNull(rootFor(target, ref), restored))
  }

  /**
   * Drops the stack and every section's memory; the window starts over at the root of its current section, made under [ref].
   * A database switch calls it with null first, so nothing of the old session is left, and again with the new generation.
   */
  fun reset(ref: DatabaseGenerationRef?) {
    memories.clear()
    status = ListStatus()
    stack.replaceAll(listOf(rootFor(section, ref)))
  }

  /** The saved form of the stack, for restoring it into a window opened later. */
  fun snapshot(): SavedState = RouteSnapshots.encode(stack.entries)

  private fun rootFor(section: Section?, ref: DatabaseGenerationRef?): AppRoute = when (section) {
    Section.WORK -> WorkRoute(ref)
    Section.HISTORY -> HistoryRoute(ref)
    null -> SettingsRoute
  }
}
