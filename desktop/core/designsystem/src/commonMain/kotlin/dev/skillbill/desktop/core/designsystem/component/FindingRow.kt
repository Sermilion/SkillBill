package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private val FINDING_SEVERITY_WIDTH = 58.dp

@Composable
fun FindingRow(
  title: String,
  severity: String? = null,
  ref: String? = null,
  location: String? = null,
  meta: String? = null,
  state: String? = null,
  stateColor: Color? = null,
  showDivider: Boolean = true,
  refContent: (@Composable () -> Unit)? = null,
  stacked: Boolean = false,
  severityColor: Color? = null,
) {
  val dividerColor = SkillBillTheme.colors.borderSubtle
  val metaPresent = !location.isNullOrBlank() || !meta.isNullOrBlank()
  if (stacked) {
    Column(
      Modifier.drawBehind {
        if (showDivider) {
          val stroke = 1.dp.toPx()
          drawLine(dividerColor, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
        }
      }.padding(start = 12.dp, end = 12.dp, top = if (showDivider) 10.dp else 9.dp, bottom = 9.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        severity?.let {
          BasicText(
            it.uppercase(),
            style = SkillBillTheme.type.t2xs.copy(
              color = severityColor ?: stackedSeverityColor(it),
              fontWeight = FontWeight.SemiBold,
            ),
          )
        }
        if (refContent != null) refContent() else ref?.let { IdTag(it, Modifier.padding(top = 2.dp)) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        state?.let { SbLabel(it, SbLabelStyle.Dot(stateColor ?: SkillBillTheme.colors.fgFaint)) }
      }
      SelectionContainer(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          ExpandableText(title, SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.45.em))
          if (metaPresent) FindingLocationAndMeta(location, meta)
        }
      }
    }
    return
  }
  var open by rememberSaveable(title, location, meta, ref) { mutableStateOf(false) }
  val toggle = if (metaPresent) remember(title, location, meta, ref, metaPresent) { { open = !open } } else null
  val titleStyle = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.45.em)
  ExpandableResultRow(
    modifier = Modifier.drawBehind {
      if (showDivider) {
        val stroke = 1.dp.toPx()
        drawLine(dividerColor, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
      }
    },
    open = open,
    onToggle = toggle,
    toggleLabel = "Toggle finding",
    ref = {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        severity?.let { SeverityText(it, FINDING_SEVERITY_WIDTH, severityColor) }
        if (refContent != null) refContent() else ref?.let { IdTag(it, Modifier.padding(top = 2.dp)) }
      }
    },
    title = {
      if (metaPresent) {
        BasicText(
          title,
          style = titleStyle,
          maxLines = if (open) Int.MAX_VALUE else 2,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        SelectionContainer {
          ExpandableText(title, titleStyle, maxLines = 2)
        }
      }
    },
    stateBadge = state?.let {
      {
        SbLabel(it, SbLabelStyle.Dot(stateColor ?: SkillBillTheme.colors.fgFaint))
      }
    },
    disclosure = if (metaPresent) {
      {
        FindingLocationAndMeta(location, meta)
      }
    } else null,
    disclosureInsetStart = (if (severity != null) FINDING_SEVERITY_WIDTH + 10.dp else 0.dp) + 12.dp,
  )
}

@Composable
private fun FindingLocationAndMeta(location: String?, meta: String?) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    location?.takeIf { it.isNotBlank() }?.let { ResultRowFileChip(it.substringAfterLast('/'), it) }
    meta?.takeIf { it.isNotBlank() }?.let { text ->
      BasicText(
        text,
        style = SkillBillTheme.type.tXs.copy(
          color = SkillBillTheme.colors.fgFaint,
          fontWeight = FontWeight.Normal,
          lineHeight = 1.45.em,
        ),
      )
    }
  }
}

@Composable
private fun SeverityText(severity: String, width: Dp, colorOverride: Color? = null) {
  BasicText(
    severity.uppercase(),
    Modifier.width(width),
    style = SkillBillTheme.type.t2xs.copy(
      color = colorOverride ?: severityColor(severity),
      fontWeight = FontWeight.SemiBold,
      letterSpacing = .04f.em,
      lineHeight = 1.6.em,
    ),
    maxLines = 1,
  )
}

@Composable
private fun stackedSeverityColor(severity: String): Color = when (severity.lowercase()) {
  "blocker" -> SkillBillTheme.colors.stFailed
  "major", "high" -> SkillBillTheme.colors.stBlocked
  "medium" -> SkillBillTheme.colors.data1
  "minor", "low" -> SkillBillTheme.colors.fgMuted
  else -> SkillBillTheme.colors.fgFaint
}

@Composable
private fun severityColor(severity: String): Color = when (severity.lowercase()) {
  "blocker", "high" -> SkillBillTheme.colors.stFailed
  "major", "medium" -> SkillBillTheme.colors.stBlocked
  else -> SkillBillTheme.colors.fgFaint
}
