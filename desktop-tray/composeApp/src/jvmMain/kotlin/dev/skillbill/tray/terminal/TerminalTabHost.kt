package dev.skillbill.tray.terminal

import dev.skillbill.tray.feature.terminal.TerminalTabs
import dev.skillbill.tray.lifetime.WindowOwner
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * What the navigation host needs from the terminal's platform side: the window's tab list, tab ids and directories (the view
 * model only holds them), the shells that ended on their own, and ending the shells whose tabs the view model no longer lists.
 */
interface TerminalTabHost {
  /** The tab list of [window]. It outlives the window's view models, so a database switch keeps every tab and its shell. */
  fun tabs(window: WindowOwner): TerminalTabs

  /** A tab id that has never been used in this process. */
  fun newTabId(): Long

  /** A missing, blank or non-directory [requested] directory resolves to the user's home. */
  fun resolveDirectory(requested: String?): ResolvedTerminalDirectory

  /** The tabs of [window] whose shell exited on its own and whose tab is still listed. */
  fun endedTabs(window: WindowOwner): StateFlow<Set<Long>>

  /** Ends the shells of [window] whose tab is not in [tabIds]. */
  fun retainOnly(window: WindowOwner, tabIds: Set<Long>)
}

object AppTerminalTabHost : TerminalTabHost {
  override fun tabs(window: WindowOwner): TerminalTabs = appTerminalSessions.tabsFor(window)

  override fun newTabId(): Long = appTerminalSessions.newTabId()

  override fun resolveDirectory(requested: String?): ResolvedTerminalDirectory =
    resolveTerminalDirectory(System.getProperty("user.home"), requested, { File(it).isDirectory }) { git ->
      File(git).takeIf(File::isFile)?.readText()
    }

  override fun endedTabs(window: WindowOwner): StateFlow<Set<Long>> = appTerminalSessions.endedTabs(window)

  override fun retainOnly(window: WindowOwner, tabIds: Set<Long>) = appTerminalSessions.retainOnly(window, tabIds)
}
