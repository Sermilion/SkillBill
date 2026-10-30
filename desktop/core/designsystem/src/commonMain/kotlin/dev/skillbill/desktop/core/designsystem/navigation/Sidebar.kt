package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** The room a focus ring needs outside its row: 1 px offset plus the 2 px stroke. */
private val FOCUS_RING_BLEED = 3.dp

/** Lays the element out [amount] larger on every side than its slot, without moving its neighbours. */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
  val px = amount.roundToPx()
  val placeable = measurable.measure(constraints.offset(horizontal = px * 2, vertical = px * 2))
  layout(placeable.width - px * 2, placeable.height - px * 2) { placeable.place(-px, -px) }
}

@Composable
fun Sidebar(
  top: @Composable () -> Unit,
  footer: @Composable ColumnScope.() -> Unit,
  modifier: Modifier = Modifier,
  items: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier.width(SkillBillSize.sidebar).fillMaxHeight().background(SkillBillTheme.colors.bgFrame)
      .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    top()
    // The scroll area clips to its bounds, which would cut the items' focus ring (2px, 1px outside the row). It reaches
    // [FOCUS_RING_BLEED] past its slot and pads its content back, so the rows sit where they did and the ring stays whole.
    Column(
      Modifier.weight(1f).fillMaxWidth().bleed(FOCUS_RING_BLEED).verticalScroll(rememberScrollState()).padding(FOCUS_RING_BLEED),
      verticalArrangement = Arrangement.spacedBy(1.dp),
      content = items,
    )
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp), content = footer)
  }
}
