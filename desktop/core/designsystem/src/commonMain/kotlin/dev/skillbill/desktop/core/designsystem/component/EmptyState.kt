package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmptyState(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector = LucideIcons.inbox, body: String? = null, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) =
  EmptyState(title, icon, body?.let(::AnnotatedString), actions)

/** The same empty state with rich body text, for inline mono spans such as a command. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmptyState(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector = LucideIcons.inbox, body: AnnotatedString?, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val iconShape = RoundedCornerShape(SkillBillRadius.lg)
  // `.sb-empty`: padding 48px 24px, gap 10px, centered.
  androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
    // margin-bottom: 4px on the icon tile adds to the 10px gap.
    Box(Modifier.padding(bottom = 4.dp).size(44.dp).background(colors.bgRaised, iconShape).border(1.dp, colors.border, iconShape), contentAlignment = Alignment.Center) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(icon, SbIconSize.Lg) }
    }
    BasicText(title, style = type.tMd.copy(color = colors.fg, lineHeight = 1.3.em, textAlign = TextAlign.Center))
    body?.let { BasicText(it, Modifier.widthIn(max = SkillBillSize.emptyStateBody), style = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.55.em, textAlign = TextAlign.Center)) }
    actions?.let { FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
  }
}
