package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

internal data class TourFinishState(
  val chrome: StageChrome = StageChrome(TourNav.Work),
  val overlay: StageOverlay = StageOverlay(),
  val running: Int = -1,
  val done: Int = 0,
  val checks: List<StatusKind> = List(TourFixture.checkNames.size) { StatusKind.Pending },
  val prShown: Boolean = false,
  val prReady: Boolean = false,
  val sparkBurst: Int = 0,
)

/**
 * The "Review and ship" sequence: the finishing phases complete in order, the PR card's checks go
 * green one by one, sparks fire once, and the final spotlight lands on the card with "Ready for you".
 * The merged step lands on the final state.
 */
internal fun tourFinishTimeline(): TourTimeline<TourFinishState> = TourTimeline(
  TourFinishState(),
  listOf(
    TourBeat {
      it.copy(
        done = 3,
        running = 3,
        overlay = it.overlay.copy(
          spotlight = Spotlight(TourTarget.FinishSteps, TourFixture.tipGoalFinish, TipSide.Right, pad = 4.dp),
        ),
      )
    },
    TourBeat {
      it.copy(
        done = 4, running = 4, prShown = true,
        checks = List(TourFixture.checkNames.size) { StatusKind.Active },
        overlay = it.overlay.copy(spotlight = null),
      )
    },
    TourBeat {
      it.copy(
        done = TourFixture.finishSteps.size, running = -1,
        checks = List(TourFixture.checkNames.size) { StatusKind.Completed },
        prReady = true, sparkBurst = it.sparkBurst + 1,
        overlay = it.overlay.copy(
          spotlight = Spotlight(TourTarget.PrCard, TourFixture.tipReadyForYou, TipSide.Bottom, pad = 6.dp, radius = 14.dp),
        ),
      )
    },
  ),
)

@Composable
internal fun TourFinishScene(step: Int, replayToken: Int, beat: Int, timeline: TourTimeline<TourFinishState>) {
  val reduced = LocalReducedMotion.current
  val state = timeline.stateAt(beat)
  TourStageFrame(state.chrome, state.overlay) {
    Column(Modifier.fillMaxSize()) {
      TourFinishHeader(state)
      Row(
        Modifier.weight(1f).fillMaxWidth().padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
      ) {
        Column(Modifier.weight(1f)) {
          TourSectionLabel("After the last subtask")
          Column(
            Modifier.padding(top = 10.dp).tourTarget(TourTarget.FinishSteps)
              .border(1.dp, SkillBillTheme.colors.borderSubtle, RoundedCornerShape(10.dp)),
          ) {
            TourFixture.finishSteps.forEachIndexed { index, step ->
              TourFinishRow(
                step,
                status = when {
                  index < state.done -> StatusKind.Completed
                  index == state.running -> StatusKind.Active
                  else -> StatusKind.Pending
                },
                last = index == TourFixture.finishSteps.lastIndex,
              )
            }
          }
        }
        Column(Modifier.width(380.dp)) {
          TourSectionLabel("Pull request")
          AnimatedVisibility(
            state.prShown,
            enter = if (reduced) {
              EnterTransition.None
            } else {
              slideInVertically(tween(800, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) { it / 8 } + fadeIn(tween(600))
            },
            exit = ExitTransition.None,
          ) {
            TourPrCard(state, Modifier.padding(top = 10.dp))
          }
        }
      }
    }
    TourSparkBurst(TourTarget.PrCard, state.sparkBurst)
  }
}

@Composable
private fun TourFinishHeader(state: TourFinishState) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(TourFixture.goalKey, style = type.monoSm.copy(color = colors.fgFaint))
    BasicText("›", Modifier.padding(horizontal = 8.dp), style = type.tSm.copy(color = colors.fgFaint))
    BasicText("Goal finish", style = type.tBaseMedium.copy(color = colors.fg))
    Spacer(Modifier.weight(1f))
    SbLabel(
      if (state.prReady) "Completed" else "Finishing",
      style = SbLabelStyle.Dot(if (state.prReady) colors.stDone else colors.stActive),
    )
  }
}

@Composable
private fun TourFinishRow(step: TourFinishStep, status: StatusKind, last: Boolean) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().height(52.dp)
      .then(if (last) Modifier else Modifier.hairline(Edge.Bottom, colors.borderSubtle))
      .background(if (status == StatusKind.Active) colors.bgHover else Color.Transparent)
      .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusIcon(status, null, large = true)
    BasicText(
      step.phase,
      Modifier.width(130.dp),
      style = type.mono.copy(
        fontSize = 12.5.sp,
        color = when (status) {
          StatusKind.Completed -> colors.fg
          StatusKind.Active -> colors.accentText
          else -> colors.fgFaint
        },
      ),
    )
    if (status == StatusKind.Completed) {
      BasicText(step.outcome, style = type.tSm.copy(color = colors.fgMuted), maxLines = 1)
    }
  }
}
