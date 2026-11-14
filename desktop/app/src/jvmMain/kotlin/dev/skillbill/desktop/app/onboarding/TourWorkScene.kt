package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.GroupHeader
import dev.skillbill.desktop.core.designsystem.list.ListRow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon

internal data class TourBoardState(
  val chrome: StageChrome = StageChrome(TourNav.Work),
  val overlay: StageOverlay = StageOverlay(),
  val newRow: TourBoardRow? = null,
)

internal fun tourWorkTimeline(): TourTimeline<TourBoardState> = TourTimeline(
  TourBoardState(),
  listOf(
    TourBeat {
      it.copy(overlay = it.overlay.copy(spotlight = Spotlight(TourTarget.BoardLanes, TourFixture.tipBoardLanes, TipSide.Bottom, 0.dp, 2.dp)))
    },
    TourBeat {
      it.copy(
        newRow = TourFixture.newGoalRow,
        overlay = it.overlay.copy(spotlight = Spotlight(TourTarget.NewRow, TourFixture.tipNewGoal, TipSide.Bottom, 0.dp, 2.dp)),
      )
    },
    TourBeat { it.copy(newRow = TourFixture.newGoalRowActive.copy(fresh = false)) },
  ),
)

@Composable
internal fun TourWorkScene(step: Int, replayToken: Int, beat: Int) {
  val timeline = remember { tourWorkTimeline() }
  val state = timeline.stateAt(beat)
  TourStageFrame(state.chrome, state.overlay) {
    TourWorkBoard(state.newRow)
  }
}

/** The staged Work board; later steps reuse it with the settled ACME-121 row. */
@Composable
internal fun TourWorkBoard(newRow: TourBoardRow?, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current
  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicText("Work", style = type.tBaseMedium.copy(color = colors.fg))
      Spacer(Modifier.weight(1f))
      Row(
        Modifier.width(180.dp).height(26.dp).border(1.dp, colors.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.search, SbIconSize.Xs) }
        BasicText("Filter", Modifier.weight(1f), style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
        Kbd("/")
      }
    }
    Column(Modifier.weight(1f).fillMaxWidth().tourTarget(TourTarget.BoardLanes)) {
      TourLane("Needs you", 0, TourTarget.LaneNeeds)
      TourEmptyRow("Nothing needs you right now.")
      TourLane("Goal runs", if (newRow != null) 2 else 1, TourTarget.LaneGoals)
      AnimatedVisibility(
        newRow != null,
        enter = if (reduced) {
          EnterTransition.None
        } else {
          expandVertically(tween(800, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) + fadeIn(tween(800))
        },
        exit = ExitTransition.None,
      ) {
        newRow?.let { TourBoardRowLine(it, Modifier.tourTarget(TourTarget.NewRow)) }
      }
      TourBoardRowLine(TourFixture.boardGoalRow)
      TourLane("Standalone phases", 1, TourTarget.LanePhases)
      TourBoardRowLine(TourFixture.boardPhaseRow)
      TourLane("Operations", 1, TourTarget.LaneOps)
      TourBoardRowLine(TourFixture.boardOpsRow)
      TourLane("Finished today", 2, TourTarget.LaneDone)
      TourFixture.boardDoneRows.forEach { TourBoardRowLine(it) }
    }
  }
}

@Composable
private fun TourLane(label: String, count: Int, target: TourTarget) {
  GroupHeader(
    label,
    count,
    modifier = Modifier.tourTarget(target),
    lead = { BasicText("▼", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontSize = 9.sp)) },
  )
}

@Composable
private fun TourEmptyRow(text: String) {
  Box(
    Modifier.fillMaxWidth().height(38.dp).hairline(Edge.Bottom, SkillBillTheme.colors.borderSubtle).padding(horizontal = 16.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    BasicText(text, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal))
  }
}

@Composable
private fun TourBoardRowLine(row: TourBoardRow, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  ListRow(
    title = row.title,
    onClick = {},
    modifier = modifier,
    id = row.key,
    status = { StatusIcon(row.status, row.iconProgress) },
    meta = {
      row.phaseTag?.let { SbTag(it, mono = true) }
      Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        TourAgentDot(row.agent)
        BasicText(row.agent.name, style = type.tXs.copy(color = colors.fgMuted))
      }
      SbTag(row.branch, mono = true)
      BasicText(row.label, style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
      Box(Modifier.width(72.dp).height(2.dp).background(colors.border, RoundedCornerShape(1.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(row.progress.coerceIn(0f, 1f)).background(colors.stActive))
      }
    },
    time = row.time,
    selected = row.fresh,
  )
}
