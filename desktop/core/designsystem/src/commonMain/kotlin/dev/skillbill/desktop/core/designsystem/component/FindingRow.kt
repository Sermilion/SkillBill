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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private val FINDING_SEVERITY_WIDTH = 58.dp
private val COMPACT_SEVERITY_WIDTH = 52.dp
private const val COMPACT_PATH_LIMIT = 28

/** A long path as the compact row shows it: "…/" plus the last two segments once it passes 28 characters. */
fun shortPath(path: String): String {
  if (path.length <= COMPACT_PATH_LIMIT) return path
  val segments = path.split('/')
  return if (segments.size > 2) "…/" + segments.takeLast(2).joinToString("/") else "…" + path.takeLast(COMPACT_PATH_LIMIT - 1)
}

@Composable
fun FindingRow(title: String, severity: String? = null, ref: String? = null, location: String? = null, meta: String? = null, state: String? = null, stateColor: Color? = null, showDivider: Boolean = true, refContent: (@Composable () -> Unit)? = null) {
  val dividerColor = SkillBillTheme.colors.borderSubtle
  val locationFontFamily = SkillBillTheme.type.monoSm.fontFamily
  // `.sb-finding` border-top takes its own 1px above the 9px padding; the line sits inside the row, not half above it.
  Row(
    Modifier.drawBehind {
      if (showDivider) {
        val stroke = 1.dp.toPx()
        drawLine(dividerColor, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
      }
    }.padding(start = 12.dp, end = 12.dp, top = if (showDivider) 10.dp else 9.dp, bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    severity?.let { SeverityText(it, FINDING_SEVERITY_WIDTH) }
    // A long machine id passes [refContent] (a ShortId) in place of the plain ref tag.
    if (refContent != null) refContent() else ref?.let { IdTag(it, Modifier.padding(top = 2.dp)) }
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExpandableText(title, SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.45.em))
        if (location != null || meta != null) {
          val text = buildAnnotatedString {
            if (location != null) withStyle(SpanStyle(fontFamily = locationFontFamily)) { append(location) }
            if (location != null && meta != null) append(" · ")
            meta?.let(::append)
          }
          ExpandableText(text, SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
        }
      }
    }
    state?.let { SbLabel(it, SbLabelStyle.Dot(stateColor ?: SkillBillTheme.colors.fgFaint)) }
  }
}

/**
 * `.sb-finding--compact`, the subtask inspector's finding: no divider, 5 px vertical padding, 8 px gap, a 52 px severity column,
 * the text in 12 px `fg-2` that wraps and folds past three lines ([ExpandableText]), and [location] under it on one line in 11.5 px mono `fg-faint`, shortened by [shortPath]
 * with the full path in a tooltip.
 */
@Composable
fun CompactFindingRow(title: String, severity: String?, location: String?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    severity?.let { SeverityText(it, COMPACT_SEVERITY_WIDTH) }
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExpandableText(title, type.tSm.copy(color = colors.fg2, lineHeight = 1.45.em))
        location?.takeIf { it.isNotBlank() }?.let { path ->
          SbTooltip(path) {
            BasicText(
              shortPath(path),
              style = type.monoSm.copy(color = colors.fgFaint, fontSize = type.tXs.fontSize, lineHeight = 1.45.em),
              maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SeverityText(severity: String, width: Dp) {
  BasicText(
    severity.uppercase(), Modifier.width(width),
    style = SkillBillTheme.type.t2xs.copy(color = severityColor(severity), fontWeight = FontWeight.SemiBold, letterSpacing = .04f.em, lineHeight = 1.6.em),
    maxLines = 1,
  )
}

@Composable private fun severityColor(severity: String): Color = when (severity.lowercase()) {
  "blocker", "high" -> SkillBillTheme.colors.stFailed
  "major", "medium" -> SkillBillTheme.colors.stBlocked
  else -> SkillBillTheme.colors.fgFaint
}
