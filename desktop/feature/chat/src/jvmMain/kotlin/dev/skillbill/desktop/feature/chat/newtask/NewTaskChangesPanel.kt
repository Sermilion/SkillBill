package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.domain.model.ChatWorktreeChanges
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.*
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
internal fun NewTaskChangesPanel(state: ChatChangesState.Shown, onOpenDiff: () -> Unit = {}, onOpenFile: (String) -> Unit = {}, onOpenWorktree: (ChatWorktreeChanges, String?) -> Unit = { _, _ -> }) {
  if (state.changes.worktrees.isNotEmpty()) {
    Column {
      state.changes.worktrees.forEach { worktree ->
        BasicText(worktree.repositoryRoot, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        NewTaskChangesPanel(state.copy(changes = worktree.changes, baseline = worktree.baseline, paths = worktree.paths),
          onOpenDiff = { onOpenWorktree(worktree, null) }, onOpenFile = { onOpenWorktree(worktree, it) })
      }
    }
    return
  }
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val data = state.changes
  Column(Modifier.widthIn(min = 220.dp).fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText("Changes", style = type.tSmMedium.copy(color = colors.fg))
      BasicText(fileCount(data.files.size), style = type.tXs.copy(color = colors.fgFaint))
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("+${data.insertions}", style = type.monoSm.copy(fontSize = type.tXs.fontSize, color = colors.stDone))
        BasicText("−${data.deletions}", style = type.monoSm.copy(fontSize = type.tXs.fontSize, color = colors.stFailed))
      }
      Spacer(Modifier.weight(1f))
      SbButton("Open diff", onOpenDiff, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.external)
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
    Column(Modifier.padding(4.dp)) {
      data.files.take(6).forEach { file ->
        val parts = changePathParts(file.path)
        val letter = changeLetter(file.status)
        val tone = when (letter.tone) { ChangeLetterTone.Done -> colors.stDone; ChangeLetterTone.Muted -> colors.fgMuted; ChangeLetterTone.Failed -> colors.stFailed; ChangeLetterTone.Planning -> colors.stPlanning }
        val interaction = remember(file.path) { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val focused by interaction.collectIsFocusedAsState()
        val active = hovered || focused
        val shape = RoundedCornerShape(SkillBillRadius.sm)
        SbTooltip(file.path) {
          Row(Modifier.fillMaxWidth().height(30.dp).background(if (active) colors.bgActive else Color.Transparent, shape).hoverable(interaction).clickable(interaction, indication = null, role = Role.Button) { onOpenFile(file.path) }.semantics { contentDescription = file.path }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(letter.letter, style = type.monoSm.copy(fontSize = type.t2xs.fontSize, fontWeight = FontWeight.Medium, color = tone), modifier = Modifier.width(14.dp).clearAndSetSemantics { contentDescription = letter.label }, maxLines = 1)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              BasicText(parts.name, style = type.monoSm.copy(fontSize = type.tSm.fontSize, color = if (active) colors.fg else colors.fg2), modifier = Modifier.alignByBaseline(), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
              parts.folder?.let { folder ->
                BasicText(folder, style = type.tXs.copy(color = colors.fgFaint), modifier = Modifier.weight(1f, fill = false).alignByBaseline(), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
              }
            }
            file.insertions?.takeIf { it > 0 }?.let { BasicText("+$it", style = type.monoSm.copy(fontSize = type.tXs.fontSize, color = colors.stDone), maxLines = 1) }
            file.deletions?.takeIf { it > 0 }?.let { BasicText("−$it", style = type.monoSm.copy(fontSize = type.tXs.fontSize, color = colors.stFailed), maxLines = 1) }
          }
        }
      }
      if (data.files.size > 6) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val focused by interaction.collectIsFocusedAsState()
        val active = hovered || focused
        val shape = RoundedCornerShape(SkillBillRadius.sm)
        Row(Modifier.fillMaxWidth().height(30.dp).background(if (active) colors.bgActive else Color.Transparent, shape).hoverable(interaction).clickable(interaction, indication = null, onClick = onOpenDiff).padding(start = 30.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(moreFiles(data.files.size - 6), style = type.tXs.copy(color = if (active) colors.fg else colors.fgMuted))
          androidx.compose.runtime.CompositionLocalProvider(dev.skillbill.desktop.core.designsystem.LocalContentColor provides if (active) colors.fg else colors.fgMuted) {
            SbIcon(LucideIcons.arrowRight, SbIconSize.Xs)
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).drawBehind { drawLine(colors.borderSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText("Changes since chat opened" + chatChangesMade(data.commitCount, data.uncommitted).let { if (it.isEmpty()) "" else " · $it" }, style = type.tXs.copy(color = colors.fgFaint))
    }
  }
}
