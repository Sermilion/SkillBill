package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

internal enum class VerifyContextKind(val title: String) {
  NonGoals("Non-goals"), Constraints("Technical constraints"), Rollout("Rollout"),
}

internal fun LazyListScope.verifyContextSection(collapse: SectionCollapse, key: String, kind: VerifyContextKind, texts: List<String>) {
  item(key = key) {
    VerifyContextPanel(kind, texts, !collapse.isCollapsed(key, default = true)) { collapse.toggle(key, default = true) }
  }
}

@Composable
internal fun VerifyContextPanel(kind: VerifyContextKind, texts: List<String>, open: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val tone = when (kind) {
    VerifyContextKind.NonGoals -> colors.fgMuted
    VerifyContextKind.Constraints -> colors.stBlocked
    VerifyContextKind.Rollout -> colors.accentText
  }
  val icon = when (kind) {
    VerifyContextKind.NonGoals -> LucideIcons.x
    VerifyContextKind.Constraints -> LucideIcons.shield
    VerifyContextKind.Rollout -> LucideIcons.arrowRight
  }
  val note = when (kind) {
    VerifyContextKind.NonGoals -> "${texts.size} ${if (texts.size == 1) "exclusion" else "exclusions"}"
    VerifyContextKind.Constraints -> "${texts.size} ${if (texts.size == 1) "requirement" else "requirements"}"
    VerifyContextKind.Rollout -> "Release approach"
  }
  VerifyBox(Modifier.padding(top = 8.dp), fill = colors.bgInset) {
    Row(
      Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Toggle ${kind.title}", onClick = onToggle)
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(30.dp).background(tone.copy(alpha = .1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tone) { SbIcon(icon, 16.dp) }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(kind.title, style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg2))
        if (!open) texts.firstOrNull()?.let {
          BasicText(verifyText(it.replace(Regex("\\s+"), " ").trim()), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicText(note, style = SkillBillTheme.type.tXs.copy(color = tone))
      }
      VerifyChevron(open)
    }
    if (open) Column(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 16.dp, vertical = 4.dp)) {
      texts.forEachIndexed { index, text ->
        Column(
          Modifier.fillMaxWidth().then(if (index > 0) Modifier.hairline(Edge.Top, colors.borderSubtle) else Modifier)
            .padding(vertical = 12.dp).drawBehind { drawLine(tone.copy(alpha = .5f), Offset.Zero, Offset(0f, size.height), 2.dp.toPx()) }
            .padding(start = 12.dp),
        ) { VerifyReportText(text) }
      }
    }
  }
}
