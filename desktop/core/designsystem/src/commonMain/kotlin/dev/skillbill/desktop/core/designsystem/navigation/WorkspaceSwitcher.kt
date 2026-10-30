package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.BrandMark

@Composable
fun WorkspaceSwitcher(name: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  // `.sb-ws-btn` declares no transition: the hover fill switches instantly.
  val fill = if (interaction.collectIsHoveredAsState().value) colors.bgHover else Color.Transparent
  // `.sb-ws` pads 6 px and `.sb-ws-btn` pulls back 6 px with its own 6 px padding: the hover box starts flush, the mark at 6.
  Row(
    modifier.fillMaxWidth().padding(bottom = 4.dp).height(32.dp).padding(end = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The name takes all the room the actions leave; it ellipsizes only when that room runs out.
    Box(Modifier.weight(1f)) { Row(
      Modifier.height(28.dp).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).background(fill, RoundedCornerShape(SkillBillRadius.sm))
        .padding(horizontal = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BrandMark(20.dp)
      BasicText(
        name,
        style = SkillBillTheme.type.tBase.copy(color = colors.fg, fontWeight = FontWeight.SemiBold),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
    } }
    actions()
  }
}
