package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.PhasePipeline
import dev.skillbill.desktop.core.designsystem.detail.PipelineStep
import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.SubtaskRow
import dev.skillbill.desktop.core.designsystem.status.StatusKind

internal enum class TourDetailPill { Active, PauseRequested, Paused }

internal data class TourDetailState(
  val chrome: StageChrome = StageChrome(TourNav.Work),
  val overlay: StageOverlay = StageOverlay(),
  val subs: List<TourSubtask> = TourFixture.subtaskTitles.map { TourSubtask(it, StatusKind.Pending) },
  val pipelineIndex: Int = 0,
  val currentSubtask: Int? = null,
  val changes: TourChanges = TourChanges(0, 0, 0),
  val log: List<TourLogLine> = emptyList(),
  val logTotal: Int = 0,
  val pill: TourDetailPill = TourDetailPill.Active,
  val banner: Boolean = false,
)

internal fun tourRunDetailTimeline(): TourTimeline<TourDetailState> {
  fun spot(target: TourTarget, tip: TourTip, side: TipSide, pad: Dp): TourBeat<TourDetailState> =
    TourBeat { it.copy(overlay = it.overlay.copy(spotlight = Spotlight(target, tip, side, pad))) }
  return TourTimeline(
    TourDetailState(),
    listOf(
      TourBeat { it.runSubtask(0).copy(pipelineIndex = 0).appendLog(TourFixture.logPreplan) },
      spot(TourTarget.Subtasks, TourFixture.tipSubtasks, TipSide.Right, (-4).dp),
      TourBeat {
        it.copy(pipelineIndex = 4, changes = TourChanges(1, 64, 0))
          .appendLog(TourFixture.logPlan)
          .appendLog(TourFixture.logEditCsv)
          .appendLog(TourFixture.logEditCsvTest)
          .appendLog(TourFixture.logReview)
          .appendLog(TourFixture.logTest)
      },
      spot(TourTarget.Pipeline, TourFixture.tipPipeline, TipSide.Bottom, 2.dp),
      TourBeat {
        it.copy(pipelineIndex = 5).appendLog(TourFixture.logCommit)
          .finishSubtask(0).copy(changes = TourChanges(2, 105, 0)).appendLog(TourFixture.logSubtaskDone)
          .runSubtask(1).copy(pipelineIndex = 1)
      },
      spot(TourTarget.ActivityLog, TourFixture.tipActivity, TipSide.Top, 6.dp),
    ),
  )
}

internal fun TourDetailState.appendLog(text: String): TourDetailState {
  val n = logTotal + 1
  return copy(log = (log + TourLogLine("14:%02d".format(2 + n), text)).takeLast(6), logTotal = n)
}

internal fun TourDetailState.runSubtask(index: Int): TourDetailState = copy(
  currentSubtask = index,
  subs = subs.mapIndexed { i, s -> if (i == index) s.copy(status = StatusKind.Active, current = true) else s.copy(current = false) },
)

internal fun TourDetailState.finishSubtask(index: Int): TourDetailState = copy(
  subs = subs.mapIndexed { i, s -> if (i == index) s.copy(status = StatusKind.Completed, current = false) else s },
)

@Composable
internal fun TourRunDetailScene(step: Int, replayToken: Int, beat: Int) {
  val timeline = remember { tourRunDetailTimeline() }
  val state = timeline.stateAt(beat)
  TourStageFrame(state.chrome, state.overlay) {
    TourRunDetail(state)
  }
}

