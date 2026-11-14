package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.icon.BrandMark
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.feature.chat.StartCard
import dev.skillbill.desktop.feature.chat.StartCardContext

internal data class TourSidebarState(
  val chrome: StageChrome = StageChrome(TourNav.NewTask),
  val overlay: StageOverlay = StageOverlay(),
)

internal fun tourSidebarTimeline(): TourTimeline<TourSidebarState> = TourTimeline(
  TourSidebarState(),
  listOf(
    TourBeat<TourSidebarState> { it.spot(Spotlight(TourTarget.NavGroup, TourFixture.tipNavGroup, TipSide.Right, pad = 4.dp, radius = 8.dp)) },
    TourBeat<TourSidebarState> { it.spot(Spotlight(TourTarget.Usage, TourFixture.tipUsage, TipSide.Right, pad = 3.dp)) },
  ),
)

private fun TourSidebarState.spot(spotlight: Spotlight) = copy(overlay = overlay.copy(spotlight = spotlight))

private val homePanelState = TourChatState()

@Composable
internal fun TourSidebarScene(step: Int, replayToken: Int, beat: Int) {
  val timeline = remember { tourSidebarTimeline() }
  val state = timeline.stateAt(beat)
  TourStageFrame(state.chrome, state.overlay) {
    TourNewTaskPanel(homePanelState)
  }
}

/** The staged New task panel: header tab, home, messages column (plus the start card), and the composer. */
@Composable
internal fun TourNewTaskPanel(
  state: TourChatState,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
          Modifier.height(28.dp).background(colors.bgActive, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BasicText("New chat", style = type.tSm.copy(color = colors.fg))
          BasicText("×", style = type.tSm.copy(color = colors.fgFaint))
        }
        Box(Modifier.height(28.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
          BasicText("+", style = type.tSm.copy(color = colors.fgMuted))
        }
      }
      Spacer(Modifier.weight(1f))
      Kbd(listOf(MOD_KEY, "T"))
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (state.homeVisible) {
        Column(
          Modifier.align(Alignment.Center).padding(bottom = 120.dp).widthIn(max = 380.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          BrandMark(32.dp)
          BasicText(TourFixture.newTaskHeading, style = type.tXl.copy(color = colors.fg))
          BasicText(TourFixture.newTaskBody, style = type.tSm.copy(color = colors.fgMuted, textAlign = TextAlign.Center))
          Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TourFixture.homeCounts.forEach { TourChip { BasicText(it, style = type.tXs.copy(color = colors.fgMuted)) } }
          }
        }
      }
      Column(Modifier.fillMaxSize()) {
        Column(
          Modifier.weight(1f).align(Alignment.CenterHorizontally).width(680.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
        ) {
          state.messages.forEach { line ->
            when (line) {
              is TourChatLine.User -> TourUserBubble(line.text)
              is TourChatLine.Agent -> TourAgentMessage(line.text)
              TourChatLine.AgentThinking -> TourThinkingDots()
            }
          }
          if (state.startCard != StartCardStage.Hidden) {
            Box(Modifier.fillMaxWidth().padding(start = 32.dp)) {
              Box(Modifier.tourTarget(TourTarget.StartCard)) {
                StartCard(
                  TourFixture.startAction,
                  issueKey = null,
                  context = StartCardContext("acme-web", "main"),
                  starting = state.startCard == StartCardStage.Starting,
                  enabled = true,
                  onStart = {},
                )
              }
              // StartCard takes no modifier, so an invisible box registers the button's area for the demo cursor.
              Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(width = 84.dp, height = 30.dp)
                  .tourTarget(TourTarget.StartGoalButton),
              )
            }
          }
        }
        TourComposer(
          state.composerText, state.composerFocused, state.sendReady, state.sendPressed,
          Modifier.align(Alignment.CenterHorizontally).width(680.dp).padding(bottom = 18.dp),
        )
      }
    }
  }
}

@Composable
internal fun TourComposer(
  text: String,
  focused: Boolean,
  sendReady: Boolean,
  sendPressed: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current
  val shape = RoundedCornerShape(12.dp)
  Column(
    modifier.fillMaxWidth()
      .border(1.dp, if (focused) colors.borderStrong else colors.border, shape)
      .background(colors.bgRaised, shape)
      .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
  ) {
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
      Row {
        if (text.isEmpty()) {
          BasicText(TourFixture.composerPlaceholder, style = type.tBase.copy(color = colors.fgFaint))
        } else {
          BasicText(text, Modifier.weight(1f, fill = false), style = type.tBase.copy(color = colors.fg))
        }
        TourCaret()
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TourChip {
        CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.branch, SbIconSize.Xs) }
        BasicText("acme-web", style = type.tXs.copy(color = colors.fgMuted))
        BasicText("· main", style = type.tXs.copy(color = colors.fgFaint))
      }
      TourChip {
        TourAgentDot(TourFixture.claude)
        BasicText("Claude", style = type.tXs.copy(color = colors.fgMuted))
      }
      Spacer(Modifier.weight(1f))
      val sendScale by animateFloatAsState(
        if (sendPressed) .88f else 1f,
        if (reduced) snap() else tween(120),
        label = "send-press",
      )
      Box(
        Modifier.size(28.dp).graphicsLayer { scaleX = sendScale; scaleY = sendScale }
          .background(if (sendReady) colors.accent else colors.bgActive, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
      ) {
        CompositionLocalProvider(LocalContentColor provides if (sendReady) colors.accentFg else colors.fgFaint) {
          SbIcon(LucideIcons.arrowUp, SbIconSize.Sm)
        }
      }
    }
  }
}

@Composable
internal fun TourCaret() {
  val alpha = if (LocalReducedMotion.current) {
    1f
  } else {
    rememberInfiniteTransition(label = "caret").animateFloat(
      1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "caret-blink",
    ).value
  }
  Box(Modifier.padding(start = 1.dp).width(1.5.dp).height(15.dp).alpha(alpha).background(SkillBillTheme.colors.accent))
}
