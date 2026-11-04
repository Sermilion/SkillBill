package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

@Composable
internal fun NewTaskPrefixPrompt(choices: List<String>, enabled: Boolean, onChoose: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Callout(tone = FeedbackTone.Info, body = "Choose an issue prefix or key to start this run.")
    IssuePrefixPicker(choices, enabled, onChoose)
  }
}

/** Lets the operator pick one of the suggested [choices], or type any prefix or key. */
@Composable
internal fun IssuePrefixPicker(choices: List<String>, enabled: Boolean, onChoose: (String) -> Unit) {
  val type = SkillBillTheme.type
  val colors = SkillBillTheme.colors
  var keyChoice by remember { mutableStateOf("") }
  val valid = ISSUE_CHOICE.matches(keyChoice.trim())
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText("Issue prefix or key", style = type.tXs.copy(color = colors.fgMuted))
    if (choices.isNotEmpty()) {
      SbMenu(choices.map { choice -> SbMenuEntry.Item(choice, { onChoose(choice) }, mono = true, enabled = enabled) })
    }
    SbInput(
      keyChoice,
      { keyChoice = it },
      modifier = Modifier.fillMaxWidth().gatesShortcutsWhileFocused(),
      placeholder = "Issue prefix or key",
      isError = keyChoice.isNotBlank() && !valid,
      enabled = enabled,
      onSubmit = { if (enabled && valid) onChoose(keyChoice.trim()) },
    )
    SbButton("Use issue prefix or key", { onChoose(keyChoice.trim()) }, size = SbControlSize.Small, enabled = enabled && valid)
  }
}

private val ISSUE_CHOICE = Regex("[A-Za-z0-9]+(?:-\\d+(?:\\.\\d+)?)?")
