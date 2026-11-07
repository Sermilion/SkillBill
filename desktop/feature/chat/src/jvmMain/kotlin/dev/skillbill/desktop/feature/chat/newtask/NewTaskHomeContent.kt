package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.GroupLabel

private val SECTION_SPACING = 8.dp

internal const val NEW_TASK_HISTORY_ROWS = 6

private data class ExampleTask(val label: String, val kind: String, val fill: String)

private val EXAMPLE_TASKS = listOf(
  ExampleTask("Add CSV export to the history screen", "Goal run", "Add CSV export to the history screen. Done when a user can download the rows they see."),
  ExampleTask("Review my current branch", "Standalone review", "Review my current branch"),
  ExampleTask("APP-123", "From an issue", "APP-123"),
)

private val START_CHIPS = listOf(
  "Review my branch" to "review",
  "Run the checks" to "validation",
  "QA my branch" to "qa",
  "Open a PR" to "pr",
)

@Composable
internal fun ExamplesSection(readiness: ReadinessLine?, onExample: (String) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    GroupLabel("Try one of these")
    Column {
      EXAMPLE_TASKS.forEach { example ->
        HomeRow({ onExample(example.fill) }, Modifier.heightIn(min = SkillBillSize.row)) {
          BasicText(example.label, Modifier.weight(1f), style = type.tBase.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
          BasicText(example.kind, style = type.tSm.copy(color = colors.fgMuted), maxLines = 1)
        }
      }
    }
    readiness?.let { ReadinessRow(it) }
  }
}

@Composable
private fun ReadinessRow(line: ReadinessLine) {
  val colors = SkillBillTheme.colors
  val dot = if (line.ready) colors.stDone else colors.accent
  Row(horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING), verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(7.dp)) { drawCircle(dot) }
    BasicText(line.text, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** Pressing a chip reports its start kind and leaves the input text alone. A null [onStart] renders every chip disabled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewTaskChips(onStart: ((String, String?) -> Unit)?, modifier: Modifier = Modifier) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    START_CHIPS.forEach { (label, kind) ->
      SbButton(label, { onStart?.invoke(kind, null) }, size = SbControlSize.Small, enabled = onStart != null)
    }
  }
}

@Composable
private fun HomeRow(onClick: () -> Unit, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focused by interaction.collectIsFocusedAsState()
  Row(
    modifier.fillMaxWidth()
      .background(if (hovered || focused) SkillBillTheme.colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.sm))
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}
