package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.RepoIcon
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
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.SidePane
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/**
 * screens.js `specsPane`: the 44 px header (title, project picker, Collapse all, close), then the toolbar, filters, tree and
 * footer sections. Toolbar and filters end in a hairline; the footer starts with one; the tree rows run edge to edge.
 */
@Composable
internal fun SpecsPane(state: SpecsUiState, onAction: (SpecsAction) -> Unit, onClose: () -> Unit) {
  val colors = SkillBillTheme.colors
  val project = state.project
  SidePane(
    title = "Specs",
    head = {
      Box {
        val dismiss = { onAction(SpecsAction.DismissProjectMenu) }
        ProjectPicker(project?.displayName, { onAction(SpecsAction.OpenProjectMenu) })
        if (state.projectMenuOpen) {
          ShellLayer(dismiss)
          SbPopover(expanded = true, onDismissRequest = dismiss) {
            SbMenu(
              listOf(SbMenuEntry.Head(CHOOSE_PROJECT)) + state.projects.map { item ->
                SbMenuEntry.Item(
                  item.displayName,
                  { onAction(SpecsAction.PickProject(item.projectId)) },
                  lead = { RepoIcon(item.displayName) },
                  checked = item.projectId == project?.projectId,
                )
              },
            )
          }
        }
      }
    },
    actions = {
      SbIconButton(LucideIcons.chevronUp, COLLAPSE_ALL, { onAction(SpecsAction.CollapseAll) }, size = SbControlSize.Small)
      SbIconButton(LucideIcons.x, CLOSE_SPECS, onClose, size = SbControlSize.Small)
    },
  ) {
    // Each section's 1 px CSS border takes layout space, so the padding on the bordered edge is one more than the CSS value.
    SpecRunToolbar(state, onAction, Modifier.fillMaxWidth().rule(bottom = true, colors.borderSubtle).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 13.dp))
    Column(
      Modifier.fillMaxWidth().rule(bottom = true, colors.borderSubtle).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 11.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SbInput(state.filterText, { onAction(SpecsAction.FilterChanged(it)) }, Modifier.fillMaxWidth().gatesShortcutsWhileFocused(), placeholder = FILTER_HINT, leadingIcon = LucideIcons.search)
      Segmented(
        options = listOf(
          SegmentedOption(SpecStatusFilter.ALL, "All"),
          SegmentedOption(SpecStatusFilter.ACTIVE, "Active"),
          SegmentedOption(SpecStatusFilter.PENDING, "Pending"),
          SegmentedOption(SpecStatusFilter.DONE, "Done"),
        ),
        selected = state.statusFilter,
        onSelect = { onAction(SpecsAction.ChooseStatus(it)) },
        // `.sb-seg` stretches across the `.sb-filters` column with its options packed at the start.
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
      when (val tree = state.tree) {
        // `.sb-scroll` is a block box, so `.sb-empty` sits at the top (48 down from its own padding), not centered.
        is SpecTreeState.Unavailable -> TopEmpty {
          EmptyState(SPECS_UNAVAILABLE, icon = LucideIcons.alert, body = readProblemText(tree.problem), actions = {
            SbButton("Retry", { onAction(SpecsAction.Retry) })
          })
        }
        SpecTreeState.NoCheckout -> TopEmpty { EmptyState(NO_CHECKOUT, icon = LucideIcons.folder) }
        is SpecTreeState.NoSpecs -> TopEmpty { EmptyState(NO_SPECS, icon = LucideIcons.fileText, body = noSpecsBody()) }
        SpecTreeState.Loading -> Unit
        is SpecTreeState.Loaded -> if (state.rows.isEmpty() && state.filtersActive) {
          TopEmpty {
            EmptyState(NO_MATCH, icon = LucideIcons.search, body = NO_MATCH_BODY, actions = {
              SbButton("Clear filters", { onAction(SpecsAction.ClearFilters) })
            })
          }
        } else SpecsTree(state, onAction, Modifier.fillMaxWidth())
      }
    }
    state.storePath?.let { path ->
      Row(
        Modifier.fillMaxWidth().rule(bottom = false, colors.borderSubtle).padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // `.sb-pane-foot`: 10.5 px mono at weight 400 and 1.3 line height.
        BasicText(path, Modifier.weight(1f), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 13.65.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        SbButton("Reveal", { onAction(SpecsAction.Reveal) }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
      }
    }
  }
}

/** `.sb-pick`: a 24 px bordered button with the repository icon, the project name and a 12 px faint chevron. It has no tooltip. */
@Composable
private fun ProjectPicker(name: String?, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  // `.sb-pick` has no transition, so hover switches at once.
  val fill = if (hovered) colors.bgHover else Color.Transparent
  val text = if (hovered) colors.fg else colors.fg2
  Row(
    Modifier.height(24.dp).background(fill, shape).border(1.dp, colors.border, shape)
      .hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onClick)
      .pointerHoverIcon(PointerIcon.Hand)
      .sbFocusRing(interaction, shape)
      .semantics {
        role = Role.Button
        contentDescription = name ?: CHOOSE_PROJECT
      }
      // 8 px of CSS padding inside the 1 px border.
      .padding(horizontal = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    name?.let { RepoIcon(it) }
    BasicText(name ?: CHOOSE_PROJECT, style = SkillBillTheme.type.tSmMedium.copy(color = text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronDown, SbIconSize.Xs) }
  }
}

/** A 1 px hairline on the bottom or top edge, as the pane's section borders draw it. */
private fun Modifier.rule(bottom: Boolean, color: Color) = drawBehind {
  val stroke = 1.dp.toPx()
  val y = if (bottom) size.height - stroke / 2 else stroke / 2
  drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
}

/** "No specs yet" body with the plan command in mono, as screens.js renders it. */
@Composable
private fun noSpecsBody(): AnnotatedString {
  val command = "/skill-bill phase:plan"
  val start = NO_SPECS_BODY.indexOf(command)
  return buildAnnotatedString {
    append(NO_SPECS_BODY)
    if (start >= 0) addStyle(SpanStyle(fontFamily = SkillBillTheme.type.mono.fontFamily), start, start + command.length)
  }
}

/** A scroll column with unbounded height, so [EmptyState]'s fill takes its content height and stays at the top. */
@Composable
private fun TopEmpty(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}
