package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

enum class FeedbackTone { Warn, Error, Info, Success, Neutral }

@Composable private fun FeedbackTone.color(): Color = when (this) {
  FeedbackTone.Warn -> SkillBillTheme.colors.stBlocked
  FeedbackTone.Error -> SkillBillTheme.colors.stFailed
  FeedbackTone.Info -> SkillBillTheme.colors.stPlanning
  FeedbackTone.Success -> SkillBillTheme.colors.stDone
  FeedbackTone.Neutral -> SkillBillTheme.colors.fgFaint
}
private fun FeedbackTone.icon(): ImageVector = when (this) {
  FeedbackTone.Warn -> LucideIcons.warn
  FeedbackTone.Error -> LucideIcons.alert
  FeedbackTone.Info -> LucideIcons.info
  FeedbackTone.Success -> LucideIcons.checkCircle
  FeedbackTone.Neutral -> LucideIcons.info
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Callout(tone: FeedbackTone = FeedbackTone.Info, title: String? = null, body: AnnotatedString? = null, icon: ImageVector? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
  val color = tone.color()
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Row(Modifier.background(color.copy(alpha = .08f), shape).border(1.dp, color.copy(alpha = .30f), shape).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides color) { SbIcon(icon ?: tone.icon(), SbIconSize.Md, modifier = Modifier.padding(top = 1.dp)) }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      title?.let { BasicText(it, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg)) }
      body?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
      actions?.let { FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { it() } }
    }
  }
}

@Composable
fun Callout(tone: FeedbackTone = FeedbackTone.Info, title: String? = null, body: String, icon: ImageVector? = null, actions: (@Composable RowScope.() -> Unit)? = null) =
  Callout(tone, title, AnnotatedString(body), icon, actions)

@Composable
fun Hint(text: AnnotatedString, tone: FeedbackTone = FeedbackTone.Neutral, icon: ImageVector? = null) {
  val color = tone.color()
  Row(Modifier.semantics { if (tone == FeedbackTone.Error || tone == FeedbackTone.Warn) liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides color) { SbIcon(icon ?: tone.icon(), SbIconSize.Sm) }
    BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
fun Hint(text: String, tone: FeedbackTone = FeedbackTone.Neutral, icon: ImageVector? = null) = Hint(AnnotatedString(text), tone, icon)
