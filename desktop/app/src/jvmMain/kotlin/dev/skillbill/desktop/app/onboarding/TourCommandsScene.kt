package dev.skillbill.desktop.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbElevation
import dev.skillbill.desktop.core.designsystem.component.sbOverlaySurface
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.overlay.CommandGroup
import dev.skillbill.desktop.core.designsystem.overlay.CommandList

internal data class TourCommandsState(
  val chrome: StageChrome = StageChrome(TourNav.Work),
  val overlay: StageOverlay = StageOverlay(),
  val paletteOpen: Boolean = false,
  val query: String = "",
  val highlight: Int = 0,
)

/** Filter only, keeping order, as the mock's `filterCmds` does; group heads survive on matching items. */
internal fun tourFilteredCommandGroups(query: String): List<CommandGroup> =
  TourFixture.commandGroups.map { group ->
    CommandGroup(group.head, group.items.filter { it.label.contains(query, ignoreCase = true) })
  }

/** Step 08 "Keys for everything": the staged palette opens, then "pause" is typed. */
internal fun tourCommandsTimeline(): TourTimeline<TourCommandsState> = TourTimeline(
  TourCommandsState(),
  listOf(
    TourBeat { it.copy(paletteOpen = true) },
    TourBeat { it.copy(query = TourFixture.paletteQuery, highlight = 0) },
  ),
)

@Composable
internal fun TourCommandsScene(step: Int, replayToken: Int, beat: Int) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val timeline = remember { tourCommandsTimeline() }
  val state = timeline.stateAt(beat)
  var ran by remember(step, replayToken, state.highlight) { mutableStateOf<Int?>(null) }
  val groups = tourFilteredCommandGroups(state.query)
  TourStageFrame(state.chrome, state.overlay) {
    TourWorkBoard(TourFixture.newGoalRowSettled)
    if (state.paletteOpen) {
      Box(Modifier.fillMaxSize().background(colors.scrim))
      Column(
        Modifier.align(Alignment.TopCenter).padding(top = 90.dp)
          .widthIn(max = SkillBillSize.commandMenu).fillMaxWidth()
          .sbOverlaySurface(SbElevation.Modal),
      ) {
        Row(
          Modifier.fillMaxWidth().height(46.dp).hairline(Edge.Bottom, colors.border).padding(horizontal = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.search, SbIconSize.Xs) }
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.query.isEmpty()) {
              BasicText(TourFixture.palettePlaceholder, style = type.tLg.copy(color = colors.fgFaint))
            } else {
              BasicText(state.query, style = type.tLg.copy(color = colors.fg))
            }
            Box(Modifier.padding(start = 1.dp).width(1.5.dp).height(15.dp).background(colors.accent))
          }
        }
        CommandList(
          groups,
          ran ?: state.highlight,
          onRun = { id -> ran = groups.flatMap { it.items }.indexOfFirst { it.id == id }.coerceAtLeast(0) },
          onHover = {},
        )
      }
    }
  }
}
