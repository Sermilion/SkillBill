package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmptyState(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector = LucideIcons.inbox, body: String? = null, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
  androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
    Box(Modifier.size(44.dp).background(SkillBillTheme.colors.bgRaised, RoundedCornerShape(SkillBillRadius.lg)).border(1.dp, SkillBillTheme.colors.border, RoundedCornerShape(SkillBillRadius.lg)), contentAlignment = Alignment.Center) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgMuted) { SbIcon(icon, SbIconSize.Lg, modifier = Modifier) }
    }
    Spacer(Modifier.size(4.dp))
    BasicText(title, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg), maxLines = 2)
    body?.let { BasicText(it, Modifier.widthIn(max = SkillBillSize.toast), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, textAlign = TextAlign.Center), maxLines = 4) }
    actions?.let { FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
  }
}
