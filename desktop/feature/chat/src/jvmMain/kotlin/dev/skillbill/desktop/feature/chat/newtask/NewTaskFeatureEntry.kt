package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.NewTaskRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Renders [NewTaskRoute]. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskFeatureEntry(private val createViewModel: () -> NewTaskViewModel, private val navigator: AppNavigator) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route == NewTaskRoute

  @Composable
  override fun Content(route: AppRoute) {
    val viewModel = rememberEntryViewModel("new-task") { createViewModel() }
    val state by viewModel.state.collectAsState()
    val host = NewTaskScreenHost(
      now = viewModel::now,
      onOpenRun = { navigator.navigate(DetailRoute(it)) },
      onShowAllWork = { navigator.navigate(WorkRoute(null)) },
      onStart = { wire, target, action ->
        NewTaskActionKind.entries.firstOrNull { it.wire == wire }?.toNewTaskKind()?.let { kind ->
          viewModel.draft.start(kind, target = target, action = action)
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
