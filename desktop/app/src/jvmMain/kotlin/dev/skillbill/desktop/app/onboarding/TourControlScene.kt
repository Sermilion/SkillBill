package dev.skillbill.desktop.app.onboarding

import androidx.compose.foundation.background
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
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

/**
 * The "Watch and steer" sequence: a keycap P requests pause, the run finishes subtask 2 and pauses into
 * a Needs you badge, then a keycap R resumes into subtask 3. The merged step lands on the final state.
 */
internal fun tourControlTimeline(): TourTimeline<TourDetailState> {
  val initial = TourDetailState(
    subs = TourFixture.subtaskTitles.mapIndexed { index, title ->
      TourSubtask(
        title,
        when (index) {
          0 -> StatusKind.Completed
          1 -> StatusKind.Active
          else -> StatusKind.Pending
        },
        current = index == 1,
      )
    },
    pipelineIndex = 2,
    currentSubtask = 1,
    changes = TourFixture.controlChanges,
    log = TourFixture.controlSeedLog,
    logTotal = TourFixture.controlSeedLogTotal,
  )
  return TourTimeline(
    initial,
    listOf(
      TourBeat {
        it.copy(
          pill = TourDetailPill.PauseRequested,
          overlay = it.overlay.copy(keycap = Keycap("P", "Pause after subtask", presses = 1)),
        ).appendLog(TourFixture.logPauseRequested)
      },
      TourBeat {
        it.finishSubtask(1).copy(
          pipelineIndex = 6,
          pill = TourDetailPill.Paused,
          banner = true,
          chrome = it.chrome.copy(workAttention = 1),
          overlay = it.overlay.copy(
            keycap = null,
            spotlight = Spotlight(TourTarget.NavWork, TourFixture.tipPausedNeedsYou, pad = 3.dp),
          ),
        ).appendLog(TourFixture.logCommitSubtaskTwo).appendLog(TourFixture.logPaused)
      },
      TourBeat {
        it.copy(overlay = it.overlay.copy(spotlight = null, keycap = Keycap("R", "Resume")))
      },
      TourBeat {
        it.runSubtask(2).copy(
          pipelineIndex = 1,
          pill = TourDetailPill.Active,
          banner = false,
          chrome = it.chrome.copy(workAttention = null),
          overlay = it.overlay.copy(keycap = it.overlay.keycap?.pressed()),
        ).appendLog(TourFixture.logResumed)
      },
    ),
  )
}

@Composable
internal fun TourControlScene(step: Int, replayToken: Int, beat: Int, timeline: TourTimeline<TourDetailState>) {
  val state = timeline.stateAt(beat)
  TourStageFrame(state.chrome, state.overlay) {
    TourRunDetail(state)
  }
}

/** The paused-run banner: title, body and two inert tour actions with a tinted accent border. */
@Composable
internal fun TourPauseBanner(modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(10.dp)
  Row(
    modifier.fillMaxWidth()
      .layeredShadow(SkillBillTheme.shadows.popover, shape)
      .background(colors.bgOverlay, shape)
      .border(1.dp, colors.accent.copy(alpha = .35f), shape)
      .padding(start = 14.dp, top = 11.dp, end = 12.dp, bottom = 11.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusIcon(StatusKind.Paused, null)
    Column(Modifier.weight(1f)) {
      BasicText(TourFixture.pauseBannerTitle, style = type.tSmMedium.copy(color = colors.fg))
      BasicText(TourFixture.pauseBannerBody, Modifier.padding(top = 1.dp), style = type.tXs.copy(color = colors.fgMuted))
    }
    SbButton("Run again with…", {}, size = SbControlSize.Small)
    SbButton("Resume", {}, variant = SbButtonVariant.Primary, size = SbControlSize.Small, shortcut = "R")
  }
}
