package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private object AgentIds { const val Claude = "claude"; const val Codex = "codex"; const val Cursor = "cursor"; const val Junie = "junie" }

internal fun agentInitials(agentId: String): String = when (agentId) {
  AgentIds.Claude -> "Cl"
  AgentIds.Codex -> "Cx"
  AgentIds.Cursor -> "Cu"
  AgentIds.Junie -> "Ju"
  else -> agentId.take(2).replaceFirstChar { it.uppercase() }.ifBlank { "?" }
}

internal fun agentDataSlot(agentId: String): Int = when (agentId) {
  AgentIds.Claude -> 4
  AgentIds.Codex -> 2
  AgentIds.Cursor -> 6
  AgentIds.Junie -> 3
  else -> (agentId.hashCode() % 6 + 6) % 6 + 1
}

fun SkillBillColors.dataColor(slot: Int): Color = when (slot) {
  1 -> data1; 2 -> data2; 3 -> data3; 4 -> data4; 5 -> data5; 6 -> data6
  else -> error("Data color slot must be between 1 and 6")
}

fun SkillBillColors.dataColorFor(key: String): Color = dataColor((key.hashCode() % 6 + 6) % 6 + 1)

@Composable
fun Avatar(agentId: String, small: Boolean = false, tooltip: String? = agentId) {
  val color = SkillBillTheme.colors.dataColor(agentDataSlot(agentId))
  val diameter = if (small) 16.dp else 18.dp
  val content: @Composable () -> Unit = {
    Box(Modifier.size(diameter).background(color.copy(alpha = .22f), CircleShape).semantics { contentDescription = agentId }, contentAlignment = Alignment.Center) {
      BasicText(agentInitials(agentId), style = SkillBillTheme.type.tXs.copy(color = color, fontWeight = FontWeight.SemiBold, fontSize = if (small) 8.5.sp else 9.sp, letterSpacing = (-.02).em), maxLines = 1)
    }
  }
  if (tooltip != null) SbTooltip(tooltip) { content() } else content()
}

@Composable
fun RepoIcon(name: String, color: Color = SkillBillTheme.colors.dataColorFor(name)) {
  Box(Modifier.size(16.dp).background(color.copy(alpha = .18f), RoundedCornerShape(4.dp)).semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
    BasicText(name.take(1).uppercase(), style = SkillBillTheme.type.tXs.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 9.sp), maxLines = 1)
  }
}
