package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.NewTaskRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Renders [NewTaskRoute]. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskFeatureEntry(private val createViewModel: () -> NewTaskViewModel) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route == NewTaskRoute

  @Composable
  override fun Content(route: AppRoute) {
    val viewModel = rememberEntryViewModel("new-task") { createViewModel() }
    val state by viewModel.state.collectAsState()
    NewTaskScreen(state, viewModel.draft, viewModel::onAction)
  }
}

@ContributesTo(WindowScope::class)
interface NewTaskEntryBinding {
  @Provides
  @IntoSet
  fun newTaskEntry(entry: NewTaskFeatureEntry): FeatureEntry = entry
}
