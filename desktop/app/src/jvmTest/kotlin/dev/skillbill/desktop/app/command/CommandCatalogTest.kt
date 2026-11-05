package dev.skillbill.desktop.app.command

import dev.skillbill.desktop.app.keyboard.TerminalTriggers
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportRefusalCategory
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeArchiveFileChooser
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.CommandContext
import dev.skillbill.desktop.core.ui.navigation.CommandSection
import dev.skillbill.desktop.core.ui.navigation.ShellCommand
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CommandCatalogTest {
  @Test
  fun `spec section is optional and follows the current item section`() = runTest {
    val catalog = catalog(FakeWorkControlRepository(), FakeArchiveFileChooser(), ToastQueue(), backgroundScope)
    assertEquals(emptyList(), catalog.sections(CommandContext(null, null), null, emptySet()))

    val specSection = CommandSection("SKILL-503", listOf(ShellCommand(CommandItem("spec-open", "Open spec")) {}))
    val item = workItem("SKILL-503")
    val sections = catalog.sections(CommandContext(null, item), null, emptySet(), specSection)

    assertEquals(listOf(rowKey(item), "SKILL-503"), sections.take(2).map { it.head })
    assertEquals("Open spec", sections[1].commands.single().item.label)
  }

  @Test
  fun `branch refusals with live in the branch name retain their reason at preflight and export`() = runTest {
    val reason = "Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused."
    val controls = FakeWorkControlRepository().apply {
      exportPreflightOutcome = ExportPreflightOutcome.Refused(reason, ExportRefusalCategory.BRANCH)
    }
    val archiveChooser = FakeArchiveFileChooser().apply { nextSave = "/tmp/SKILL-474.zip" }
    val toasts = ToastQueue()
    val catalog = catalog(controls, archiveChooser, toasts, backgroundScope)
    val item = workItem("SKILL-474")
    val export = catalog.sections(CommandContext(null, item), null, emptySet()).single().commands
      .single { it.item.id == "export" }

    export.run()
    runCurrent()
    assertEquals(reason, toasts.host.toasts.last().data.body)

    controls.exportPreflightOutcome = ExportPreflightOutcome.Ready("feature/delivery", "abc123")
    controls.exportOutcome = ExportOutcome.Refused(reason, ExportRefusalCategory.BRANCH)
    export.run()
    runCurrent()
    assertEquals(reason, toasts.host.toasts.last().data.body)
  }

  @Test
  fun `a live worker refusal still reports that the goal must be paused`() = runTest {
    val controls = FakeWorkControlRepository().apply {
      exportPreflightOutcome = ExportPreflightOutcome.Refused(
        "Goal is live; export refused.",
        ExportRefusalCategory.LIVENESS,
      )
    }
    val toasts = ToastQueue()
    val catalog = catalog(controls, FakeArchiveFileChooser(), toasts, backgroundScope)
    val export = catalog.sections(CommandContext(null, workItem("SKILL-474")), null, emptySet()).single().commands
      .single { it.item.id == "export" }

    export.run()
    runCurrent()

    assertEquals("Pause the goal before exporting it.", toasts.host.toasts.last().data.body)
  }

  private fun catalog(
    controls: FakeWorkControlRepository,
    archiveChooser: FakeArchiveFileChooser,
    toasts: ToastQueue,
    scope: kotlinx.coroutines.CoroutineScope,
  ): CommandCatalog {
    val session = TestSession()
    val paneCommands = TestPaneCommands()
    return CommandCatalog(
      emptySet(),
      controls,
      archiveChooser,
      session,
      toasts,
      WindowNavigator(session, SelectionHolder()),
      TerminalTriggers(paneCommands),
      WindowCoroutineScope(scope),
    )
  }

  private class TestSession : DatabaseSessionController {
    override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("test", 1L))
    override fun reopen() = Unit
  }

  private class TestPaneCommands : PaneCommands {
    override val terminalOpen = MutableStateFlow(false)
    override val terminalFocused = MutableStateFlow(false)
    override fun toggleTerminal() = Unit
    override fun newTerminalTab() = Unit
    override fun openTerminal(directory: Path?) = Unit
    override fun openTerminal(item: dev.skillbill.desktop.core.domain.model.WorkItem) = Unit
    override fun specProjectSelected(checkout: Path?) = Unit
    override fun closeTerminals() = Unit
  }
}
