package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val OUTPUT_MAX_HEIGHT = 240.dp

/** A turn's tool calls under one header; open while the turn streams, folded once it ends, until the operator toggles it. */
@Composable
internal fun ChatToolGroup(tools: List<ChatToolEntry>, streaming: Boolean) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
  val open = choice ?: streaming
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(Modifier.fillMaxWidth().border(1.dp, colors.border, shape).background(colors.bgPanel, shape)) {
    Row(
      Modifier.fillMaxWidth()
        .background(colors.bgRaised, if (open) RoundedCornerShape(topStart = SkillBillRadius.md, topEnd = SkillBillRadius.md) else shape)
        .clickable(role = Role.Button, onClickLabel = if (open) "Collapse tools" else "Expand tools") { choice = !open }
        .pointerHoverIcon(PointerIcon.Hand)
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
        .padding(horizontal = 10.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) {
        SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, SbIconSize.Sm)
      }
      BasicText(groupTitle(tools), style = type.tSmMedium.copy(color = colors.fg))
      groupSpan(tools)?.let { BasicText("· ${durationLabel(it)}", style = type.tSm.copy(color = colors.fgMuted)) }
      Spacer(Modifier.weight(1f))
      GroupStatus(tools)
    }
    if (open) {
      tools.forEach { tool -> key(tool.call.id) { ChatToolRow(tool) } }
    }
  }
}

@Composable
private fun GroupStatus(tools: List<ChatToolEntry>) {
  val colors = SkillBillTheme.colors
  val failed = tools.count { it.result?.isError == true }
  val done = tools.count { it.result != null }
  val (icon, tone, text) = when {
    failed > 0 -> Triple(LucideIcons.x, colors.stFailed, "$failed failed")
    done < tools.size -> Triple(LucideIcons.refresh, colors.accentText, "$done/${tools.size}")
    else -> Triple(LucideIcons.check, colors.stDone, "$done/${tools.size}")
  }
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    CompositionLocalProvider(LocalContentColor provides tone) { SbIcon(icon, SbIconSize.Xs) }
    BasicText(text, style = SkillBillTheme.type.tSm.copy(color = tone))
  }
}

/** One tool call: its summary, duration and status; clicking shows the input and output. */
@Composable
internal fun ChatToolRow(tool: ChatToolEntry) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  var expanded by rememberSaveable(tool.call.id) { mutableStateOf(false) }
  val result = tool.result
  val label = tool.call.summary?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: tool.call.name
  val iconTone = when {
    result == null -> colors.accentText
    result.isError -> colors.stFailed
    expanded -> colors.fg2
    else -> colors.fgMuted
  }
  Column(Modifier.fillMaxWidth().background(if (expanded) colors.bgInset else Color.Transparent).dividerAbove(colors.borderSubtle)) {
    Row(
      Modifier.fillMaxWidth()
        .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }
        .pointerHoverIcon(PointerIcon.Hand)
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides iconTone) { SbIcon(toolIcon(tool.call.name), SbIconSize.Xs) }
      BasicText(
        label,
        Modifier.weight(1f),
        style = type.tSm.copy(color = if (expanded) colors.fg else colors.fg2),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      BasicText(tool.call.name, style = type.monoSm.copy(color = colors.fgFaint))
      val start = tool.startedAt
      val end = tool.finishedAt
      when {
        result?.isError == true -> BasicText("Failed", style = type.tSm.copy(color = colors.stFailed))
        start != null && end != null -> BasicText(durationLabel(end - start), style = type.monoSm.copy(color = colors.fgMuted))
        result == null && start != null -> BasicText(durationLabel(liveElapsed(start)), style = type.monoSm.copy(color = colors.accentText))
      }
    }
    if (expanded) ToolDetails(tool)
  }
}

@Composable
private fun ToolDetails(tool: ChatToolEntry) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val result = tool.result
  val shell = toolIcon(tool.call.name) == LucideIcons.terminal
  SelectionContainer {
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      OutputBox {
        BasicText(
          tool.call.input.ifBlank { "No input provided." }.let { if (shell) "$ $it" else it },
          style = type.monoSm.copy(color = colors.fg2),
        )
      }
      when {
        result == null -> BasicText("Waiting for the result…", style = type.tSm.copy(color = colors.fgMuted))
        else -> OutputBox(error = result.isError) {
          BasicText(result.output.ifBlank { "No output." }, style = type.monoSm.copy(color = if (result.isError) colors.stFailed else colors.fgMuted))
        }
      }
    }
  }
}

@Composable
private fun OutputBox(error: Boolean = false, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Box(
    Modifier.fillMaxWidth().heightIn(max = OUTPUT_MAX_HEIGHT)
      .border(1.dp, if (error) colors.stFailed.copy(alpha = .4f) else colors.borderSubtle, shape)
      .background(colors.bgFrame, shape)
      .verticalScroll(rememberScrollState())
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) { content() }
}

private fun Modifier.dividerAbove(color: Color) = drawBehind {
  drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
}

internal fun groupTitle(tools: List<ChatToolEntry>): String {
  val running = tools.any { it.result == null }
  val shell = tools.all { toolIcon(it.call.name) == LucideIcons.terminal }
  val noun = when {
    shell && tools.size == 1 -> "command"
    shell -> "commands"
    tools.size == 1 -> "tool"
    else -> "tools"
  }
  return if (running) "Running ${tools.size} $noun" else "Ran ${tools.size} $noun"
}

private fun groupSpan(tools: List<ChatToolEntry>): kotlin.time.Duration? {
  val start = tools.mapNotNull { it.startedAt }.minOrNull() ?: return null
  if (tools.any { it.result == null }) return null
  val end = tools.mapNotNull { it.finishedAt }.maxOrNull() ?: return null
  return end - start
}
