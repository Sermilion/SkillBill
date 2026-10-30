package dev.skillbill.desktop.feature.specs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.PaneContribution
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

internal fun notifySpecsProjectSelection(projects: List<SpecProject>, projectId: String, selected: (Path?) -> Unit) {
  projects.firstOrNull { it.projectId == projectId }?.let { project ->
    selected(project.repoRoot?.let(Path::of))
  }
}

@Inject
@SingleIn(WindowScope::class)
class SpecsFeatureEntry(private val model: SpecsPaneModel, private val shellPanes: ShellPanes, private val paneCommands: PaneCommands) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override val panes: List<PaneContribution> = listOf(PaneContribution(PaneSlot.Right) {
    LaunchedEffect(model) { model.open() }
    val state by model.state.collectAsState()
    SpecsPane(state, { action ->
      if (action is SpecsAction.PickProject) {
        notifySpecsProjectSelection(model.state.value.projects, action.projectId, paneCommands::specProjectSelected)
      }
      model.onAction(action)
    }, onClose = {
      model.onAction(SpecsAction.DismissProjectMenu)
      shellPanes.specsOpen = false
    })
  })
}

@ContributesTo(WindowScope::class)
interface SpecsEntryBinding {
  @Provides
  @IntoSet
  fun entry(entry: SpecsFeatureEntry): FeatureEntry = entry
}
