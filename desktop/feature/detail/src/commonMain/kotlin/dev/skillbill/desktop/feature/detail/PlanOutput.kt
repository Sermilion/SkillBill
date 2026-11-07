package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.PlanOutputLine
import dev.skillbill.desktop.core.domain.model.WorkItem

private val TRANSCRIPT_TOOL_NAME = Regex("""^(?:\[subtask \d+] )?Tool (\S+)""")

internal fun planTranscriptLines(detail: Detail): List<PlanOutputLine> {
  val lines = detail.planOutputLines
  return lines.filterNot { it.isRaw }.ifEmpty { lines }.asReversed()
}

internal fun planTranscriptText(line: PlanOutputLine, roots: List<String>): String {
  val parsed = planOutputText(line.text, roots)
  val prefix = line.subtaskId?.let { "[subtask $it] " }.orEmpty()
  val body = when (parsed.kind) {
    "command" -> "Bash(${parsed.subject})"
    "read" -> "Read(${parsed.subject})"
    "write" -> "Write(${parsed.subject})"
    "search" -> "Grep(${parsed.subject})"
    "mcp" -> TRANSCRIPT_TOOL_NAME.find(line.text)?.groupValues?.get(1).orEmpty().ifBlank { parsed.subject }
    "tool" -> "${parsed.verb}(${parsed.subject})"
    else -> parsed.subject
  }
  return prefix + body
}

internal fun DetailSections.planTranscript(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  screen: PlanScreenState,
  onAction: (DetailAction) -> Unit,
) {
  val lines = planTranscriptLines(detail)
  val roots = planRoots(item)
  planSection(
    "plan-transcript",
    "Transcript",
    count = "${lines.size} lines",
    tools = {
      SbButton(
        "Copy",
        { onAction(DetailAction.Copy(lines.joinToString("\n") { it.text })) },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        icon = LucideIcons.copy,
      )
    },
  ) {
    item(key = "plan-transcript-box") {
      PlanTranscriptBox(lines, roots, screen.transcriptExpanded, { screen.transcriptExpanded = !screen.transcriptExpanded }, state, onAction)
    }
  }
}

@Composable
private fun PlanTranscriptBox(
  lines: List<PlanOutputLine>,
  roots: List<String>,
  expanded: Boolean,
  onToggle: () -> Unit,
  state: DetailUiState,
  onAction: (DetailAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val lineStyle = SkillBillTheme.type.t2xs.copy(lineHeight = 1.75.em)
  val lineHeight = with(LocalDensity.current) { (lineStyle.fontSize * 1.75f).toDp() }
  val bodyHeight = if (expanded) 340.dp else lineHeight * 3 + 18.dp
  Column(
    Modifier.fillMaxWidth()
      .border(1.dp, colors.borderSubtle, RoundedCornerShape(SkillBillRadius.md))
      .clip(RoundedCornerShape(SkillBillRadius.md)),
  ) {
    if (lines.isEmpty()) {
      BasicText(
        "No agent output yet.",
        Modifier.fillMaxWidth().height(bodyHeight).background(colors.bgInset).padding(horizontal = 12.dp, vertical = 9.dp),
        style = lineStyle.copy(color = colors.fgFaint),
      )
    } else {
      val listState = rememberLazyListState()
      LaunchedEffect(lines, expanded) {
        if (!expanded) listState.scrollToItem(lines.lastIndex)
      }
      LaunchedEffect(expanded) {
        if (expanded) listState.scrollToItem(lines.lastIndex + if (hasOlderPlanOutput(state)) 1 else 0)
      }
      SelectionContainer {
        LazyColumn(
          Modifier.fillMaxWidth().height(bodyHeight).background(colors.bgInset),
          state = listState,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        ) {
          if (expanded) pageFooter(DetailList.PlanOutput, state, onAction)
          itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
            TranscriptLine(line, roots, index == lines.lastIndex, expanded, lineStyle)
          }
        }
      }
    }
    Row(
      Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) {
        SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.rotate(if (expanded) 180f else 0f))
      }
      BasicText(if (expanded) "Show less" else "Expand", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
    }
  }
}

@Composable
private fun TranscriptLine(
  line: PlanOutputLine,
  roots: List<String>,
  last: Boolean,
  wrap: Boolean,
  lineStyle: TextStyle,
) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    BasicText(
      formatInstant(line.at, "HH:mm:ss"),
      Modifier.padding(end = 8.dp),
      style = lineStyle.copy(color = colors.fgFaint),
      maxLines = 1,
    )
    BasicText(
      planTranscriptText(line, roots),
      Modifier.weight(1f),
      style = lineStyle.copy(color = if (last) colors.fg2 else colors.fgMuted),
      maxLines = if (wrap) Int.MAX_VALUE else 1,
      softWrap = wrap,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun hasOlderPlanOutput(state: DetailUiState): Boolean {
  val loaded = state.sections as? SectionsUi.Loaded ?: return false
  return DetailList.PlanOutput in loaded.moreLists
}
