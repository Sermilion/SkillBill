package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.expandSemantics
import dev.skillbill.desktop.core.designsystem.composite.focusBar
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

/**
 * `.sb-sub`: one line of the Subtasks index, min 36 px, 12 px side padding, 10 px gaps. [selected] (the inspector's subject)
 * fills `bg-active` with a 2 px accent bar on the left edge and wins over [current]'s `bg-selected` tint; a selectable row
 * ([onClick]) shows `bg-hover` on its line while not selected. [body] renders under the line: the inline inspector.
 */
@Composable
fun SubtaskRow(
  number: String,
  status: StatusKind,
  progress: Float?,
  title: String,
  modifier: Modifier = Modifier,
  current: Boolean = false,
  selected: Boolean = false,
  onClick: (() -> Unit)? = null,
  meta: @Composable RowScope.() -> Unit = {},
  divider: Boolean = true,
  body: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val pending = status == StatusKind.Pending
  val interaction = remember { MutableInteractionSource() }
  val hovered = onClick != null && !selected && interaction.collectIsHoveredAsState().value
  val fill = when {
    selected -> colors.bgActive
    current -> colors.bgSelected
    else -> Color.Transparent
  }
  Column(
    modifier.fillMaxWidth().background(fill).focusBar(selected)
      .semantics { this.selected = selected }
      // `.sb-sub` border-bottom: the rule takes its own 1px below the row and body.
      .then(if (divider) Modifier.hairline(Edge.Bottom, colors.borderSubtle).padding(bottom = 1.dp) else Modifier),
  ) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = SkillBillSize.row)
        .background(if (hovered) colors.bgHover else Color.Transparent)
        .then(
          if (onClick != null) {
            // `.sb-sub-row` is a plain div: only the list takes focus (Tab), so ↑/↓ and Enter reach [SubtaskList] and Enter
            // opens the spec instead of clicking the row.
            Modifier.focusProperties { canFocus = false }
              .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
              .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
              .expandSemantics(body != null)
          } else {
            Modifier
          },
        )
        .padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.width(20.dp)) { IdTag(number) }
      StatusIcon(status, progress)
      BasicText(
        title, Modifier.weight(1f),
        style = if (pending) type.tBase.copy(color = colors.fgMuted) else type.tBaseMedium.copy(color = colors.fg),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
      )
      // Meta joins the row directly, so an empty meta adds no trailing gap after the title.
      meta()
    }
    if (body != null) {
      // `.sb-sub-body`: padding 4 16 16 62, 8 between blocks.
      Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
    }
  }
}
