package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/**
 * HANDOFF 9.2.1: the decision for a no-change pause, recorded now and applied when the goal resumes. The app never resumes the
 * goal; Resume is the ordinary launch button. A recorded draft shows only its confirmation.
 */
@Composable
internal fun OperatorDecisionBlock(draft: DecisionDraft, recordEnabled: Boolean, editable: Boolean, inFlight: Boolean, onAction: (ControlsAction) -> Unit) {
  val recorded = draft.recorded
  if (recorded != null) {
    Hint(recordedText(recorded), FeedbackTone.Success, LucideIcons.check)
    return
  }
  val type = SkillBillTheme.type
  val colors = SkillBillTheme.colors
  // The design's decision panel: 12 padding inside a 1px `--r-md` frame (13 from the outer edge), parts 12 apart.
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(
    Modifier.fillMaxWidth().border(1.dp, colors.borderSubtle, shape).padding(13.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(draft.subtaskId?.let { "Decide subtask $it" } ?: "Decide the current subtask", style = type.tSmMedium.copy(color = colors.fg))
      BasicText("Recorded now, applied when the goal resumes", style = type.tSm.copy(color = colors.fgFaint))
    }
    // `.sb-seg` is a flex item of the `.l-col`, so it stretches to the panel's width; its buttons stay at the start.
    Segmented(OperatorDecision.entries.map { SegmentedOption(it, it.label(), enabled = editable) }, draft.decision, Modifier.fillMaxWidth()) { onAction(ControlsAction.ChooseDecision(it)) }
    if (draft.decision == OperatorDecision.RETRY_FIX) {
      SbField("Instructions for the retry", help = "Required for Retry fix.", error = draft.fieldError) {
        SbInput(
          draft.instructions,
          { onAction(ControlsAction.EditInstructions(it)) },
          fieldModifier.fillMaxWidth().gatesShortcutsWhileFocused(),
          placeholder = "What should the agent change?",
          isError = isError,
          enabled = editable,
        )
      }
    }
    SbButton(if (inFlight) WORKING else "Record decision", { onAction(ControlsAction.RecordDecision) }, variant = SbButtonVariant.Primary, enabled = recordEnabled)
    draft.inlineProblem?.let { Hint(it, FeedbackTone.Error) }
  }
}
