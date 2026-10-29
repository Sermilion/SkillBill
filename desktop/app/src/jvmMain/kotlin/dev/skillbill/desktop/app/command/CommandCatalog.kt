package dev.skillbill.desktop.app.command

import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.app.keyboard.TerminalTriggers
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.navigation.CommandContext
import dev.skillbill.desktop.core.ui.navigation.CommandSection
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.ShellCommand
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Builds the command menu: the current item's actions first, then every feature entry's groups with the shell's own first. An
 * item action appears only when the item's runtime verdicts enable it, and reports its outcome through [ToastQueue].
 */
@Inject
@SingleIn(WindowScope::class)
class CommandCatalog(
  private val entries: Set<FeatureEntry>,
  private val controls: WorkControlRepository,
  private val toasts: ToastQueue,
  private val navigator: WindowNavigator,
  private val terminal: TerminalTriggers,
  private val scope: WindowCoroutineScope,
) {
  fun sections(context: CommandContext, runOptions: RunOptions?, reservations: Set<String>): List<CommandSection> {
    val shellFirst = entries.filterIsInstance<ShellCommandsEntry>() + entries.filterNot { it is ShellCommandsEntry }
    return listOfNotNull(context.item?.let { itemSection(it, context, runOptions, reservations) }) +
      shellFirst.flatMap { it.commands(context) }
  }

  private fun itemSection(item: WorkItem, context: CommandContext, runOptions: RunOptions?, reservations: Set<String>): CommandSection {
    val key = item.issueKey ?: item.id
    val reserved = key in reservations
    val subject = FeedbackSubject(key, runOptions?.launchLabel, runOptions?.agentId)
    val commands = buildList {
      if ((context.topRoute as? DetailRoute)?.item?.id != item.id) {
        add(ShellCommand(CommandItem("open", "Open $key", LucideIcons.external, "↵")) { navigator.navigate(DetailRoute(item)) })
      }
      if (item.pauseEligible && !item.pauseRequested) {
        add(control("pause-soft", "Pause after subtask", LucideIcons.pause, "P", ControlAction.PauseAfterSubtask, subject) { controls.pauseAfterSubtask(item) })
      }
      if (item.controlEligible) {
        add(control("pause-now", "Pause now", LucideIcons.stop, "⇧ P", ControlAction.PauseNow, subject) { controls.pauseNow(item) })
      }
      if (runOptions?.canLaunch == true && !reserved) {
        add(control("launch", runOptions.launchLabel, LucideIcons.play, "R", ControlAction.Launch, subject) { controls.launch(item, runOptions.agentId) })
      }
      if (runOptions?.canCancel == true && !reserved) {
        add(control("cancel", "Cancel", LucideIcons.x, null, ControlAction.Cancel, subject) { controls.cancel(item) })
      }
      item.reason?.let { reason ->
        add(ShellCommand(CommandItem("copy", "Copy reason", LucideIcons.copy)) {
          copyToClipboard(reason)
          toasts.show(ToastData("Copied", copyPreview(reason), ToastLead.Icon(LucideIcons.copy)))
        })
      }
      add(ShellCommand(CommandItem("terminal-here", "Open terminal in ${item.repositoryName}", LucideIcons.terminal)) {
        terminal.openIn(item.repositoryRoot)
      })
    }
    return CommandSection(key, commands)
  }

  private fun control(
    id: String,
    label: String,
    icon: ImageVector,
    shortcut: String?,
    action: ControlAction,
    subject: FeedbackSubject,
    request: suspend () -> ControlOutcome,
  ) = ShellCommand(CommandItem(id, label, icon, shortcut)) {
    scope.scope.launch { toasts.show(controlFeedback(action, request(), subject)) }
  }
}

/** Keeps the entries whose label and group head hold every typed term, as the prototype's filter does. */
fun filterSections(sections: List<CommandSection>, query: String): List<CommandSection> {
  val terms = query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
  return sections.map { section ->
    CommandSection(section.head, section.commands.filter { command -> terms.all { "${command.item.label} ${section.head}".lowercase().contains(it) } })
  }.filter { it.commands.isNotEmpty() }
}
