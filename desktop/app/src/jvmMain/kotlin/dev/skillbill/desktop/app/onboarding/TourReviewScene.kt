package dev.skillbill.desktop.app.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.component.Toast

internal enum class TourClaudeReply { None, Thinking, Replied }

internal data class TourReviewState(
  val chrome: StageChrome = StageChrome(TourNav.Work),
  val overlay: StageOverlay = StageOverlay(),
  val rows: List<TourDiffLine> = TourFixture.reviewDiff,
  val targetTint: Boolean = false,
  val plusShown: Boolean = false,
  val draft: String? = null,
  val youTag: String? = null,
  val claude: TourClaudeReply = TourClaudeReply.None,
  val reviewLabel: String = "Add review",
  val reviewPrimary: Boolean = false,
  val toastShown: Boolean = false,
  val lineFlash: Boolean = false,
)

/**
 * Step 06 "Review like a teammate": the cursor clicks the line 8 gutter +, a draft is typed, Add
 * review sends it, a toast confirms, and Claude's fix lands on line 8. Buttons are tour-only; a
 * click never reaches a review, a changes repository or the runtime.
 */
internal fun tourReviewTimeline(): TourTimeline<TourReviewState> {
  val cues = mutableListOf<TourCue<TourReviewState>>()
  fun at(ms: Int, apply: (TourReviewState) -> TourReviewState) { cues += TourCue(ms, apply) }
  fun press(ms: Int) = at(ms) {
    it.copy(overlay = it.overlay.copy(cursor = it.overlay.cursor?.pressed()))
  }
  at(600) { it.copy(targetTint = true, plusShown = true) }
  at(900) {
    it.copy(
      overlay = it.overlay.copy(
        spotlight = Spotlight(TourTarget.DiffLine, TourFixture.tipCommentLine, TipSide.Bottom, pad = 2.dp, radius = 4.dp),
      ),
    )
  }
  at(1200) { it.copy(overlay = it.overlay.copy(cursor = DemoCursor(TourTarget.DiffPlus))) }
  press(2700)
  at(2860) { it.copy(plusShown = false, draft = "", overlay = it.overlay.copy(spotlight = null)) }
  at(3060) { it.copy(overlay = it.overlay.copy(cursor = null)) }
  TourFixture.reviewComment.indices.forEach { i ->
    at(3160 + i * (1000 / 48)) { it.copy(draft = TourFixture.reviewComment.substring(0, i + 1)) }
  }
  at(5310) { it.copy(draft = null, youTag = "Pending", reviewLabel = "Add review · 1", reviewPrimary = true) }
  at(5710) {
    it.copy(
      overlay = it.overlay.copy(
        spotlight = Spotlight(TourTarget.AddReview, TourFixture.tipSendReview, TipSide.Bottom, pad = 4.dp),
      ),
    )
  }
  at(5910) { it.copy(overlay = it.overlay.copy(cursor = DemoCursor(TourTarget.AddReview))) }
  press(7610)
  at(7770) {
    it.copy(
      reviewLabel = "Review added",
      reviewPrimary = false,
      youTag = "Sent to agent",
      toastShown = true,
      overlay = it.overlay.copy(spotlight = null),
    )
  }
  at(8170) { it.copy(overlay = it.overlay.copy(cursor = null)) }
  at(9070) { it.copy(toastShown = false, claude = TourClaudeReply.Thinking) }
  at(10270) {
    it.copy(
      claude = TourClaudeReply.Replied,
      lineFlash = true,
      targetTint = false,
      rows = it.rows.map { row -> if (row.target) row.copy(text = TourFixture.csvFixedLine) else row },
    )
  }
  return TourTimeline(TourReviewState(), cues, endMs = 10770)
}

@Composable
internal fun TourReviewScene(step: Int, replayToken: Int, onEnd: () -> Unit) {
  val timeline = remember { tourReviewTimeline() }
  val state by rememberTourTimeline(step, replayToken, timeline, LocalReducedMotion.current, onEnd)
  var toastDismissed by remember(step, replayToken) { mutableStateOf(false) }
  TourStageFrame(state.chrome, state.overlay) {
    Column(Modifier.fillMaxSize()) {
      TourReviewHeader(state)
      Row(Modifier.weight(1f).fillMaxWidth()) {
        TourReviewFileList()
        TourReviewDiff(state, Modifier.weight(1f).fillMaxHeight())
      }
    }
    if (state.toastShown && !toastDismissed) {
      Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) {
        Toast(TourFixture.reviewToast) { toastDismissed = true }
      }
    }
  }
}
