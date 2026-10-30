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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
fun Callout(tone: FeedbackTone = FeedbackTone.Info, title: String? = null, body: AnnotatedString? = null, icon: ImageVector? = null, actions: (@Composable RowScope.() -> Unit)? = null, modifier: Modifier = Modifier, content: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
  val color = tone.color()
  val shape = RoundedCornerShape(SkillBillRadius.md)
  // `.sb-callout` pads 10px 12px inside its 1px border.
  Row(modifier.background(color.copy(alpha = .08f), shape).border(1.dp, color.copy(alpha = .30f), shape).padding(horizontal = 13.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides color) { SbIcon(icon ?: tone.icon(), SbIconSize.Md, modifier = Modifier.padding(top = 1.dp)) }
    androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      title?.let { BasicText(it, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.4.em)) }
      body?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.5.em)) }
      // Rich `.sb-callout-bd` markup (columns, checklists) that plain body text can't carry, drawn in the body's text style.
      content?.let { slot ->
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgMuted) {
          androidx.compose.foundation.layout.Column(content = slot)
        }
      }
      actions?.let { FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { it() } }
    }
  }
}

@Composable
fun Callout(tone: FeedbackTone = FeedbackTone.Info, title: String? = null, body: String, icon: ImageVector? = null, actions: (@Composable RowScope.() -> Unit)? = null, modifier: Modifier = Modifier) =
  Callout(tone, title, AnnotatedString(body), icon, actions, modifier)

@Composable
fun Hint(text: AnnotatedString, tone: FeedbackTone = FeedbackTone.Neutral, icon: ImageVector? = null, fold: Boolean = false) {
  val color = tone.color()
  // `.sb-hint` aligns to the top and wraps; the 14px icon drops 1px to meet the first line.
  Row(Modifier.semantics { if (tone == FeedbackTone.Error || tone == FeedbackTone.Warn) liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides color) { SbIcon(icon ?: tone.icon(), SbIconSize.Sm, modifier = Modifier.padding(top = 1.dp)) }
    val style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.45.em)
    // [fold]: runtime text such as a block reason can run to many lines, so past three it folds behind Show more.
    if (fold) ExpandableText(text, style, Modifier.weight(1f, fill = false)) else BasicText(text, Modifier.weight(1f, fill = false), style = style)
  }
}

@Composable
fun Hint(text: String, tone: FeedbackTone = FeedbackTone.Neutral, icon: ImageVector? = null, fold: Boolean = false) = Hint(AnnotatedString(text), tone, icon, fold)
