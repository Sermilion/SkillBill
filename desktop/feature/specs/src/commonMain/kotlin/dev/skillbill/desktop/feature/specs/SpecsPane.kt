package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.detail.SidePane
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

@Composable
internal fun SpecsPane(state: SpecsUiState, onAction: (SpecsAction) -> Unit, onClose: () -> Unit) {
  val project = state.project
  SidePane(
    title = "Specs",
    head = {
      Box {
        val dismiss = { onAction(SpecsAction.DismissProjectMenu) }
        SbButton(project?.displayName ?: CHOOSE_PROJECT, { onAction(SpecsAction.OpenProjectMenu) }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost, icon = LucideIcons.chevronDown, tooltip = CHOOSE_PROJECT)
        if (state.projectMenuOpen) {
          ShellLayer(dismiss)
          SbPopover(expanded = true, onDismissRequest = dismiss) {
            SbMenu(state.projects.map { item -> SbMenuEntry.Item(item.displayName, {
              onAction(SpecsAction.PickProject(item.projectId))
            }, checked = item.projectId == project?.projectId) })
          }
        }
      }
    },
    actions = {
      SbIconButton(LucideIcons.chevronUp, COLLAPSE_ALL, { onAction(SpecsAction.CollapseAll) })
      SbIconButton(LucideIcons.x, CLOSE_SPECS, onClose)
    },
  ) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SpecRunToolbar(state, onAction)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SbInput(state.filterText, { onAction(SpecsAction.FilterChanged(it)) }, Modifier.weight(1f).gatesShortcutsWhileFocused(), placeholder = FILTER_HINT, leadingIcon = LucideIcons.search)
        Segmented(
          options = listOf(
            SegmentedOption(SpecStatusFilter.ALL, "All"),
            SegmentedOption(SpecStatusFilter.ACTIVE, "Active"),
            SegmentedOption(SpecStatusFilter.PENDING, "Pending"),
            SegmentedOption(SpecStatusFilter.DONE, "Done"),
          ),
          selected = state.statusFilter,
          onSelect = { onAction(SpecsAction.ChooseStatus(it)) },
        )
      }
      when (val tree = state.tree) {
        is SpecTreeState.Unavailable -> EmptyState(SPECS_UNAVAILABLE, body = readProblemText(tree.problem), actions = {
          SbButton("Retry", { onAction(SpecsAction.Retry) }, size = SbControlSize.Small, variant = SbButtonVariant.Secondary)
        })
        SpecTreeState.NoCheckout -> EmptyState(NO_CHECKOUT)
        is SpecTreeState.NoSpecs -> EmptyState(NO_SPECS, body = NO_SPECS_BODY)
        SpecTreeState.Loading -> Unit
        is SpecTreeState.Loaded -> if (state.rows.isEmpty() && state.filtersActive) {
          EmptyState(NO_MATCH, body = NO_MATCH_BODY, actions = {
            SbButton("Clear filters", { onAction(SpecsAction.ClearFilters) }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
          })
        } else SpecsTree(state, onAction, Modifier.weight(1f))
      }
      Spacer(Modifier.weight(.001f))
      Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        state.storePath?.let { BasicText(it, Modifier.weight(1f), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (state.storePath != null) SbButton("Reveal", { onAction(SpecsAction.Reveal) }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
      }
    }
  }
}
