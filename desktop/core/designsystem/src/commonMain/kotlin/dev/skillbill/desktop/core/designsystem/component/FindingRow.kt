package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private val FINDING_SEVERITY_WIDTH = 58.dp

@Composable
fun FindingRow(title: String, severity: String? = null, ref: String? = null, location: String? = null, meta: String? = null, state: String? = null, stateColor: Color? = null, showDivider: Boolean = true) {
  val dividerColor = SkillBillTheme.colors.borderSubtle
  val locationFontFamily = SkillBillTheme.type.monoSm.fontFamily
  Row(Modifier.drawBehind { if (showDivider) drawLine(dividerColor, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    severity?.let { BasicText(it.uppercase(), Modifier.width(FINDING_SEVERITY_WIDTH), style = SkillBillTheme.type.t2xs.copy(color = severityColor(it), fontWeight = FontWeight.SemiBold, letterSpacing = .04f.em, lineHeight = 1.6.em), maxLines = 1) }
    ref?.let { IdTag(it, Modifier.padding(top = 2.dp)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      BasicText(title, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.45.em))
      if (location != null || meta != null) {
        val text = buildAnnotatedString {
          if (location != null) withStyle(SpanStyle(fontFamily = locationFontFamily)) { append(location) }
          if (location != null && meta != null) append(" · ")
          meta?.let(::append)
        }
        BasicText(text, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
      }
    }
    state?.let { SbLabel(it, SbLabelStyle.Dot(stateColor ?: SkillBillTheme.colors.fgFaint)) }
  }
}

@Composable private fun severityColor(severity: String): Color = when (severity.lowercase()) {
  "blocker", "high" -> SkillBillTheme.colors.stFailed
  "major", "medium" -> SkillBillTheme.colors.stBlocked
  else -> SkillBillTheme.colors.fgFaint
}
