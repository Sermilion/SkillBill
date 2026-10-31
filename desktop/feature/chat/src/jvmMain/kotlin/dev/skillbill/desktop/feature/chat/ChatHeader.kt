package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

/** The execution the chat is about, as of the latest read: title, state, phase and branch. */
@Composable
internal fun ChatHeader(session: ChatSession) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val item by session.header.collectAsState()
  val state = classify(item)
  val border = colors.border
  Column(
    Modifier.fillMaxWidth()
      .drawBehind { drawLine(border, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
      .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      item.issueKey?.let { BasicText(it, style = type.monoSm.copy(color = colors.fgMuted)) }
      BasicText(rememberRuntimeText(item.title), Modifier.weight(1f), style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ChatPill(state.name, tone = stateTone(state, colors), dot = true)
      (item.phaseExecution?.phaseId ?: item.stepLabel).takeIf { it.isNotBlank() }?.let { ChatPill(it, mono = true) }
      item.progress?.let { ChatPill("${it.done}/${it.total}", mono = true) }
      item.branch?.let { ChatPill(it, mono = true, icon = LucideIcons.branch, maxWidth = 260.dp) }
    }
  }
}
