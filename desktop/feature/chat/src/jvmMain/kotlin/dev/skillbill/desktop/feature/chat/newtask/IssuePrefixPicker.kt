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
internal fun NewTaskIssueKeyPrompt(
  choices: List<String>,
  selectedKey: String?,
  linkedKeys: List<String>,
  enabled: Boolean,
  onChoose: (String) -> Unit,
  onStart: () -> Unit,
) {
  var value by remember { mutableStateOf("") }
  val valid = NEW_TASK_COMPLETE_KEY.matches(value.trim())
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Callout(tone = FeedbackTone.Info, body = "Choose a complete issue key. Suggestions are not selected automatically.")
    if (choices.isNotEmpty()) {
      BasicText("Suggested issue keys", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
      SbMenu(choices.map { choice ->
        val source = if (choice in linkedKeys) "supplied Linear issue" else "next local project key"
        SbMenuEntry.Item("$choice from $source", {
          value = choice
          onChoose(choice)
        }, mono = true, enabled = enabled)
      })
    }
    SbInput(
      value,
      { value = it },
      modifier = Modifier.fillMaxWidth().gatesShortcutsWhileFocused(),
      placeholder = "Complete issue key",
      isError = value.isNotBlank() && !valid,
      enabled = enabled,
      onSubmit = { if (enabled && valid) onChoose(value.trim()) },
    )
    SbButton("Use issue key", { onChoose(value.trim()) }, size = SbControlSize.Small, enabled = enabled && valid)
    selectedKey?.let { key ->
      Callout(tone = FeedbackTone.Info, body = "Selected issue key: $key")
      SbButton("Start run $key", onStart, size = SbControlSize.Small, enabled = enabled && value.trim().equals(key, ignoreCase = true))
    }
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