/** The staged run detail for ACME-121; step 05 drives the pill and the paused banner through [TourDetailState]. */
@Composable
internal fun TourRunDetail(state: TourDetailState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current
  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicText("Work", style = type.tSm.copy(color = colors.fgFaint))
        BasicText("›", Modifier.padding(horizontal = 8.dp), style = type.tSm.copy(color = colors.fgFaint))
        BasicText(TourFixture.goalKey, style = type.monoSm.copy(color = colors.fgFaint))
        Spacer(Modifier.weight(1f))
        SbLabel(
          when (state.pill) {
            TourDetailPill.Active -> "Active"
            TourDetailPill.PauseRequested -> "Pause requested"
            TourDetailPill.Paused -> "Paused"
          },
          style = when (state.pill) {
            TourDetailPill.Active -> SbLabelStyle.Dot(colors.stActive)
            TourDetailPill.PauseRequested -> SbLabelStyle.Tint(colors.accent)
            TourDetailPill.Paused -> SbLabelStyle.Dot(colors.stPaused)
          },
        )
      }
      Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 14.dp).hairline(Edge.Bottom, colors.borderSubtle)) {
        BasicText(TourFixture.goalTitle, style = type.tXl.copy(color = colors.fg))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TourChip {
            CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.folder, SbIconSize.Xs) }
            BasicText("acme-web", style = type.tXs.copy(color = colors.fgMuted))
          }
          TourChip {
            CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.branch, SbIconSize.Xs) }
            BasicText(TourFixture.goalBranch, style = type.monoSm.copy(color = colors.fgMuted))
          }
          TourChip {
            TourAgentDot(TourFixture.claude)
            BasicText("Claude · opus", style = type.tXs.copy(color = colors.fgMuted))
          }
          TourChip {
            BasicText("${state.changes.files} files", style = type.tXs.copy(color = colors.fgMuted))
            BasicText("+${state.changes.adds}", style = type.tXs.copy(color = colors.stActive))
            BasicText("−${state.changes.deletes}", style = type.tXs.copy(color = colors.stFailed))
          }
        }
      }
      Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.width(270.dp).fillMaxHeight().hairline(Edge.End, colors.borderSubtle).padding(start = 12.dp, top = 16.dp, end = 12.dp)) {
          val done = state.subs.count { it.status == StatusKind.Completed }
          Column(Modifier.tourTarget(TourTarget.Subtasks)) {
            TourSectionLabel("Subtasks $done/3", Modifier.padding(horizontal = 8.dp))
            Column(Modifier.padding(top = 10.dp)) {
              state.subs.forEachIndexed { index, sub ->
                SubtaskRow(
                  number = "${index + 1}",
                  status = sub.status,
                  progress = sub.progress,
                  title = sub.title,
                  current = sub.current,
                )
              }
            }
          }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 22.dp, top = 16.dp, end = 22.dp)) {
          if (state.currentSubtask != null) {
            BasicText(
              buildAnnotatedString {
                withStyle(SpanStyle(color = colors.fgFaint)) { append("Subtask ${state.currentSubtask + 1} · ") }
                append(state.subs[state.currentSubtask].title)
              },
              style = type.tMd.copy(color = colors.fg, fontWeight = FontWeight.SemiBold),
            )
          }
          Box(
            Modifier.fillMaxWidth().padding(top = 18.dp).tourTarget(TourTarget.Pipeline)
              .clip(RoundedCornerShape(10.dp))
              .background(colors.bgInset)
              .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
              .padding(horizontal = 14.dp, vertical = 14.dp),
          ) {
            PhasePipeline(
              TourFixture.phaseNames.mapIndexed { index, name ->
                PipelineStep(
                  name,
                  when {
                    index < state.pipelineIndex -> PipelineStepState.Done
                    index == state.pipelineIndex -> PipelineStepState.Current
                    else -> PipelineStepState.Pending
                  },
                )
              },
            )
          }
          TourSectionLabel("Latest activity", Modifier.padding(top = 26.dp))
          Column(
            Modifier.padding(top = 10.dp).tourTarget(TourTarget.ActivityLog),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            state.log.forEach { line ->
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(line.time, style = type.monoSm.copy(color = colors.fgFaint))
                BasicText(line.text, style = type.monoSm.copy(color = colors.fg2, fontWeight = FontWeight.Medium))
              }
            }
          }
        }
      }
    }
    AnimatedVisibility(
      state.banner,
      Modifier.align(Alignment.TopCenter),
      enter = if (reduced) {
        EnterTransition.None
      } else {
        slideInVertically(tween(600, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) { -it / 4 } + fadeIn(tween(400))
      },
      exit = if (reduced) {
        ExitTransition.None
      } else {
        slideOutVertically(tween(400, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) { -it / 4 } + fadeOut(tween(300))
      },
    ) {
      TourPauseBanner(Modifier.fillMaxWidth().padding(top = 54.dp, start = 16.dp, end = 16.dp))
    }
  }
}

@Composable
internal fun TourSectionLabel(text: String, modifier: Modifier = Modifier) {
  BasicText(
    text.uppercase(),
    modifier,
    style = SkillBillTheme.type.tXs.copy(
      color = SkillBillTheme.colors.fgFaint,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = .06.em,
    ),
  )
}
