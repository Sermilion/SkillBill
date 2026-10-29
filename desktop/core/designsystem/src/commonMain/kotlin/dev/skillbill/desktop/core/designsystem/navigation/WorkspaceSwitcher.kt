package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.BrandMark

@Composable
fun WorkspaceSwitcher(name: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
  Row(
    modifier.fillMaxWidth().padding(bottom = 4.dp).height(32.dp).padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BrandMark(20.dp)
    BasicText(
      name, Modifier.weight(1f),
      style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fg, fontWeight = FontWeight.SemiBold),
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    actions()
  }
}
