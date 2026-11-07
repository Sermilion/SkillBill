package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbElevation
import dev.skillbill.desktop.core.designsystem.component.sbOverlaySurface
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.ui.changes.changesSans
import androidx.compose.ui.text.font.FontWeight

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PendingReviewBar(count: Int, review: ReviewBarUi, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  Column(modifier.sbOverlaySurface(SbElevation.Modal).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StatusDot(colors.accent, Modifier.padding(2.dp), pulse = true)
      if (review.confirmingDiscard) {
        BasicText("Discard $count pending ${if (count == 1) "comment" else "comments"}?", style = changesSans(11f, FontWeight.Medium, colors.fg))
        SbButton("Discard", { onAction(ChangesWindowAction.ConfirmDiscard) }, variant = SbButtonVariant.Danger, size = SbControlSize.Small)
        SbButton("Keep", { onAction(ChangesWindowAction.KeepPending) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      } else {
        BasicText("$count pending ${if (count == 1) "comment" else "comments"}", style = changesSans(11f, FontWeight.SemiBold, colors.fg))
        BasicText("· only you can see them", style = changesSans(10.5f, FontWeight.Normal, colors.fgFaint))
        SbButton(if (review.inFlight == ReviewWrite.Discard) "Discarding…" else "Discard all", { onAction(ChangesWindowAction.RequestDiscard) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, enabled = !review.submitting)
        SbButton(if (review.inFlight == ReviewWrite.Submit) "Adding review…" else "Add review", { onAction(ChangesWindowAction.SubmitReview) }, variant = SbButtonVariant.Primary, size = SbControlSize.Small, shortcut = "⌘/Ctrl+↵", enabled = !review.submitting)
      }
    }
    review.problem?.let { problem ->
      Column(Modifier.semantics { error(problem); liveRegion = LiveRegionMode.Polite }) {
        Hint(problem, tone = FeedbackTone.Error)
      }
    }
  }
}

@Composable
internal fun ReviewToast(text: String, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }.sbOverlaySurface(SbElevation.Modal).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("✓", style = changesSans(12f, FontWeight.Bold, colors.stDone))
    BasicText(text, style = changesSans(11f, FontWeight.Medium, colors.fg))
  }
}
