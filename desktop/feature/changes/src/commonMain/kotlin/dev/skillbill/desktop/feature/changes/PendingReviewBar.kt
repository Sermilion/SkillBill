package dev.skillbill.desktop.feature.changes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.ui.changes.changesSans
import androidx.compose.ui.text.font.FontWeight

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PendingReviewBar(count: Int, review: ReviewBarUi, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val transition = rememberInfiniteTransition(label = "pending-dot")
  val pulse by transition.animateFloat(1f, .55f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pending-dot-alpha")
  Column(modifier.layeredShadow(SkillBillTheme.shadows.modal, RoundedCornerShape(9.dp)).background(colors.bgOverlay, RoundedCornerShape(9.dp))
    .border(1.dp, colors.borderStrong, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      androidx.compose.foundation.layout.Box(Modifier.padding(2.dp).then(if (LocalReducedMotion.current) Modifier else Modifier.alpha(pulse).scale(pulse)).background(colors.accent, CircleShape).padding(4.dp))
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
  Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }.layeredShadow(SkillBillTheme.shadows.modal, RoundedCornerShape(8.dp)).background(colors.bgOverlay, RoundedCornerShape(8.dp)).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("✓", style = changesSans(12f, FontWeight.Bold, colors.stDone))
    BasicText(text, style = changesSans(11f, FontWeight.Medium, colors.fg))
  }
}
