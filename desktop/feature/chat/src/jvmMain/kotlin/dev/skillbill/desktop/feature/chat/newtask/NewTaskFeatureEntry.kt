package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.ChangesWindows
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.NewTaskRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/** Renders [NewTaskRoute] and, while it shows, gives the terminal the selected chat's repository to start new tabs in. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskFeatureEntry(
  private val createViewModel: () -> NewTaskViewModel,
  private val navigator: AppNavigator,
  private val changesWindows: ChangesWindows,
  private val paneCommands: PaneCommands,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route == NewTaskRoute

  @Composable
  override fun Content(route: AppRoute) {
    val viewModel = rememberEntryViewModel("new-task") { createViewModel() }
    val state by viewModel.state.collectAsState()
    val selectedChat by viewModel.chats.selected.collectAsState()
    val repositoryRoot = selectedChat.repository?.root
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(repositoryRoot, windowFocused) {
      if (repositoryRoot != null && windowFocused) {
        while (true) {
          viewModel.onAction(NewTaskAction.RetryBranches)
          delay(3_000)
        }
      }
    }
    LaunchedEffect(repositoryRoot) { paneCommands.chatRepositorySelected(repositoryRoot?.let(Path::of)) }
    DisposableEffect(paneCommands) { onDispose { paneCommands.chatRepositorySelected(null) } }
    val host = NewTaskScreenHost(
      now = viewModel::now,
      onOpenRun = { navigator.navigate(DetailRoute(it)) },
      onShowAllWork = { navigator.navigate(WorkRoute(null)) },
      onOpenChatChanges = { source, path -> changesWindows.openChat(source, ChangeScope.All, path) },
      onStart = { wire, target, action ->
        NewTaskActionKind.entries.firstOrNull { it.wire == wire }?.toNewTaskKind()?.let { kind ->
          viewModel.start(kind, target, action)
        }
      },
    )
    NewTaskScreen(state, viewModel.chats, host, viewModel::onAction)
  }
}

@ContributesTo(WindowScope::class)
interface NewTaskEntryBinding {
  @Provides
  @IntoSet
  fun newTaskEntry(entry: NewTaskFeatureEntry): FeatureEntry = entry
}
