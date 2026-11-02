package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

@Composable
internal fun NewTaskOfferCard(
  branch: String,
  agent: String,
  busy: Boolean,
  offer: NewTaskOffer,
  onStart: (dev.skillbill.desktop.core.domain.model.NewTaskKind, String?) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  var keyChoice by remember { mutableStateOf("") }
  val prefixKind = offer.prefixKind
  val canStart = !busy && !offer.starting
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
      .border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.md))
      .background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.md)).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    BasicText("Goal run", Modifier.semantics { heading() }, style = type.tBaseMedium.copy(color = colors.fg))
    BasicText("base ${branch.ifBlank { "default branch" }} · $agent", style = type.monoSm.copy(color = colors.fgMuted))
    BasicText("plan › implement › audit › review › validate › qa › pr", style = type.monoSm.copy(color = colors.fgMuted))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SbButton("Start goal run", { onStart(dev.skillbill.desktop.core.domain.model.NewTaskKind.GoalRun, null) }, variant = SbButtonVariant.Primary, enabled = canStart)
      SbButton("Plan only", { onStart(dev.skillbill.desktop.core.domain.model.NewTaskKind.PlanOnly, null) }, enabled = canStart)
    }
    if (offer.choices.isNotEmpty() && prefixKind != null) {
      BasicText("Issue prefix or key", style = type.tXs.copy(color = colors.fgMuted))
      SbMenu(offer.choices.map { choice -> SbMenuEntry.Item(choice, { onStart(prefixKind, choice) }, mono = true, enabled = canStart) })
    } else if (prefixKind != null) {
      BasicText("Issue prefix or key", style = type.tXs.copy(color = colors.fgMuted))
      SbInput(
        keyChoice,
        { keyChoice = it },
        modifier = Modifier.fillMaxWidth().gatesShortcutsWhileFocused(),
        placeholder = "Issue prefix or key",
        isError = keyChoice.isNotBlank() && !ISSUE_CHOICE.matches(keyChoice.trim()),
        enabled = canStart,
        onSubmit = { if (canStart && ISSUE_CHOICE.matches(keyChoice.trim())) onStart(prefixKind, keyChoice.trim()) },
      )
      SbButton("Use issue prefix or key", { onStart(prefixKind, keyChoice.trim()) }, size = SbControlSize.Small, enabled = canStart && ISSUE_CHOICE.matches(keyChoice.trim()))
    }
    offer.problem?.let { Callout(tone = FeedbackTone.Error, body = it) }
  }
}

private val ISSUE_CHOICE = Regex("[A-Za-z0-9]+(?:-\\d+(?:\\.\\d+)?)?")
