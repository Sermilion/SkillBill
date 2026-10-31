package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.ui.feedback.readProblemText

@Composable
fun ChangesScreen(viewModel: ChangesWindowViewModel) {
  val state by viewModel.state.collectAsState()
  Column(Modifier.fillMaxSize()) {
    BasicText(scopeLabel(state.scope, state.subtasks), Modifier.padding(12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    when (val content = state.content) {
      WindowContent.Loading -> CenterHint("Loading changes")
      WindowContent.Missing -> CenterHint("This execution is not in the open database.")
      WindowContent.NoBaseline -> CenterHint("No change baseline for this execution.")
      is WindowContent.Problem -> CenterHint(readProblemText(content.problem))
      is WindowContent.Ready -> Row(Modifier.fillMaxSize()) {
        FileListPane(state, viewModel::onAction, Modifier.width(340.dp).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(SkillBillTheme.colors.border))
        DiffPane(state, viewModel::onAction, Modifier.weight(1f).fillMaxHeight())
      }
    }
  }
}

@Composable
private fun CenterHint(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint(text) }
