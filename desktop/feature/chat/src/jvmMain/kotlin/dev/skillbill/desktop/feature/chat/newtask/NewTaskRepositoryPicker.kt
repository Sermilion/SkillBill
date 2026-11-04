package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbMenuItem
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import java.io.File

private val PICKER_HEIGHT = 264.dp
private val HEAD_ROW_HEIGHT = 32.dp
private val LEFT_COLUMN_FLOOR = 190.dp
private val ERROR_BLOCK_MAX_HEIGHT = 120.dp
private const val FILTER_ROW_THRESHOLD = 7
private const val FILTER_PLACEHOLDER = "Filter repositories"

internal data class NewTaskRepositoryRow(val repository: NewTaskRepository, val parent: String?)

internal fun repositoryRows(listed: List<NewTaskRepository>, added: List<NewTaskRepository>, selected: NewTaskRepository?): List<NewTaskRepositoryRow> {
  val merged = (listed + added + listOfNotNull(selected)).distinctBy { it.root }
  val nameCounts = merged.groupingBy { it.name }.eachCount()
  return merged.map { repository ->
    val shared = nameCounts.getValue(repository.name) > 1
    NewTaskRepositoryRow(repository, if (shared) File(repository.root).parentFile?.name else null)
  }
}

@Composable
internal fun NewTaskRepositoryPickerContent(state: NewTaskUiState, draft: NewTaskDraft, onAction: (NewTaskAction) -> Unit) {
  val colors = SkillBillTheme.colors
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val leftWidth = max(LEFT_COLUMN_FLOOR, maxWidth / 3)
    Row(Modifier.fillMaxWidth().height(PICKER_HEIGHT)) {
      RepositoryColumn(state, draft, Modifier.width(leftWidth).fillMaxHeight(), onAction)
      Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderSubtle))
      Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
        SbField("Base branch") {
          SbInput(
            draft.baseBranch, { onAction(NewTaskAction.BaseBranchChanged(it)) },
            modifier = fieldModifier.fillMaxWidth().gatesShortcutsWhileFocused(),
            mono = true,
          )
        }
      }
    }
  }
}

@Composable
private fun RepositoryColumn(state: NewTaskUiState, draft: NewTaskDraft, modifier: Modifier, onAction: (NewTaskAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val rows = repositoryRows(state.repositories, draft.addedRepositories, draft.repository)
  val filterable = rows.size > FILTER_ROW_THRESHOLD
  var filter by remember { mutableStateOf("") }
  val visible = if (filterable) rows.filter { it.repository.name.contains(filter.trim(), ignoreCase = true) } else rows
  Column(modifier) {
    Box(Modifier.fillMaxWidth().height(HEAD_ROW_HEIGHT).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
      BasicText("Repository", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1)
    }
    if (filterable) RepositoryFilter(filter) { filter = it }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
      items(visible, key = { it.repository.root }) { row ->
        SbTooltip(row.repository.root) {
          SbMenuItem(
            SbMenuEntry.Item(
              row.repository.name,
              { onAction(NewTaskAction.ChooseRepository(row.repository)) },
              endText = row.parent,
              checked = row.repository.root == draft.repository?.root,
            ),
          )
        }
      }
      if (visible.isEmpty() && rows.isNotEmpty()) {
        item {
          BasicText("No repository matches \"${filter.trim()}\"", Modifier.padding(8.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
        }
      }
    }
    state.repositoryError?.let { error -> RepositoryError(error) { onAction(NewTaskAction.AddRepository) } }
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
      SbMenuItem(SbMenuEntry.Item("Add repository", { onAction(NewTaskAction.AddRepository) }, icon = LucideIcons.plus))
    }
  }
}

@Composable
private fun RepositoryFilter(text: String, onChange: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val style = SkillBillTheme.type.tSm.copy(color = colors.fg)
  BasicTextField(
    text, onChange,
    Modifier.fillMaxWidth().height(HEAD_ROW_HEIGHT).gatesShortcutsWhileFocused().semantics { contentDescription = FILTER_PLACEHOLDER },
    singleLine = true, textStyle = style, cursorBrush = SolidColor(colors.fg),
    decorationBox = { field ->
      Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (text.isEmpty()) BasicText(FILTER_PLACEHOLDER, style = style.copy(color = colors.fgFaint), maxLines = 1, softWrap = false)
        field()
      }
    },
  )
}

@Composable
private fun RepositoryError(message: String, onChooseAnother: () -> Unit) {
  val colors = SkillBillTheme.colors
  val linkInteraction = remember { MutableInteractionSource() }
  Column(
    Modifier.fillMaxWidth().heightIn(max = ERROR_BLOCK_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    BasicText(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = SkillBillTheme.type.tSm.copy(color = colors.stFailed))
    BasicText(
      "Choose another folder",
      Modifier.pointerHoverIcon(PointerIcon.Hand)
        .clickable(linkInteraction, indication = null, role = Role.Button, onClick = onChooseAnother)
        .sbFocusRing(linkInteraction, RoundedCornerShape(SkillBillRadius.sm)),
      style = SkillBillTheme.type.tSm.copy(color = colors.accentText),
    )
  }
}
