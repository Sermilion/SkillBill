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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Step 07 "From goal to pull request": the finishing phases complete in order, the PR card's checks
 * go green one by one, sparks fire once, and the final spotlight lands on the card with "Ready for you".
 */
internal fun tourFinishTimeline(): TourTimeline<TourFinishState> {
  val cues = mutableListOf<TourCue<TourFinishState>>()
  fun at(ms: Int, apply: (TourFinishState) -> TourFinishState) { cues += TourCue(ms, apply) }
  at(600) { it.copy(running = 0) }
  at(1600) { it.copy(done = 1, running = 1) }
  at(2600) { it.copy(done = 2, running = 2) }
  at(3600) {
    it.copy(
      done = 3,
      running = 3,
      overlay = it.overlay.copy(
        spotlight = Spotlight(TourTarget.FinishSteps, TourFixture.tipGoalFinish, TipSide.Right, pad = 4.dp),
      ),
    )
  }
  at(4600) { it.copy(done = 4, running = 4, prShown = true) }
  at(4800) {
    it.copy(
      checks = List(TourFixture.checkNames.size) { StatusKind.Active },
      overlay = it.overlay.copy(spotlight = null),
    )
  }
  TourFixture.checkNames.indices.forEach { c ->
    at(4900 + c * 280) { state ->
      state.copy(checks = state.checks.mapIndexed { i, kind -> if (i == c) StatusKind.Completed else kind })
    }
  }
  at(6580) { it.copy(done = 5, running = -1) }
  at(6680) { it.copy(prReady = true, sparkBurst = it.sparkBurst + 1) }
  at(7180) {
    it.copy(
      overlay = it.overlay.copy(
        spotlight = Spotlight(TourTarget.PrCard, TourFixture.tipReadyForYou, TipSide.Bottom, pad = 6.dp, radius = 14.dp),
      ),
    )
  }
  return TourTimeline(TourFinishState(), cues, endMs = 7480)
}

@Composable
internal fun TourFinishScene(step: Int, replayToken: Int, onEnd: () -> Unit) {
  val timeline = remember { tourFinishTimeline() }
  val reduced = LocalReducedMotion.current
  val state by rememberTourTimeline(step, replayToken, timeline, reduced, onEnd)
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
