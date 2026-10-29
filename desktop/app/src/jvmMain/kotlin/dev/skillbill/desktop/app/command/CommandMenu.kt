package dev.skillbill.desktop.app.command

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.app.di.WindowComponent
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.overlay.CommandGroup
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
import dev.skillbill.desktop.core.designsystem.overlay.CommandPalette
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.ui.navigation.CommandContext

/** The command menu over the window. Opening it prefetches the current item's run options; it closes before a command runs. */
@Composable
fun CommandMenu(window: WindowComponent) {
  val menu = window.menu
  if (!menu.open) return
  val item by window.selection.item.collectAsState()
  val reservations by window.controls.reservations.collectAsState()
  val boardView by window.board.view.collectAsState()
  var runOptions by remember(item?.id) { mutableStateOf<RunOptions?>(null) }
  LaunchedEffect(item?.id) { runOptions = item?.let { window.details.prefetch(it.id) } }
  val topRoute = window.navigator.top.route
  val sections = remember(item, runOptions, reservations, boardView, topRoute, menu.query, window.panes.specsOpen, window.terminal.open) {
    filterSections(window.catalog.sections(CommandContext(topRoute, item), runOptions, reservations), menu.query)
  }
  val commands = remember(sections) {
    sections.mapIndexed { index, section -> section.commands.associateBy { "$index/${it.item.id}" } }
  }
  val groups = remember(commands, sections) {
    sections.zip(commands) { section, byId ->
      CommandGroup(section.head, byId.map { (id, it) -> CommandItem(id, it.item.label, it.item.icon, it.item.shortcut, it.item.danger, it.item.enabled) })
    }
  }
  CommandPalette(
    query = menu.query,
    onQueryChange = { menu.query = it },
    groups = groups,
    onRun = { id ->
      val command = commands.firstNotNullOf { it[id] }
      menu.close()
      command.run()
    },
    onDismiss = menu::close,
    context = item?.let { current -> { IdTag(current.issueKey ?: current.id) } },
  )
}
