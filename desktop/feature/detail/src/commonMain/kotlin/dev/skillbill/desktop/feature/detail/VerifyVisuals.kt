package dev.skillbill.desktop.feature.detail

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalSkillBillColors
import dev.skillbill.desktop.core.designsystem.LocalSkillBillType
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ResultRowBadge
import dev.skillbill.desktop.core.designsystem.component.ResultRowChevron
import dev.skillbill.desktop.core.designsystem.component.ResultRowFileChip
import dev.skillbill.desktop.core.designsystem.component.RuntimeTextStyles
import dev.skillbill.desktop.core.designsystem.component.runtimeText

@Composable
internal fun VerifyBox(modifier: Modifier = Modifier, fill: Color = SkillBillTheme.colors.bgRaised, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Column(modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, SkillBillTheme.colors.border, shape).padding(1.dp), content = content)
}

@Composable
internal fun VerifyPill(text: String, dot: Color? = null) {
  val colors = SkillBillTheme.colors
  Row(Modifier.height(22.dp).border(1.dp, colors.border, CircleShape).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    dot?.let { Box(Modifier.size(7.dp).background(it, CircleShape)) }
    BasicText(text, style = SkillBillTheme.type.tXs.copy(color = colors.fg2, fontWeight = FontWeight.Normal, lineHeight = 1.em))
  }
}

@Composable
internal fun VerifyBadge(text: String, color: Color) = ResultRowBadge(text, color)

@Composable
internal fun VerifyChevron(open: Boolean) = ResultRowChevron(open)

@Composable
internal fun VerifyFold(title: String, open: Boolean, onToggle: () -> Unit, count: Int? = null, note: String? = null, strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      VerifyChevron(open)
      BasicText(title, style = SkillBillTheme.type.tSm.copy(color = if (strong) colors.fg else colors.fgMuted, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal))
      (count?.toString() ?: note)?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint)) }
    }
    if (open) content()
  }
}

@Composable
internal fun verifyText(text: String): AnnotatedString {
  val colors = SkillBillTheme.colors
  val mono = SkillBillTheme.type.monoSm.fontFamily
  val styles = remember(colors, mono) {
    val code = SpanStyle(fontFamily = mono, fontSize = 11.5.sp, color = colors.fg2, background = colors.bgActive)
    RuntimeTextStyles(code, code, SpanStyle(color = colors.accentText), SpanStyle(fontWeight = FontWeight.Medium))
  }
  return remember(text, styles) { runtimeText(text, styles) }
}

@Composable
internal fun VerifyControlStyle(content: @Composable () -> Unit) {
  val type = SkillBillTheme.type
  CompositionLocalProvider(LocalSkillBillColors provides SkillBillTheme.colors.copy(fg = SkillBillTheme.colors.fg2), LocalSkillBillType provides type.copy(tSmMedium = type.tSm, tXs = type.tXs.copy(fontWeight = FontWeight.Normal)), content = content)
}

@Composable
internal fun VerifyFileChip(text: String, tooltip: String = text, icon: ImageVector? = null) = ResultRowFileChip(text, tooltip, icon)

@Composable
internal fun <T> VerifyRouteSwitch(options: List<Pair<T, String>>, value: T, large: Boolean = false, onSelect: (T) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(6.dp)
  Row(Modifier.background(colors.bgInset, shape).border(1.dp, colors.border, shape).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
    options.forEach { (id, label) ->
      Box(Modifier.height(if (large) 22.dp else 18.dp).background(if (id == value) colors.bgOverlay else Color.Transparent, RoundedCornerShape(3.dp))
        .clickable(role = Role.Tab, onClick = { onSelect(id) }).semantics { selected = id == value }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = SkillBillTheme.type.tXs.copy(fontSize = if (large) 11.5.sp else 10.5.sp, fontWeight = FontWeight.Normal, color = if (id == value) colors.fg else colors.fgFaint, lineHeight = 1.em))
      }
    }
  }
}

@Composable
internal fun VerifyTextLink(label: String, onClick: () -> Unit) {
  BasicText(label, Modifier.clickable(role = Role.Button, onClick = onClick), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.accentText))
}

@Composable
internal fun Modifier.verifyLiveRing(active: Boolean, color: Color, inset: Dp, opacity: Float): Modifier {
  if (!active) return this
  val transition = if (LocalReducedMotion.current) null else rememberInfiniteTransition(label = "verify-live-ring")
  val progress = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "verify-live-ring-progress")?.value
  return drawBehind {
    val scale = progress?.let { .85f + .4f * it } ?: 1f
    drawCircle(color.copy(alpha = opacity * (progress?.let { 1f - it } ?: 1f)), radius = (size.minDimension / 2 + inset.toPx()) * scale, style = Stroke(2.dp.toPx()))
  }
}
