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
internal fun tourReviewTimeline(): TourTimeline<TourReviewState> = TourTimeline(
  TourReviewState(),
  listOf(
    TourBeat {
      it.copy(
        targetTint = true, plusShown = true,
        overlay = it.overlay.copy(
          spotlight = Spotlight(TourTarget.DiffLine, TourFixture.tipCommentLine, TipSide.Bottom, pad = 2.dp, radius = 4.dp),
          cursor = DemoCursor(TourTarget.DiffPlus),
        ),
      )
    },
    TourBeat {
      it.copy(
        plusShown = false, draft = TourFixture.reviewComment,
        overlay = it.overlay.copy(spotlight = null, cursor = it.overlay.cursor?.pressed()),
      )
    },
    TourBeat {
      it.copy(
        draft = null, youTag = "Pending", reviewLabel = "Add review · 1", reviewPrimary = true,
        overlay = it.overlay.copy(
          spotlight = Spotlight(TourTarget.AddReview, TourFixture.tipSendReview, TipSide.Bottom, pad = 4.dp),
          cursor = DemoCursor(TourTarget.AddReview),
        ),
      )
    },
    TourBeat {
      it.copy(
        reviewLabel = "Review added",
        reviewPrimary = false,
        youTag = "Sent to agent",
        toastShown = true,
        claude = TourClaudeReply.Replied,
        lineFlash = true,
        targetTint = false,
        rows = it.rows.map { row -> if (row.target) row.copy(text = TourFixture.csvFixedLine) else row },
        overlay = it.overlay.copy(spotlight = null, cursor = it.overlay.cursor?.pressed()),
      )
    },
  ),
)

@Composable
internal fun TourReviewScene(step: Int, replayToken: Int, beat: Int) {
  val timeline = remember { tourReviewTimeline() }
  val state = timeline.stateAt(beat)
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
